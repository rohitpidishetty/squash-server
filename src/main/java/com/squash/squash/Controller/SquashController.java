package com.squash.squash.Controller;

import com.squash.squash.Service.Squash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
        return HashMap<>() {
            {
                put("1","1");
            }
        };
    }

    @PostMapping("/compress")
    public ResponseEntity<StreamingResponseBody> compress(@RequestBody Map<Object, Object> payload) {
        Map<?, ?> m = (Map<?, ?>) payload.get("data");
        String TAR_FILE = (String) m.get("uid");
        return sq.compress((Map<Object, Object>) m.get("fileContent"), TAR_FILE);
    }

    @PostMapping("/decompress")
    public Map<String, String> decompress(@RequestBody Map<Object, Object> payload) {

        Map<String, String> data = sq.decompress((String) payload.get("uid"), (String) payload.get("filename"), (Object) payload.get("content"));
//        System.out.println(data);

        return data;
    }
}
