package com.squash.squash.Controller;

import com.squash.squash.Service.Squash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/squash")
public class SquashController {
    @Autowired
    protected Squash sq;

    @GetMapping("/test")
    public Map<String, String> test() {
        return new HashMap<>() {
            {
                put("1", "1");
            }
        };
    }

    @PostMapping(
            value = "/compress",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<StreamingResponseBody> compress(@RequestParam String uid, @RequestPart("files") List<MultipartFile> files) {

        return sq.compress(uid, files);
    }

    @PostMapping(
            value = "/decompress",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, String>> decompress(@RequestParam String uid, @RequestPart("file") MultipartFile file) {

        return sq.decompress(uid, file);
    }
}
