package com.interview.kvstore.api;

import com.interview.kvstore.core.StorageEngine;
import com.interview.kvstore.exception.StorageException;
import com.interview.kvstore.recovery.CompactionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Administrative endpoints for monitoring, telemetry, and manual maintenance tasks.
 * Sensitive maintenance endpoints (/admin/stats, /admin/compact) require authentication via
 * X-Admin-Token or Authorization: Bearer headers.
 * The liveness probe (/admin/health) remains public for orchestrators (Docker, Kubernetes).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final StorageEngine engine;
    private final String adminToken;

    public AdminController(StorageEngine engine) {
        this(engine, "secret-admin-token");
    }

    @Autowired
    public AdminController(StorageEngine engine,
                           @Value("${kvstore.admin.token:secret-admin-token}") String adminToken) {
        this.engine = engine;
        this.adminToken = adminToken;
    }

    private boolean isAuthorized(String adminTokenHeader, String authHeader) {
        if (adminToken == null || adminToken.isBlank()) {
            return true; // No token configured -> allow access
        }
        if (adminTokenHeader != null && adminToken.equals(adminTokenHeader.trim())) {
            return true;
        }
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = authHeader.substring(7).trim();
            return adminToken.equals(bearer);
        }
        return false;
    }

    /**
     * Unauthenticated health check for Docker/Kubernetes readiness and liveness probes.
     */
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
    public ResponseEntity<?> stats(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminTokenHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAuthorized(adminTokenHeader, authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized: Invalid or missing admin token"));
        }

        StorageEngine.EngineStats stats = engine.getStats();
        return ResponseEntity.ok(Map.of(
                "keyCount", stats.keyCount(),
                "walFileSizeBytes", stats.walFileSizeBytes(),
                "lastCompactedAt", stats.lastCompactedAt() > 0 ? stats.lastCompactedAt() : "never",
                "recoveredEntriesCount", stats.recoveredEntriesCount(),
                "tornWriteDetectedOnStartup", stats.tornWriteDetected(),
                "currentSequenceNumber", stats.currentSequenceNumber(),
                "totalWriteOps", stats.totalWriteOps(),
                "avgWriteLatencyMicros", stats.avgWriteLatencyMicros(),
                "compactionCount", stats.compactionCount()
        ));
    }

    /** Programmatic / test convenience overload */
    public ResponseEntity<?> stats() {
        return stats(adminToken, null);
    }

    @PostMapping("/compact")
    public ResponseEntity<?> compact(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminTokenHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAuthorized(adminTokenHeader, authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized: Invalid or missing admin token"));
        }

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

    /** Programmatic / test convenience overload */
    public ResponseEntity<?> compact() {
        return compact(adminToken, null);
    }

    @GetMapping("/integrity")
    public ResponseEntity<?> integrity(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminTokenHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAuthorized(adminTokenHeader, authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized: Invalid or missing admin token"));
        }

        StorageEngine.IntegrityStatus status = engine.getIntegrityStatus();
        if (!status.integrityEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "status", "DISABLED",
                    "integrityEnabled", false
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", status.status(),
                "integrityEnabled", true,
                "walFormatVersion", status.walFormatVersion(),
                "verifiedEntries", status.verifiedEntries(),
                "lastSequenceNumber", status.lastSequenceNumber(),
                "chainRootPrefix", status.chainRootPrefix() != null ? status.chainRootPrefix() : ""
        ));
    }

    /** Programmatic / test convenience overload */
    public ResponseEntity<?> integrity() {
        return integrity(adminToken, null);
    }
}
