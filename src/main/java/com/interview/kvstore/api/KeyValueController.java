package com.interview.kvstore.api;

import com.interview.kvstore.core.StorageEngine;
import com.interview.kvstore.exception.StorageException;
import com.interview.kvstore.wal.BatchOp;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Production REST API over StorageEngine. Exposes full CRUD, range scans,
 * prefix queries, and atomic batch writes.
 */
@RestController
@RequestMapping("/keys")
public class KeyValueController {

    private final StorageEngine engine;

    public KeyValueController(StorageEngine engine) {
        this.engine = engine;
    }

    @PutMapping(value = "/{key}", consumes = "application/json")
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

    /**
     * Query keys with optional prefix or range scan.
     * Examples:
     *   GET /keys                        -> { "count": 42 }
     *   GET /keys?prefix=user:&limit=10  -> { "count": 2, "keys": { "user:1": "...", "user:2": "..." } }
     *   GET /keys?from=a&to=m&limit=50   -> { "count": 5, "keys": { ... } }
     */
    @GetMapping
    public ResponseEntity<?> query(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") int limit) {

        if (prefix != null) {
            Map<String, String> matches = engine.scan(prefix, limit);
            return ResponseEntity.ok(Map.of("prefix", prefix, "count", matches.size(), "keys", matches));
        }

        if (from != null || to != null) {
            Map<String, String> matches = engine.range(from, to, limit);
            return ResponseEntity.ok(Map.of("count", matches.size(), "keys", matches));
        }

        return ResponseEntity.ok(Map.of("count", engine.size()));
    }

    /**
     * Executes an atomic batch of puts and deletes with a single fsync.
     */
    @PostMapping(value = "/batch", consumes = "application/json")
    public ResponseEntity<?> batchWrite(@RequestBody BatchRequest request) {
        List<BatchOp> ops = new ArrayList<>();
        if (request.puts() != null) {
            for (Map.Entry<String, String> entry : request.puts().entrySet()) {
                ops.add(BatchOp.put(entry.getKey(), entry.getValue()));
            }
        }
        if (request.deletes() != null) {
            for (String key : request.deletes()) {
                ops.add(BatchOp.delete(key));
            }
        }

        if (ops.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Batch request contains no operations"));
        }

        try {
            engine.batchWrite(ops);
            return ResponseEntity.ok(Map.of(
                    "status", "durably written",
                    "operationCount", ops.size()
            ));
        } catch (StorageException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    public record BatchRequest(Map<String, String> puts, List<String> deletes) {}
}
