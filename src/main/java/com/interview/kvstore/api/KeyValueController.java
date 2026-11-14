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
 * prefix queries, and atomic batch writes with strict input validation.
 */
@RestController
@RequestMapping("/keys")
public class KeyValueController {

    public static final int MAX_KEY_LENGTH = 1024;
    public static final int MAX_VALUE_LENGTH = 8 * 1024 * 1024; // 8 MiB
    public static final int MAX_BATCH_SIZE = 1000;

    private final StorageEngine engine;

    public KeyValueController(StorageEngine engine) {
        this.engine = engine;
    }

    @PutMapping(value = "/{key}", consumes = "application/json")
    public ResponseEntity<?> put(
            @PathVariable("key") String key,
            @RequestBody Map<String, String> body) throws StorageException {
        validateKey(key);
        if (body == null || !body.containsKey("value")) {
            throw new IllegalArgumentException("Request body must include \"value\"");
        }
        String value = body.get("value");
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Value length exceeds maximum allowed limit of " + MAX_VALUE_LENGTH + " characters");
        }

        engine.put(key, value); // returns only after write is fsynced to disk
        return ResponseEntity.ok(Map.of("key", key, "value", value, "status", "durably written"));
    }

    @GetMapping("/{key}")
    public ResponseEntity<?> get(@PathVariable("key") String key) {
        validateKey(key);
        String value = engine.get(key);
        if (value == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "key not found"));
        }
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<?> delete(@PathVariable("key") String key) throws StorageException {
        validateKey(key);
        engine.delete(key);
        return ResponseEntity.ok(Map.of("key", key, "status", "durably deleted"));
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
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {

        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Limit must be between 1 and 1000 (was " + limit + ")");
        }

        if (from != null && to != null && from.compareTo(to) > 0) {
            throw new IllegalArgumentException("Invalid range: 'from' key (" + from + ") cannot be greater than 'to' key (" + to + ")");
        }

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
    public ResponseEntity<?> batchWrite(@RequestBody BatchRequest request) throws StorageException {
        if (request == null) {
            throw new IllegalArgumentException("Batch request body cannot be null");
        }

        List<BatchOp> ops = new ArrayList<>();
        if (request.puts() != null) {
            for (Map.Entry<String, String> entry : request.puts().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                validateKey(k);
                if (v == null) {
                    throw new IllegalArgumentException("Batch PUT value cannot be null for key: " + k);
                }
                if (v.length() > MAX_VALUE_LENGTH) {
                    throw new IllegalArgumentException("Batch PUT value exceeds max length for key: " + k);
                }
                ops.add(BatchOp.put(k, v));
            }
        }
        if (request.deletes() != null) {
            for (String key : request.deletes()) {
                validateKey(key);
                ops.add(BatchOp.delete(key));
            }
        }

        if (ops.isEmpty()) {
            throw new IllegalArgumentException("Batch request contains no operations");
        }
        if (ops.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Batch size exceeds maximum limit of " + MAX_BATCH_SIZE + " operations");
        }

        engine.batchWrite(ops);
        return ResponseEntity.ok(Map.of(
                "status", "durably written",
                "operationCount", ops.size()
        ));
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key cannot be null or blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Key length exceeds maximum allowed limit of " + MAX_KEY_LENGTH + " characters");
        }
    }

    public record BatchRequest(Map<String, String> puts, List<String> deletes) {}
}
