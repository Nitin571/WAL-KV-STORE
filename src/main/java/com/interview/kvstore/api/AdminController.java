package com.interview.kvstore.api;

import com.interview.kvstore.core.StorageEngine;
import com.interview.kvstore.exception.StorageException;
import com.interview.kvstore.recovery.CompactionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Administrative endpoints for monitoring, telemetry, and manual maintenance tasks.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final StorageEngine engine;

    public AdminController(StorageEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        if (engine.isHealthy()) {
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "walWritable", true,
                    "keyCount", engine.size()
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "walWritable", false,
                    "error", "Storage engine is closed or lock lost"
            ));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        StorageEngine.EngineStats stats = engine.getStats();
        return ResponseEntity.ok(Map.of(
                "keyCount", stats.keyCount(),
                "walFileSizeBytes", stats.walFileSizeBytes(),
                "lastCompactedAt", stats.lastCompactedAt() > 0 ? stats.lastCompactedAt() : "never",
                "recoveredEntriesCount", stats.recoveredEntriesCount(),
                "tornWriteDetectedOnStartup", stats.tornWriteDetected(),
                "currentSequenceNumber", stats.currentSequenceNumber()
        ));
    }

    @PostMapping("/compact")
    public ResponseEntity<?> compact() {
        try {
            CompactionResult result = engine.compact();
            return ResponseEntity.ok(Map.of(
                    "status", "compacted",
                    "bytesBefore", result.bytesBefore(),
                    "bytesAfter", result.bytesAfter(),
                    "bytesReclaimed", result.bytesReclaimed(),
                    "keysRetained", result.keysRetained()
            ));
        } catch (StorageException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Compaction failed: " + e.getMessage()));
        }
    }
}
