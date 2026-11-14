package com.interview.kvstore.wal;

/**
 * Represents a single operation within an atomic batch write.
 */
public record BatchOp(WalEntry.OpType opType, String key, String value) {

    public static BatchOp put(String key, String value) {
        if (key == null) throw new IllegalArgumentException("Key cannot be null");
        return new BatchOp(WalEntry.OpType.PUT, key, value);
    }

    public static BatchOp delete(String key) {
        if (key == null) throw new IllegalArgumentException("Key cannot be null");
        return new BatchOp(WalEntry.OpType.DELETE, key, null);
    }
}
