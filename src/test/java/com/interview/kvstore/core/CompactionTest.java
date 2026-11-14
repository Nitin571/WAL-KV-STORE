package com.interview.kvstore.core;

import com.interview.kvstore.recovery.CompactionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompactionTest {

    private Path walFile;

    @BeforeEach
    void setUp() throws Exception {
        walFile = Files.createTempFile("compaction-test", ".wal");
        Files.deleteIfExists(walFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(walFile);
    }

    @Test
    void compactionReclaimsSpaceFromOverwritesAndDeletes() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            // Write 500 updates to the same key, plus some deleted keys
            for (int i = 0; i < 500; i++) {
                engine.put("counter", "value-" + i);
                engine.put("temp-" + i, "data-" + i);
                engine.delete("temp-" + i);
            }
            engine.put("permanent", "survivor");

            long sizeBefore = Files.size(walFile);
            assertTrue(sizeBefore > 20_000, "WAL should have grown large from 1000+ operations");

            CompactionResult result = engine.compact();

            assertEquals(2, result.keysRetained(), "Only 'counter' and 'permanent' should be retained");
            long sizeAfter = Files.size(walFile);
            assertTrue(sizeAfter < sizeBefore / 10, "Compacted WAL should be a small fraction of original size");
            assertEquals(sizeAfter, result.bytesAfter());
            assertEquals(sizeBefore, result.bytesBefore());

            // Check that active data is preserved intact
            assertEquals("value-499", engine.get("counter"));
            assertEquals("survivor", engine.get("permanent"));
            assertEquals(2, engine.size());
        }

        // Reopen new engine to verify persistence of compacted log
        try (StorageEngine reopened = new StorageEngine(walFile)) {
            assertEquals("value-499", reopened.get("counter"));
            assertEquals("survivor", reopened.get("permanent"));
            assertEquals(2, reopened.size());

            // Verify subsequent writes continue seamlessly
            reopened.put("newKey", "newValue");
            assertEquals("newValue", reopened.get("newKey"));
            assertEquals(3, reopened.size());
        }
    }

    @Test
    void autoCompactionTriggersWhenThresholdIsExceeded() throws Exception {
        // Set a small threshold of 1 KB
        long smallThreshold = 1024L;
        try (StorageEngine engine = new StorageEngine(walFile, smallThreshold)) {
            for (int i = 0; i < 100; i++) {
                engine.put("key", "a-fairly-long-value-string-to-cross-threshold-" + i);
            }

            // Without auto-compaction, 100 writes of ~70 bytes each would produce > 7 KB.
            // With auto-compaction at 1 KB, the file is continuously bounded near the threshold.
            long finalSize = Files.size(walFile);
            assertTrue(finalSize <= smallThreshold + 200,
                    "Auto-compaction should keep file bounded near threshold, was: " + finalSize);
            assertTrue(finalSize < 3000,
                    "Auto-compaction should prevent file from reaching uncompacted 7KB+ size");
            assertEquals("a-fairly-long-value-string-to-cross-threshold-99", engine.get("key"));
            assertEquals(1, engine.size());
        }
    }
}
