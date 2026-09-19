package com.interview.kvstore.api;

import com.interview.kvstore.core.StorageEngine;
import com.interview.kvstore.exception.StorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Thin HTTP layer over StorageEngine. No business logic lives here --
 * every request calls straight into the same put()/get()/delete() methods
 * that were already compiled and crash-tested directly (CLI, simulated
 * torn write, real kill -9). This controller doesn't change what
 * "durable" means; it just gives HTTP callers a way to trigger it.
 */
@RestController
@RequestMapping("/keys")
public class KeyValueController {

    private final StorageEngine engine;

    public KeyValueController(StorageEngine engine) {
        this.engine = engine;
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> put(@PathVariable String key, @RequestBody Map<String, String> body) {
        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request body must include \"value\""));
        }
        try {
            engine.put(key, value); // returns only after the write is fsynced to disk
            return ResponseEntity.ok(Map.of("key", key, "value", value, "status", "durably written"));
        } catch (StorageException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{key}")
    public ResponseEntity<?> get(@PathVariable String key) {
        String value = engine.get(key);
        if (value == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "key not found"));
        }
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<?> delete(@PathVariable String key) {
        try {
            engine.delete(key);
            return ResponseEntity.ok(Map.of("key", key, "status", "durably deleted"));
        } catch (StorageException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> size() {
        return ResponseEntity.ok(Map.of("count", engine.size()));
    }
}
