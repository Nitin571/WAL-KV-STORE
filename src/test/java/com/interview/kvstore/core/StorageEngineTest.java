package com.interview.kvstore.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StorageEngineTest {

    private Path walFile;

    @BeforeEach
    void setUp() throws Exception {
        walFile = Files.createTempFile("engine-test", ".wal");
        Files.deleteIfExists(walFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(walFile);
    }

    @Test
    void putThenGetReturnsTheStoredValue() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("name", "Alice");
            assertEquals("Alice", engine.get("name"));
        }
    }

    @Test
    void getOnMissingKeyReturnsNull() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            assertNull(engine.get("doesNotExist"));
        }
    }

    @Test
    void deleteRemovesTheKey() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("name", "Alice");
            engine.delete("name");
            assertNull(engine.get("name"));
            assertEquals(0, engine.size());
        }
    }

    @Test
    void putOverwritesExistingValue() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("name", "Alice");
            engine.put("name", "Bob");
            assertEquals("Bob", engine.get("name"));
            assertEquals(1, engine.size());
        }
    }

    @Test
    void stateSurvivesCloseAndReopen() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("name", "Alice");
            engine.put("city", "NYC");
            engine.delete("city");
        } // engine closed here -- simulates a clean shutdown

        // Reopen as a brand new instance, same file -- this is the actual
        // persistence guarantee being tested, not just in-memory behavior.
        try (StorageEngine reopened = new StorageEngine(walFile)) {
            assertEquals("Alice", reopened.get("name"));
            assertNull(reopened.get("city"));
            assertEquals(1, reopened.size());
        }
    }

    @Test
    void recoveryResultIsExposedAfterReopen() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("a", "1");
            engine.put("b", "2");
        }

        try (StorageEngine reopened = new StorageEngine(walFile)) {
            var recovery = reopened.getLastRecoveryResult();
            assertEquals(2, recovery.recoveredEntries.size());
            assertFalse(recovery.tornWriteFound);
        }
    }
}
