package com.interview.kvstore.exception;

/**
 * Base exception for anything that goes wrong in the storage engine that
 * the caller should be able to catch as a single category (as opposed to
 * a raw IOException, which conflates "disk is full" with "log is
 * corrupted" with "file doesn't exist" -- all very different situations
 * that deserve different handling).
 */
public class StorageException extends Exception {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
