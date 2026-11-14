package com.interview.kvstore.core;

import com.interview.kvstore.wal.BatchOp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchAndScanTest {

    private Path walFile;

    @BeforeEach
    void setUp() throws Exception {
        walFile = Files.createTempFile("batch-scan-test", ".wal");
        Files.deleteIfExists(walFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(walFile);
    }

    @Test
    void batchWriteDurablyAppliesPutsAndDeletes() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("user:0", "initial");

            List<BatchOp> ops = List.of(
                    BatchOp.put("user:1", "Alice"),
                    BatchOp.put("user:2", "Bob"),
                    BatchOp.put("user:3", "Charlie"),
                    BatchOp.delete("user:0")
            );

            engine.batchWrite(ops);

            assertNull(engine.get("user:0"));
            assertEquals("Alice", engine.get("user:1"));
            assertEquals("Bob", engine.get("user:2"));
            assertEquals("Charlie", engine.get("user:3"));
            assertEquals(3, engine.size());
        }

        // Reopen to verify all batch operations were durably flushed in one fsync
        try (StorageEngine reopened = new StorageEngine(walFile)) {
            assertNull(reopened.get("user:0"));
            assertEquals("Alice", reopened.get("user:1"));
            assertEquals("Bob", reopened.get("user:2"));
            assertEquals("Charlie", reopened.get("user:3"));
            assertEquals(3, reopened.size());
        }
    }

    @Test
    void scanPrefixReturnsOrderedMatchingKeys() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("order:2026-01-02", "itemB");
            engine.put("user:101", "Alice");
            engine.put("order:2026-01-01", "itemA");
            engine.put("order:2026-01-03", "itemC");
            engine.put("user:102", "Bob");

            Map<String, String> userMatches = engine.scan("user:", 10);
            assertEquals(2, userMatches.size());
            assertEquals(List.of("user:101", "user:102"), List.copyOf(userMatches.keySet()));

            Map<String, String> orderMatches = engine.scan("order:", 2); // limit = 2
            assertEquals(2, orderMatches.size());
            assertEquals(List.of("order:2026-01-01", "order:2026-01-02"), List.copyOf(orderMatches.keySet()));
        }
    }

    @Test
    void rangeScanReturnsKeysInBoundaries() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("b", "2");
            engine.put("d", "4");
            engine.put("a", "1");
            engine.put("e", "5");
            engine.put("c", "3");

            Map<String, String> range = engine.range("b", "d", 10);
            assertEquals(3, range.size());
            assertEquals(List.of("b", "c", "d"), List.copyOf(range.keySet()));
            assertEquals("2", range.get("b"));
            assertEquals("3", range.get("c"));
            assertEquals("4", range.get("d"));
        }
    }
}
