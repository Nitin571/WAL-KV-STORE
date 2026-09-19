package com.interview.kvstore.exception;

/**
 * Thrown when a log entry's bytes are structurally present (right length,
 * or at least enough bytes exist) but something about its content is
 * invalid: a checksum mismatch, an impossible field length, an unknown
 * operation code, a sequence number that breaks the expected ordering, or
 * leftover/missing bytes relative to what the entry's own length fields
 * declared.
 *
 * This is deliberately distinct from TornWriteException. A torn write is
 * an EXPECTED artifact of a crash mid-append -- the file simply ends
 * before the entry finishes, which is normal and safe to truncate away.
 * A CorruptedEntryException means something is wrong with a record that
 * otherwise looks complete -- which is NOT a normal crash artifact and
 * should never be silently discarded, since that would be silently
 * deleting data (and potentially data written after the corruption point
 * too, since a corrupted length field can misalign every subsequent read).
 * Callers should surface this to a human rather than auto-truncating.
 */
public class CorruptedEntryException extends StorageException {
    public CorruptedEntryException(long byteOffset) {
        super("Checksum mismatch for log entry at byte offset " + byteOffset +
                " -- entry content does not match its stored checksum.");
    }

    public CorruptedEntryException(long byteOffset, String reason) {
        super("Corrupted log entry at byte offset " + byteOffset + ": " + reason);
    }
}
