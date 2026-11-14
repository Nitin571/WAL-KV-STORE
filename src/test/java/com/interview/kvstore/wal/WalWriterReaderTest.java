package com.interview.kvstore.wal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalWriterReaderTest {

    private Path walFile;

    @BeforeEach
    void setUp() throws Exception {
        walFile = Files.createTempFile("wal-test", ".wal");
        Files.deleteIfExists(walFile); // WalWriter creates it fresh
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(walFile);
    }

    @Test
    void writtenEntriesCanBeReadBackInOrder() throws Exception {
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("key1", "value1");
            writer.appendPut("key2", "value2");
            writer.appendDelete("key1");
        }

        List<WalEntry> read = new java.util.ArrayList<>();
        try (WalReader reader = new WalReader(walFile)) {
            while (reader.hasNext()) {
                read.add(reader.next());
            }
        }

        assertEquals(3, read.size());
        assertEquals(new WalEntry(0, WalEntry.OpType.PUT, "key1", "value1"), read.get(0));
        assertEquals(new WalEntry(1, WalEntry.OpType.PUT, "key2", "value2"), read.get(1));
        assertEquals(new WalEntry(2, WalEntry.OpType.DELETE, "key1", null), read.get(2));
    }

    @Test
    void sequenceNumbersIncrementAcrossWrites() throws Exception {
        try (WalWriter writer = new WalWriter(walFile, 5)) {
            WalEntry e1 = writer.appendPut("a", "1");
            WalEntry e2 = writer.appendPut("b", "2");
            assertEquals(5, e1.sequenceNumber);
            assertEquals(6, e2.sequenceNumber);
        }
    }

    @Test
    void emptyFileHasNoEntries() throws Exception {
        // WalReader on a file that doesn't exist yet should behave sanely
        // via RecoveryManager (tested separately) -- here we just check
        // an explicitly-created-but-empty file has no entries.
        Files.createFile(walFile);
        try (WalReader reader = new WalReader(walFile)) {
            assertFalse(reader.hasNext());
        }
    }

    @Test
    void handlesUnicodeKeysAndValuesCorrectly() throws Exception {
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("emoji-key-🔑", "value-✅-日本語");
        }
        try (WalReader reader = new WalReader(walFile)) {
            WalEntry entry = reader.next();
            assertEquals("emoji-key-🔑", entry.key);
            assertEquals("value-✅-日本語", entry.value);
        }
    }

    @Test
    void emptyStringValueIsPreservedNotConflatedWithNull() throws Exception {
        // A PUT with an empty string value ("") is a valid operation that is
        // semantically different from a DELETE (null value). The serialized
        // form has valLen == 0 for both, so the reader must use OpType to
        // distinguish them. Before the fix, both deserialized to value=null.
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            writer.appendPut("key1", "");         // empty-string value
            writer.appendDelete("key2");           // null value
        }

        try (WalReader reader = new WalReader(walFile)) {
            WalEntry putEntry = reader.next();
            assertEquals(WalEntry.OpType.PUT, putEntry.opType);
            assertEquals("", putEntry.value, "PUT with empty string must deserialize to \"\", not null");

            WalEntry deleteEntry = reader.next();
            assertEquals(WalEntry.OpType.DELETE, deleteEntry.opType);
            assertNull(deleteEntry.value, "DELETE must deserialize to null value");
        }
    }

    @Test
    void rejectsEntryExceedingMaxEntrySizeBytesBeforeAllocation() throws Exception {
        try (WalWriter writer = new WalWriter(walFile, 0)) {
            String hugeKey = "k".repeat(17 * 1024 * 1024);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> writer.appendPut(hugeKey, "val"));
            assertTrue(ex.getMessage().contains("MAX_ENTRY_SIZE_BYTES"));
            assertEquals(0, writer.currentSequence(), "Sequence number must not advance on rejected write");
        }
    }
}
