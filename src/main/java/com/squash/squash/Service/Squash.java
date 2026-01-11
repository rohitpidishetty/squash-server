package com.squash.squash.Service;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

class TreeBuilder {

    private TreeNodeTuple root = null;

    public TreeBuilder(PriorityQueue<TreeNodeTuple> queue) {
        while (queue.size() > 1) {
            TreeNodeTuple t1 = queue.poll();
            TreeNodeTuple t2 = queue.poll();
            TreeNodeTuple sub_root = new TreeNodeTuple(
                    (byte) 0,
                    (t1.Frequency + t2.Frequency)
            );
            sub_root.leftTuple = t1;
            sub_root.rightTuple = t2;
            queue.offer(sub_root);
        }
        this.root = queue.poll();
    }

    private void buildCodeMap(
            TreeNodeTuple node,
            String code,
            Map<Byte, String> map
    ) {
        if (node == null) return;
        if (node.isLeaf()) {
            map.put(node.Byte, code.length() > 0 ? code : "0");
            return;
        }
        buildCodeMap(node.leftTuple, code + "0", map);
        buildCodeMap(node.rightTuple, code + "1", map);
    }

    public Map<Byte, String> generateEmbeddings() {
        Map<Byte, String> embeddings = new HashMap<>();
        buildCodeMap(root, "", embeddings);
        return embeddings;
    }
}

class FileReader {

    private Map<String, byte[]> files;
    private String base_dir;
    private DataOutputStream dos;
    private Map<Byte, String> embeddings;

    public FileReader(
            Map<String, byte[]> files,
            String base_dir,
            DataOutputStream dos,
            Map<Byte, String> embeddings
    ) {
        this.files = files;
        this.base_dir = base_dir;
        this.dos = dos;
        this.embeddings = embeddings;
    }

    public synchronized void mapEmbeddingsAndWriteToSquash() throws Exception {
        for (Map.Entry<String, byte[]> m : this.files.entrySet()) {
            String fileName = m.getKey();
            byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            this.dos.writeInt(fileNameBytes.length);
            this.dos.write(fileNameBytes);
            int bitBuffer = 0, bitCount = 0;

            byte[] arr = m.getValue();
            this.dos.writeInt(arr.length); // original buffer length
            ArrayList<Byte> compressed = new ArrayList<>();
            for (byte b : arr) {
                String code = this.embeddings.get(b);
                if (code == null) {
                    throw new RuntimeException("Missing embedding for byte " + b);
                }
                for (char ch : code.toCharArray()) {
                    bitBuffer = (bitBuffer << 1) | (ch == '1' ? 1 : 0);
                    bitCount++;
                    if (bitCount == 8) {
                        compressed.add((byte) bitBuffer);

                        bitBuffer = 0;
                        bitCount = 0;
                    }
                }
            }
            int paddingBits = 0;
            if (bitCount > 0) {
                paddingBits = 8 - bitCount;
                bitBuffer <<= paddingBits;
                compressed.add((byte) bitBuffer);
            }

            this.dos.writeInt(compressed.size()); // compressed bits length
            this.dos.writeInt(paddingBits); // extra padding len
            for (Byte com : compressed) this.dos.write(com);
        }

    }
}

class TreeNodeTuple {

    public byte Byte;
    public int Frequency;
    public TreeNodeTuple leftTuple = null, rightTuple = null;

    public TreeNodeTuple(byte Byte, int Frequency) {
        this.Byte = Byte;
        this.Frequency = Frequency;
    }

    public boolean isLeaf() {
        return leftTuple == null && rightTuple == null;
    }

    @Override
    public String toString() {
        return "(" + this.Byte + " " + this.Frequency + ")";
    }
}

@Service
@CrossOrigin
public class Squash {

    public ResponseEntity<StreamingResponseBody> compress(Map<Object, Object> payload, String uuid) {
        Map<Byte, Integer> frequency = new HashMap<>();
        PriorityQueue<TreeNodeTuple> pQ = new PriorityQueue<>(Comparator.comparingInt(a -> a.Frequency));
        String TAR_FILE = uuid + ".tar.sq";

        try {
            Map<String, byte[]> fileData = new HashMap<>();

            for (Map.Entry<Object, Object> m : payload.entrySet()) {
                String filename = (String) m.getKey();
                ArrayList<Integer> bytes = (ArrayList<Integer>) m.getValue();
                byte[] _bytes_ = new byte[bytes.size()];
                for (int i = 0; i < _bytes_.length; i++) {
                    _bytes_[i] = bytes.get(i).byteValue();
                    frequency.put(_bytes_[i], frequency.getOrDefault(_bytes_[i], 0) + 1);
                }
                fileData.put(filename, _bytes_);
            }

            for (Map.Entry<Byte, Integer> freqMap : frequency.entrySet()) {
                pQ.add(new TreeNodeTuple(freqMap.getKey(), freqMap.getValue()));
            }
            Map<Byte, String> embeddings = new TreeBuilder(pQ).generateEmbeddings();

            File file = new File(TAR_FILE);
            if (!file.exists()) file.createNewFile();

            try (FileOutputStream fos = new FileOutputStream(file);
                 DataOutputStream dos = new DataOutputStream(fos)) {

                dos.writeInt(6);
                dos.writeBytes("squash");
                dos.writeInt(9);
                dos.writeBytes("--version");
                dos.writeInt(1);

                dos.writeInt(embeddings.size());
                for (Map.Entry<Byte, String> em : embeddings.entrySet()) {
                    dos.writeByte(em.getKey());
                    dos.writeUTF(em.getValue());
                }

                new FileReader(fileData, "", dos, embeddings)
                        .mapEmbeddingsAndWriteToSquash();

                dos.flush();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        File fileToSend = new File(TAR_FILE);
        StreamingResponseBody stream = out -> {
            try (FileInputStream fis = new FileInputStream(fileToSend)) {
                fis.transferTo(out);
            } finally {
                boolean deleted = fileToSend.delete();
                System.out.println("File deleted? " + deleted);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileToSend.getName() + "\"")
                .contentLength(fileToSend.length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(stream);
    }

    public Map<String, String> decompress(String uid, String filename, Object content) {
        Map<String, String> originalContent = new HashMap<>();
        ArrayList<Integer> al = (ArrayList<Integer>) content;
        int i = 0;
        int n = al.size();
        byte[] conteneBuffer = new byte[n];
        while (i < n)
            conteneBuffer[i] = al.get(i++).byteValue();

        try {
            ByteArrayInputStream byteStream = new ByteArrayInputStream(conteneBuffer);
            DataInputStream dInp = new DataInputStream(byteStream);
            if (!new String(dInp.readNBytes(dInp.readInt())).equals("squash")) {
                return new HashMap<>() {
                    {
                        put("err", "Squash file is corrupted");
                    }
                };
            }

            if (!new String(dInp.readNBytes(dInp.readInt())).equals("--version")) {
                return new HashMap<>() {
                    {
                        put("err", "Squash file is corrupted");
                    }
                };
            }

            if (dInp.readInt() != 1) {
                return new HashMap<>() {
                    {
                        put("err", "Squash version mismatched");
                    }
                };
            }

            // Map size
            int map_size = dInp.readInt();
            Map<String, Byte> mappings = new HashMap<>();
            for (i = 0; i < map_size; i++) {
                byte b = dInp.readByte();
                String embedding = dInp.readUTF();
                mappings.put(embedding, b);
            }

            try {
                while (dInp.available() > 0) {
                    String fn = new String(
                            dInp.readNBytes(dInp.readInt()),
                            StandardCharsets.UTF_8
                    );

                    int originalBufferLen = dInp.readInt();
                    ArrayList<Byte> fos = new ArrayList<>();

                    int compressedArrLen = dInp.readInt();
                    int paddingLen = dInp.readInt();
                    System.out.printf("De-squashing %s ", fn);
                    int written = 0;
                    StringBuilder key = new StringBuilder();
                    i = 0;
                    for (; i < compressedArrLen; i++) {
                        byte b = (byte) (dInp.readByte() & 0xff);
                        int bitsToRead = 8;
                        if (i == compressedArrLen - 1 && paddingLen > 0) bitsToRead -=
                                paddingLen;
                        for (int j = 7; j >= 8 - bitsToRead; j--) {
                            boolean is_set_bit = ((b >> j) & 1) == 1;
                            key.append(is_set_bit ? '1' : '0');
                            String ref = key.toString();
                            if (mappings.containsKey(ref)) {
                                byte original = mappings.get(ref);
                                fos.add((byte) ((original & 0xff)));
                                key.setLength(0);
                                written++;
                                if (written >= originalBufferLen) break;
                            }
                        }
                    }
//                    System.out.println(fn + " " + fos);
                    // Convert to byte[]
                    byte[] bytes = new byte[written];
                    for (i = 0; i < written; i++) {
                        bytes[i] = fos.get(i).byteValue();
                    }
                    originalContent.put(fn, Base64.getEncoder().encodeToString(bytes));
                    System.out.printf("Successful\n");
                }
                System.out.printf("De-squashed all the files\n");
                return originalContent;
            } catch (Exception e) {
                System.err.printf("Squash error - %s\n", e.getMessage().toString());
                System.exit(1);
            }

        } catch (Exception e) {
            return new HashMap<>() {
                {
                    put("err", e.toString());
                }
            };
        }

        return new HashMap<>() {
            {
                put("err", "Something went wrong");
            }
        };
    }

}

