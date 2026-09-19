package com.interview.kvstore.exception;

/**
 * Thrown when a log entry is incomplete: either the length prefix claims
 * more bytes than actually exist in the file, or the file ends before the
 * entry's declared length is satisfied. This is the classic signature of
 * a process crashing mid-write -- the OS had written the length prefix (or
 * part of the body) but never got to finish, and never got to fsync the
 * rest before dying.
 */
public class TornWriteException extends StorageException {
    public TornWriteException(long byteOffset) {
        super("Torn write detected at byte offset " + byteOffset +
                " -- entry is incomplete, likely due to a crash mid-write.");
    }
}
