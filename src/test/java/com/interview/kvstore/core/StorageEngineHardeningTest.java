package com.interview.kvstore.core;

import com.interview.kvstore.exception.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StorageEngineHardeningTest {

    private Path walFile;

    @BeforeEach
    void setUp() throws Exception {
        walFile = Files.createTempFile("hardening-engine", ".wal");
        Files.deleteIfExists(walFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(walFile);
        Path lockFile = walFile.getParent() != null
                ? walFile.getParent().resolve(walFile.getFileName().toString() + ".lock")
                : Path.of(walFile.getFileName().toString() + ".lock");
        Files.deleteIfExists(lockFile);
    }

    @Test
    void fileLockPreventsConcurrentInstancesOnSameWal() throws Exception {
        try (StorageEngine engine1 = new StorageEngine(walFile)) {
            assertTrue(engine1.isHealthy());

            // A second instance attempting to open the same WAL must fail immediately
            StorageException ex = assertThrows(StorageException.class, () -> new StorageEngine(walFile));
            assertTrue(ex.getMessage().toLowerCase().contains("exclusive lock") ||
                            ex.getMessage().toLowerCase().contains("already in use"),
                    "Expected exclusive lock failure message, was: " + ex.getMessage());

            engine1.put("k1", "v1");
        }

        // Once engine1 is cleanly closed and lock released, opening engine2 must succeed
        try (StorageEngine engine2 = new StorageEngine(walFile)) {
            assertTrue(engine2.isHealthy());
            assertEquals("v1", engine2.get("k1"));
        }
    }

    @Test
    void healthCheckReflectsEngineState() throws Exception {
        StorageEngine engine = new StorageEngine(walFile);
        assertTrue(engine.isHealthy());

        engine.close();
        assertFalse(engine.isHealthy(), "Engine must report unhealthy after close()");
    }
}
