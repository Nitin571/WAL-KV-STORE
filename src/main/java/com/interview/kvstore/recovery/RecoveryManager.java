package com.interview.kvstore.recovery;

import com.interview.kvstore.exception.CorruptedEntryException;
import com.interview.kvstore.exception.StorageException;
import com.interview.kvstore.exception.TornWriteException;
import com.interview.kvstore.wal.WalEntry;
import com.interview.kvstore.wal.WalIntegrity;
import com.interview.kvstore.wal.WalReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates startup recovery: reads every entry from the WAL via
 * WalReader, verifies HMAC cryptographic chains (in format v2), checks
 * that sequence numbers form a strictly increasing, gap-free chain, and
 * reacts appropriately:
 *
 *  - TornWriteException (file ends before an entry finishes): normal crash artifact.
 *    Everything read successfully before that point is kept, and the file is
 *    truncated to drop the incomplete tail -- forced to disk with force(true).
 *
 *  - CorruptedEntryException (bad CRC32, HMAC mismatch, impossible length,
 *    sequence gap/reordering): NOT a crash artifact. Throws StorageException
 *    without truncating to preserve forensic evidence and prevent silent data loss.
 */
public final class RecoveryManager {

    private RecoveryManager() {}

    public static RecoveryResult recover(Path walFile) throws StorageException {
        return recover(walFile, null);
    }

    public static RecoveryResult recover(Path walFile, byte[] integritySecret) throws StorageException {
        List<WalEntry> entries = new ArrayList<>();

        if (!Files.exists(walFile)) {
            return new RecoveryResult(entries, 0, false, null,
                    integritySecret != null ? WalIntegrity.FORMAT_VERSION_2 : 1,
                    integritySecret != null ? WalIntegrity.GENESIS_TAG : null);
        }

        long fileSize;
        try {
            fileSize = Files.size(walFile);
        } catch (IOException e) {
            throw new StorageException("Failed to check WAL file size: " + walFile, e);
        }

        if (fileSize == 0) {
            return new RecoveryResult(entries, 0, false, null,
                    integritySecret != null ? WalIntegrity.FORMAT_VERSION_2 : 1,
                    integritySecret != null ? WalIntegrity.GENESIS_TAG : null);
        }

        // Handle torn header during initial file creation in integrity mode
        if (integritySecret != null && fileSize < WalIntegrity.HEADER_SIZE) {
            try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.READ)) {
                ByteBuffer prefixBuf = ByteBuffer.allocate((int) fileSize);
                ch.read(prefixBuf);
                prefixBuf.flip();
                byte[] prefix = prefixBuf.array();
                boolean matchesPrefix = true;
                for (int i = 0; i < prefix.length; i++) {
                    if (prefix[i] != WalIntegrity.MAGIC_HEADER[i]) {
                        matchesPrefix = false;
                        break;
                    }
                }
                if (matchesPrefix) {
                    truncateFile(walFile, 0);
                    return new RecoveryResult(entries, 0, true, "Incomplete integrity header",
                            WalIntegrity.FORMAT_VERSION_2, WalIntegrity.GENESIS_TAG);
                } else {
                    throw new StorageException("Cannot enable integrity mode on an existing legacy unversioned WAL file: " +
                            walFile + ". Historical entries were not cryptographically signed. " +
                            "Please start with a fresh WAL or perform an explicit migration.");
                }
            } catch (IOException e) {
                throw new StorageException("Failed to inspect WAL file header: " + walFile, e);
            }
        }

        long validPos = 0;
        boolean tornWriteFound = false;
        String description = null;
        long expectedSeq = -1;
        int detectedVersion = 1;
        byte[] lastTag = (integritySecret != null) ? WalIntegrity.GENESIS_TAG.clone() : null;

        try (WalReader reader = new WalReader(walFile, integritySecret)) {
            detectedVersion = reader.getFormatVersion();

            // Enforce legacy compatibility and mode consistency
            if (integritySecret != null && detectedVersion == 1) {
                throw new StorageException("Cannot enable integrity mode on an existing legacy unversioned WAL file: " +
                        walFile + ". Historical entries were not cryptographically signed. " +
                        "Please start with a fresh WAL or perform an explicit migration.");
            }
            if (integritySecret == null && detectedVersion == 2) {
                throw new StorageException("Cannot read integrity-enabled WAL file (version 2) when integrity mode is disabled. " +
                        "Please configure wal.integrity.enabled=true and provide wal.integrity.secret.");
            }

            validPos = reader.getHeaderSize();

            while (reader.hasNext()) {
                WalEntry entry;
                try {
                    entry = reader.next();
                } catch (TornWriteException e) {
                    tornWriteFound = true;
                    description = e.getMessage();
                    break;
                } catch (CorruptedEntryException e) {
                    throw new StorageException(
                            "WAL corruption detected during recovery -- this is NOT a torn write from a " +
                            "normal crash and will NOT be auto-truncated, to avoid silently discarding data. " +
                            "Manual investigation is required. " + e.getMessage() + ". " +
                            entries.size() + " entries were read successfully before this point.", e);
                }

                if (expectedSeq != -1 && entry.sequenceNumber != expectedSeq) {
                    throw new StorageException(
                            "WAL corruption detected during recovery -- expected sequence number " + expectedSeq +
                            " but found " + entry.sequenceNumber + " at byte offset " + validPos +
                            ". This indicates a gap, duplicate, or reordering that a normal crash would not " +
                            "produce, so this is NOT auto-truncated. " +
                            entries.size() + " entries were read successfully before this point.");
                }

                entries.add(entry);
                validPos = reader.position();
                expectedSeq = entry.sequenceNumber + 1;
                if (entry.hmacTag != null) {
                    lastTag = entry.hmacTag.clone();
                }
            }
        } catch (IOException e) {
            throw new StorageException("Failed to read WAL file during recovery: " + walFile, e);
        }

        if (tornWriteFound) {
            truncateFile(walFile, validPos);
        }

        return new RecoveryResult(entries, validPos, tornWriteFound, description, detectedVersion, lastTag);
    }

    /**
     * Truncates the file to the last valid entry AND forces that
     * truncation to disk.
     */
    private static void truncateFile(Path walFile, long validLength) throws StorageException {
        try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
            ch.truncate(validLength);
            ch.force(true);
        } catch (IOException e) {
            throw new StorageException("Failed to truncate WAL file after detecting a torn write: " + walFile, e);
        }
    }
}
