package com.interview.kvstore.recovery;

import com.interview.kvstore.wal.WalEntry;

import java.util.List;

/**
 * Summary of what happened during log replay at startup. Kept as its own
 * small data class (rather than just returning a List<WalEntry>) so the
 * caller can distinguish "everything was clean" from "we found and
 * discarded a torn write" -- which matters for logging/alerting in a real
 * system, even though both cases leave the store correctly recovered.
 */
public final class RecoveryResult {
    public final List<WalEntry> recoveredEntries;
    public final long validByteLength; // file gets truncated to this length if a torn write was found
    public final boolean tornWriteFound;
    public final String tornWriteDescription; // null if tornWriteFound is false

    public RecoveryResult(List<WalEntry> recoveredEntries, long validByteLength,
                           boolean tornWriteFound, String tornWriteDescription) {
        this.recoveredEntries = recoveredEntries;
        this.validByteLength = validByteLength;
        this.tornWriteFound = tornWriteFound;
        this.tornWriteDescription = tornWriteDescription;
    }
}
