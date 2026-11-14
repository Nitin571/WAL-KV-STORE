package com.interview.kvstore.recovery;

/**
 * Summary metrics of a WAL compaction execution.
 */
public record CompactionResult(
        long bytesBefore,
        long bytesAfter,
        int keysRetained
) {
    public long bytesReclaimed() {
        return Math.max(0, bytesBefore - bytesAfter);
    }
}
