package com.interview.kvstore.wal;

import com.interview.kvstore.core.StorageEngine;
import com.interview.kvstore.exception.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class WalIntegrityTest {

    private static final String TEST_SECRET = "super-confidential-secret-key-for-testing-123";
    private Path tempDir;
    private Path walFile;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("wal-integrity-test");
        walFile = tempDir.resolve("integrity.wal");
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(walFile);
        Path lockFile = tempDir.resolve("integrity.wal.lock");
        Files.deleteIfExists(lockFile);
        Files.deleteIfExists(tempDir);
    }

    @Test
    void testIntegrityWriteCloseReopenAndRecover() throws Exception {
        // 1. Create store in integrity mode and append records
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            for (int i = 0; i < 10; i++) {
                engine.put("key:" + i, "val:" + i);
            }
            StorageEngine.IntegrityStatus status = engine.getIntegrityStatus();
            assertEquals("VALID", status.status());
            assertTrue(status.integrityEnabled());
            assertEquals(2, status.walFormatVersion());
            assertEquals(10, status.verifiedEntries());
            assertEquals(9, status.lastSequenceNumber());
            assertNotNull(status.chainRootPrefix());
            assertEquals(12, status.chainRootPrefix().length());
        }

        // 2. Reopen store with the same secret and verify clean recovery
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            for (int i = 0; i < 10; i++) {
                assertEquals("val:" + i, engine.get("key:" + i));
            }
            StorageEngine.IntegrityStatus status = engine.getIntegrityStatus();
            assertEquals("VALID", status.status());
            assertEquals(10, status.verifiedEntries());

            // Write additional record in reopened store
            engine.put("key:10", "val:10");
            assertEquals(11, engine.getIntegrityStatus().verifiedEntries());
        }
    }

    @Test
    void testModifyingCompleteRecordByteCausesRecoveryToFailWithoutTruncation() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            engine.put("alpha", "100");
            engine.put("beta", "200");
            engine.put("gamma", "300");
        }

        long originalSize = Files.size(walFile);
        assertTrue(originalSize > WalIntegrity.HEADER_SIZE);

        // Tamper with one byte inside the payload on disk
        try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer byteBuf = ByteBuffer.allocate(1);
            long tamperPos = WalIntegrity.HEADER_SIZE + 20; // Position inside the first record payload
            ch.read(byteBuf, tamperPos);
            byte original = byteBuf.get(0);
            byte tampered = (byte) (original ^ 0x01); // flip 1 bit
            byteBuf.clear();
            byteBuf.put(tampered);
            byteBuf.flip();
            ch.write(byteBuf, tamperPos);
            ch.force(true);
        }

        // Recovery must detect the tamper attempt and refuse to start
        StorageException ex = assertThrows(StorageException.class, () ->
                new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET));

        assertTrue(ex.getMessage().toLowerCase().contains("corruption") ||
                ex.getMessage().toLowerCase().contains("hmac") ||
                ex.getMessage().toLowerCase().contains("checksum"),
                "Expected corruption/HMAC error message but was: " + ex.getMessage());

        // Crucial security requirement: file must NOT be truncated when tampering is detected!
        assertEquals(originalSize, Files.size(walFile), "Tampered file must not be truncated");
    }

    @Test
    void testRemovingRecordFromMiddleCausesRecoveryToFail() throws Exception {
        // Write 3 records
        WalEntry e0, e1, e2;
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            engine.put("record0", "val0");
            engine.put("record1", "val1");
            engine.put("record2", "val2");
        }

        // Read all entries with their raw byte segments
        byte[] allBytes = Files.readAllBytes(walFile);

        // Find individual entry boundaries
        // Header = 16 bytes
        int pos = WalIntegrity.HEADER_SIZE;
        int len0 = ByteBuffer.wrap(allBytes, pos, 4).getInt() + 4;
        int pos1 = pos + len0;
        int len1 = ByteBuffer.wrap(allBytes, pos1, 4).getInt() + 4;
        int pos2 = pos1 + len1;
        int len2 = ByteBuffer.wrap(allBytes, pos2, 4).getInt() + 4;

        // Splice file: Header + Record 0 + Record 2 (Record 1 removed from middle)
        ByteBuffer spliced = ByteBuffer.allocate(WalIntegrity.HEADER_SIZE + len0 + len2);
        spliced.put(allBytes, 0, WalIntegrity.HEADER_SIZE); // header
        spliced.put(allBytes, pos, len0);                   // record 0
        spliced.put(allBytes, pos2, len2);                  // record 2 (skipped record 1)
        spliced.flip();

        Files.write(walFile, spliced.array(), StandardOpenOption.TRUNCATE_EXISTING);

        // Startup recovery must fail because record 2's previous tag was record 1, but now received record 0
        StorageException ex = assertThrows(StorageException.class, () ->
                new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET));

        assertTrue(ex.getMessage().contains("corruption") || ex.getMessage().contains("sequence"),
                "Expected sequence or corruption failure on deleted middle record: " + ex.getMessage());
    }

    @Test
    void testReorderingRecordsCausesRecoveryToFail() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            engine.put("firstKey", "val1");
            engine.put("secondKey", "val2");
        }

        byte[] allBytes = Files.readAllBytes(walFile);
        int pos0 = WalIntegrity.HEADER_SIZE;
        int len0 = ByteBuffer.wrap(allBytes, pos0, 4).getInt() + 4;
        int pos1 = pos0 + len0;
        int len1 = ByteBuffer.wrap(allBytes, pos1, 4).getInt() + 4;

        // Swap record 0 and record 1
        ByteBuffer swapped = ByteBuffer.allocate(WalIntegrity.HEADER_SIZE + len0 + len1);
        swapped.put(allBytes, 0, WalIntegrity.HEADER_SIZE);
        swapped.put(allBytes, pos1, len1); // record 1 first
        swapped.put(allBytes, pos0, len0); // record 0 second
        swapped.flip();

        Files.write(walFile, swapped.array(), StandardOpenOption.TRUNCATE_EXISTING);

        // Recovery must fail
        assertThrows(StorageException.class, () ->
                new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET));
    }

    @Test
    void testPartialFinalRecordIsDetectedAsTornWriteAndSafelyTruncated() throws Exception {
        long validSize;
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            engine.put("k1", "v1");
            engine.put("k2", "v2");
        }
        validSize = Files.size(walFile);

        // Append 15 bytes of incomplete record (partial declared length and partial body)
        try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer garbage = ByteBuffer.allocate(15);
            garbage.putInt(80); // claims 80 bytes follow
            garbage.put(new byte[11]);
            garbage.flip();
            ch.write(garbage);
            ch.force(true);
        }

        assertTrue(Files.size(walFile) > validSize);

        // Reopen store: torn write must be detected and truncated back to validSize
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            assertEquals("v1", engine.get("k1"));
            assertEquals("v2", engine.get("k2"));
            assertTrue(engine.getLastRecoveryResult().tornWriteFound);
            assertEquals(validSize, Files.size(walFile));
            assertEquals(2, engine.getIntegrityStatus().verifiedEntries());
        }
    }

    @Test
    void testCompactionProducesValidIntegrityWalAndCanRestart() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            for (int i = 0; i < 50; i++) {
                engine.put("user:account", "version-" + i);
            }
            long sizeBefore = Files.size(walFile);
            var compResult = engine.compact();
            long sizeAfter = Files.size(walFile);

            assertTrue(sizeAfter < sizeBefore);
            assertEquals(1, compResult.keysRetained());
            assertEquals("VALID", engine.getIntegrityStatus().status());

            // Write a new key after compaction
            engine.put("user:new", "active");
        }

        // Reopen from compacted file and verify clean startup
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            assertEquals("version-49", engine.get("user:account"));
            assertEquals("active", engine.get("user:new"));
            assertEquals(2, engine.size());
            assertEquals("VALID", engine.getIntegrityStatus().status());
            assertEquals(2, engine.getIntegrityStatus().verifiedEntries());
        }
    }

    @Test
    void testMissingIntegritySecretWithIntegrityEnabledFailsSafely() {
        StorageException ex1 = assertThrows(StorageException.class, () ->
                new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, null));
        assertTrue(ex1.getMessage().contains("missing or blank"));

        StorageException ex2 = assertThrows(StorageException.class, () ->
                new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, "   "));
        assertTrue(ex2.getMessage().contains("missing or blank"));
    }

    @Test
    void testLegacyWalRejectionWhenIntegrityEnabled() throws Exception {
        // Create legacy (format v1) WAL
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, false, null)) {
            engine.put("legacyKey", "legacyVal");
        }

        // Opening the legacy unversioned WAL in integrity mode must fail with a clear migration message
        StorageException ex = assertThrows(StorageException.class, () ->
                new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET));

        assertTrue(ex.getMessage().contains("Cannot enable integrity mode on an existing legacy unversioned WAL file"),
                "Expected legacy rejection message, got: " + ex.getMessage());
    }

    @Test
    void testSecretNotPresentInLogsExceptionsOrStats() throws Exception {
        try (StorageEngine engine = new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, TEST_SECRET)) {
            engine.put("k", "v");
            String statsStr = engine.getStats().toString();
            String integrityStr = engine.getIntegrityStatus().toString();

            assertFalse(statsStr.contains(TEST_SECRET), "Secret must not be in EngineStats");
            assertFalse(integrityStr.contains(TEST_SECRET), "Secret must not be in IntegrityStatus");
        }

        try {
            new StorageEngine(walFile, StorageEngine.DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES, true, "wrong-secret-key-xyz");
            fail("Expected recovery failure with wrong secret");
        } catch (StorageException e) {
            assertFalse(e.getMessage().contains("wrong-secret-key-xyz"),
                    "Secret must not be exposed in exception messages");
        }
    }
}
