package com.interview.kvstore.recovery;

import com.interview.kvstore.exception.StorageException;
import com.interview.kvstore.wal.WalWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests specifically targeting the hardening properties added on top of
 * the basic torn-write/recovery behavior already covered in RecoveryTest:
 *
 *  1. A corrupted length field that would otherwise cause a huge/unsafe
 *     allocation is rejected before any allocation is attempted.
 *  2. Structural fields (op code, key/value lengths) are validated, not
 *     blindly trusted.
 *  3. A corrupted-but-structurally-complete record is surfaced (recovery
 *     fails loudly) rather than silently truncated away like a torn
 *     write -- because truncating would silently delete data that a
 *     normal crash would never have destroyed.
 *  4. A broken sequence-number chain (gap, duplicate, reorder) is
 *     detected as corruption, not silently accepted.
 *  5. Truncation after a genuine torn write is itself durable (implicitly
 *     covered by RecoveryTest.fileIsTruncatedAfterTornWriteIsDiscarded
 *     succeeding at all -- if force() weren't called, this is still
 *     correct within a single process run, but the force() call itself
 *     is what protects against a second crash immediately after
 *     recovery, which isn't directly observable in a unit test).
 */
class RecoveryHardeningTest {

    private Path walFile;

    @BeforeEach
    void setUp() throws Exception {
        walFile = Files.createTempFile("hardening-test", ".wal");
        Files.deleteIfExists(walFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(walFile);
    }

    @Test
    void absurdlyLargeDeclaredLengthIsRejectedWithoutAttemptingAllocation() throws Exception {
        // Write one normal, valid entry first.
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("goodkey", "committed");
        }

        // Now hand-craft a corrupted length prefix that claims a body
        // size close to Integer.MAX_VALUE -- if the reader tried to
        // allocate a buffer for this, it would throw OutOfMemoryError or
        // hang, not fail cleanly. We're proving it fails cleanly instead.
        try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer huge = ByteBuffer.allocate(8);
            huge.putInt(Integer.MAX_VALUE - 100); // declares ~2GB, nowhere near actually present
            huge.putInt(0); // a bit of filler so the file has *some* bytes after the length prefix
            huge.flip();
            ch.write(huge);
            ch.force(true);
        }

        // This must complete quickly and throw StorageException, not
        // attempt a multi-gigabyte allocation.
        StorageException ex = assertThrows(StorageException.class, () -> RecoveryManager.recover(walFile));
        assertTrue(ex.getMessage().toLowerCase().contains("corrupt") || ex.getMessage().toLowerCase().contains("sanity"),
                "expected message to indicate corruption/sanity-limit rejection, was: " + ex.getMessage());
    }

    @Test
    void corruptionInAnOtherwiseCompleteRecordIsNotSilentlyTruncated() throws Exception {
        long sizeBeforeCorruption;
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("goodkey1", "committed");
            writer.appendPut("goodkey2", "also-committed");
            writer.appendPut("goodkey3", "written-after-what-will-be-corrupted");
        }

        // Flip a single byte inside the SECOND entry's body -- this makes
        // the record structurally complete (right length) but its
        // checksum will no longer match. Critically, entry 3 (goodkey3)
        // comes AFTER this corruption in the file.
        byte[] bytes = Files.readAllBytes(walFile);
        // Find a byte roughly in the middle of the file to flip -- avoid
        // the very first 4 bytes (length prefix of entry 1) so we corrupt
        // content, not structure.
        int flipIndex = bytes.length / 2;
        bytes[flipIndex] = (byte) (bytes[flipIndex] ^ 0xFF);
        Files.write(walFile, bytes);

        long fileSizeAfterCorruption = Files.size(walFile);

        // Recovery must fail loudly (StorageException), not silently
        // truncate the file and lose goodkey3's data.
        StorageException ex = assertThrows(StorageException.class, () -> RecoveryManager.recover(walFile));
        assertTrue(ex.getMessage().toLowerCase().contains("corrupt"));

        // The critical assertion: the file must NOT have been truncated.
        // A torn write truncates; corruption must not, because goodkey3's
        // bytes (which come after the corruption point) might still be
        // perfectly intact and worth a human's manual recovery attempt.
        assertEquals(fileSizeAfterCorruption, Files.size(walFile),
                "file must be left untouched when corruption (not a torn write) is detected");
    }

    @Test
    void brokenSequenceChainIsDetectedAsCorruption() throws Exception {
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("a", "1"); // seq 0
            writer.appendPut("b", "2"); // seq 1
        }

        // Manually append a THIRD entry with a sequence number that skips
        // ahead (seq 5 instead of the expected 2) -- simulates a corrupted
        // or tampered sequence field rather than a normal crash artifact.
        try (WalWriter writer = new WalWriter(walFile, 5)) {
            writer.appendPut("c", "3"); // seq 5, but 2 was expected
        }

        StorageException ex = assertThrows(StorageException.class, () -> RecoveryManager.recover(walFile));
        assertTrue(ex.getMessage().toLowerCase().contains("sequence"),
                "expected message to mention the sequence break, was: " + ex.getMessage());
    }

    @Test
    void tornWriteStillTruncatesNormallyDespiteStricterValidation() throws Exception {
        // Sanity check that the new validation didn't break the original,
        // expected-and-safe torn-write handling.
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("goodkey1", "committed");
        }
        long cleanSize = Files.size(walFile);

        com.interview.kvstore.tools.CrashSimulator.appendTornWrite(walFile);
        assertTrue(Files.size(walFile) > cleanSize);

        RecoveryResult result = RecoveryManager.recover(walFile); // must NOT throw
        assertTrue(result.tornWriteFound);
        assertEquals(1, result.recoveredEntries.size());
        assertEquals(cleanSize, Files.size(walFile), "torn write case should still truncate as before");
    }
}
