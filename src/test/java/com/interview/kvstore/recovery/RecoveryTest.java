package com.interview.kvstore.recovery;

import com.interview.kvstore.tools.CrashSimulator;
import com.interview.kvstore.wal.WalWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {

    private Path walFile;

    @BeforeEach
    void setUp() throws Exception {
        walFile = Files.createTempFile("recovery-test", ".wal");
        Files.deleteIfExists(walFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(walFile);
    }

    @Test
    void recoveringFromNonExistentFileReturnsEmptyResult() throws Exception {
        RecoveryResult result = RecoveryManager.recover(walFile);
        assertTrue(result.recoveredEntries.isEmpty());
        assertFalse(result.tornWriteFound);
    }

    @Test
    void recoveringCleanLogReturnsAllEntries() throws Exception {
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("a", "1");
            writer.appendPut("b", "2");
            writer.appendPut("c", "3");
        }

        RecoveryResult result = RecoveryManager.recover(walFile);
        assertEquals(3, result.recoveredEntries.size());
        assertFalse(result.tornWriteFound);
    }

    @Test
    void tornWriteIsDetectedAndGoodEntriesBeforeItAreKept() throws Exception {
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("goodkey1", "committed");
            writer.appendPut("goodkey2", "also-committed");
        }
        long sizeBeforeTornWrite = Files.size(walFile);

        CrashSimulator.appendTornWrite(walFile);
        assertTrue(Files.size(walFile) > sizeBeforeTornWrite, "sanity check: torn write bytes were actually appended");

        RecoveryResult result = RecoveryManager.recover(walFile);

        assertTrue(result.tornWriteFound);
        assertNotNull(result.tornWriteDescription);
        assertEquals(2, result.recoveredEntries.size());
        assertEquals("goodkey1", result.recoveredEntries.get(0).key);
        assertEquals("goodkey2", result.recoveredEntries.get(1).key);
    }

    @Test
    void fileIsTruncatedAfterTornWriteIsDiscarded() throws Exception {
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("goodkey1", "committed");
        }
        long cleanSize = Files.size(walFile);

        CrashSimulator.appendTornWrite(walFile);
        assertTrue(Files.size(walFile) > cleanSize);

        RecoveryManager.recover(walFile);

        // After recovery, the file should be truncated back to exactly the
        // valid portion -- the torn bytes should no longer be present.
        assertEquals(cleanSize, Files.size(walFile));
    }
}
