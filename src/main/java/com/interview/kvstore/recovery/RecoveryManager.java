package com.interview.kvstore.recovery;

import com.interview.kvstore.exception.CorruptedEntryException;
import com.interview.kvstore.exception.StorageException;
import com.interview.kvstore.exception.TornWriteException;
import com.interview.kvstore.wal.WalEntry;
import com.interview.kvstore.wal.WalReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates startup recovery: reads every entry from the WAL via
 * WalReader, additionally checks that sequence numbers form a strictly
 * increasing, gap-free chain (any break in that chain means the file
 * doesn't reflect a coherent write history, which is corruption), and
 * reacts very differently depending on WHAT kind of problem is found:
 *
 *  - TornWriteException (file ends before an entry finishes): this is
 *    the expected, safe-to-handle case. Crashes mid-append are normal.
 *    Everything read successfully before that point is kept, and the
 *    file is truncated to drop the incomplete tail -- and that
 *    truncation is itself forced to disk (see truncateFile), so a second
 *    crash immediately after recovery can't resurrect the torn bytes.
 *
 *  - CorruptedEntryException (bytes present but content is wrong, or a
 *    sequence-number gap/duplicate) or a sequence violation: this is NOT
 *    a normal crash artifact. It's surfaced by throwing a StorageException
 *    up to the caller instead of silently truncating -- silently
 *    discarding a corrupted-but-structurally-complete record (and
 *    everything after it) would be silent data loss for a problem that
 *    deserves human investigation (disk corruption, a bug elsewhere, or
 *    tampering), not an automatic "shrug and move on."
 */
public final class RecoveryManager {

    private RecoveryManager() {}

    public static RecoveryResult recover(Path walFile) throws StorageException {
        List<WalEntry> entries = new ArrayList<>();

        if (!Files.exists(walFile)) {
            return new RecoveryResult(entries, 0, false, null);
        }

        long validPos = 0;
        boolean tornWriteFound = false;
        String description = null;
        long expectedSeq = -1; // -1 means "no entries seen yet, accept whatever the first one is"

        try (WalReader reader = new WalReader(walFile)) {
            while (reader.hasNext()) {
                WalEntry entry;
                try {
                    entry = reader.next();
                } catch (TornWriteException e) {
                    tornWriteFound = true;
                    description = e.getMessage();
                    break;
                } catch (CorruptedEntryException e) {
                    // Bytes were present and checksummed correctly, but the
                    // content itself failed validation. Do NOT truncate --
                    // surface this instead of silently deleting data.
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
            }
        } catch (IOException e) {
            throw new StorageException("Failed to read WAL file during recovery: " + walFile, e);
        }

        if (tornWriteFound) {
            truncateFile(walFile, validPos);
        }

        return new RecoveryResult(entries, validPos, tornWriteFound, description);
    }

    /**
     * Truncates the file to the last valid entry AND forces that
     * truncation to disk. Without the force() here, the truncation is
     * only a metadata change that the OS might not have flushed yet --
     * a second crash immediately after recovery (before any new append
     * happens to trigger another fsync) could leave the torn bytes still
     * physically present, for the next recovery to trip over again.
     */
    private static void truncateFile(Path walFile, long validLength) throws StorageException {
        try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
            ch.truncate(validLength);
            ch.force(true); // ensure the truncation itself is durable, not just the original writes
        } catch (IOException e) {
            throw new StorageException("Failed to truncate WAL file after detecting a torn write: " + walFile, e);
        }
    }
}
