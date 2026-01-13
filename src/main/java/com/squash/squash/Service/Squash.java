package com.squash.squash.Service;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.multipart.MultipartFile;
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
                    (int) 0,
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
            Map<Integer, String> map
    ) {
        if (node == null) return;
        if (node.isLeaf()) {
            map.put(node.Byte, code.length() > 0 ? code : "0");
            return;
        }
        buildCodeMap(node.leftTuple, code + "0", map);
        buildCodeMap(node.rightTuple, code + "1", map);
    }

    public Map<Integer, String> generateEmbeddings() {
        Map<Integer, String> embeddings = new HashMap<>();
        buildCodeMap(root, "", embeddings);
        return embeddings;
    }
}

class TreeNodeTuple {

    public Integer Byte;
    public int Frequency;
    public TreeNodeTuple leftTuple = null, rightTuple = null;

    public TreeNodeTuple(Integer Byte, int Frequency) {
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

    private void compressFileStream(
            String filename,
            InputStream in,
            DataOutputStream dos,
            Map<Integer, String> embeddings
    ) throws IOException {

        byte[] nameBytes = filename.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(nameBytes.length);
        dos.write(nameBytes);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        int bitBuffer = 0;
        int bitCount = 0;
        int originalSize = 0;

        int b;
        while ((b = in.read()) != -1) {
            originalSize++;
            String code = embeddings.get(b);
            if (code == null)
                throw new IOException("Missing embedding for byte: " + b);

            for (char c : code.toCharArray()) {
                bitBuffer = (bitBuffer << 1) | (c == '1' ? 1 : 0);
                if (++bitCount == 8) {
                    compressed.write((byte) bitBuffer);
                    bitBuffer = 0;
                    bitCount = 0;
                }
            }
        }

        int padding = 0;
        if (bitCount > 0) {
            padding = 8 - bitCount;
            compressed.write((byte) (bitBuffer << padding));
        }

        byte[] compressedBytes = compressed.toByteArray();

        dos.writeInt(originalSize);
        dos.writeInt(compressedBytes.length);
        dos.writeInt(padding);

        dos.write(compressedBytes);
    }

    public ResponseEntity<StreamingResponseBody> compress(
            String uid,
            List<MultipartFile> files
    ) {
        StreamingResponseBody stream = out -> {
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeInt(6);
            dos.writeBytes("squash");
            dos.writeInt(9);
            dos.writeBytes("--version");
            dos.writeInt(1);

            Map<Integer, Integer> freq = new HashMap<>();
            for (MultipartFile file : files) {
                try (InputStream in = file.getInputStream()) {
                    int b;
                    while ((b = in.read()) != -1) freq.merge((byte) b & 0xFF, 1, Integer::sum);
                }
            }

            PriorityQueue<TreeNodeTuple> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n.Frequency));
            freq.forEach((k, v) -> pq.add(new TreeNodeTuple(k, v)));
            Map<Integer, String> embeddings = new TreeBuilder(pq).generateEmbeddings();

            dos.writeInt(embeddings.size());
            for (var e : embeddings.entrySet()) {
                dos.writeByte(e.getKey());
                dos.writeUTF(e.getValue());
            }

            for (MultipartFile file : files) {
                try (InputStream in = file.getInputStream()) {
                    compressFileStream(file.getOriginalFilename(), in, dos, embeddings);
                }
            }
            dos.flush();
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + uid + ".tar.sq\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(stream);
    }

    public ResponseEntity<Map<String, String>> decompress(String uid, MultipartFile file) {
        Map<String, String> result = new HashMap<>();

        try (InputStream in = file.getInputStream();
             DataInputStream dInp = new DataInputStream(in)) {

            int hLen = dInp.readInt();
            String header = new String(dInp.readNBytes(hLen), StandardCharsets.UTF_8);
            if (!header.equals("squash")) throw new RuntimeException("Corrupted file");

            int vLen = dInp.readInt();
            String versionMarker = new String(dInp.readNBytes(vLen), StandardCharsets.UTF_8);
            if (!versionMarker.equals("--version")) throw new RuntimeException("Corrupted file");

            int version = dInp.readInt();
            if (version != 1) throw new RuntimeException("Version mismatch");

            int embSize = dInp.readInt();
            Map<String, Byte> embeddings = new HashMap<>();
            for (int i = 0; i < embSize; i++) {
                byte b = dInp.readByte();
                String code = dInp.readUTF();
                embeddings.put(code, b);
            }

            while (dInp.available() > 0) {
                int fnameLen = dInp.readInt();
                String filename = new String(dInp.readNBytes(fnameLen), StandardCharsets.UTF_8);

                int originalLen = dInp.readInt();
                int compressedLen = dInp.readInt();
                int padding = dInp.readInt();

                byte[] compressed = dInp.readNBytes(compressedLen);

                List<Byte> decompressed = new ArrayList<>();
                StringBuilder key = new StringBuilder();
                int written = 0;

                for (int i = 0; i < compressed.length; i++) {
                    int bitsToRead = 8;
                    if (i == compressed.length - 1 && padding > 0) bitsToRead -= padding;

                    for (int j = 7; j >= 8 - bitsToRead; j--) {
                        boolean isSet = ((compressed[i] >> j) & 1) == 1;
                        key.append(isSet ? '1' : '0');

                        if (embeddings.containsKey(key.toString())) {
                            byte orig = embeddings.get(key.toString());
                            decompressed.add(orig);
                            key.setLength(0);
                            written++;
                            if (written >= originalLen) break;
                        }
                    }
                }

                byte[] bytes = new byte[decompressed.size()];
                for (int i = 0; i < decompressed.size(); i++) bytes[i] = decompressed.get(i);
                result.put(filename, Base64.getEncoder().encodeToString(bytes));
            }

        } catch (Exception e) {
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

}

