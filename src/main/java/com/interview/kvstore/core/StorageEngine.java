package com.interview.kvstore.core;

import com.interview.kvstore.exception.StorageException;
import com.interview.kvstore.recovery.RecoveryManager;
import com.interview.kvstore.recovery.RecoveryResult;
import com.interview.kvstore.wal.WalEntry;
import com.interview.kvstore.wal.WalWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The durable key-value store. This is the piece application code
 * actually talks to; it hides the WAL/recovery machinery behind a plain
 * put/get/delete API.
 *
 * The ordering rule that makes this durable:
 *   1. Append the operation to the WAL and fsync it (WalWriter does this).
 *   2. ONLY THEN apply the change to the in-memory map.
 *   3. ONLY THEN return success to the caller.
 *
 * If steps 1 and 2 were flipped -- memory updated first, logged after -- a
 * crash between them would mean the caller was told the write succeeded
 * but the log never recorded it, so recovery would silently lose it. The
 * WAL write, not the memory update, is the actual point of commitment.
 *
 * CONCURRENCY NOTE: writeLock guards the append-then-apply sequence as a
 * single atomic unit. This matters even though WalWriter itself is
 * internally synchronized: without this outer lock, two threads could
 * append to the WAL in the correct order (say, thread A gets sequence 5,
 * thread B gets sequence 6) but then race to apply their changes to the
 * in-memory map in the OPPOSITE order -- leaving memory holding thread
 * A's (older) value even though the durable log, and any future
 * recovery replaying it, would end up with thread B's (newer, correct)
 * value. That would make live reads briefly return the wrong answer
 * relative to what's already durably committed. Wrapping both steps in
 * one lock makes each put/delete atomic end-to-end, so memory state is
 * always consistent with WAL order. Reads (get/size/snapshot) are left
 * lock-free via ConcurrentHashMap, since they don't need to participate
 * in that ordering guarantee -- a reader might see a slightly-stale value
 * mid-write, which is normal, expected read-during-write behavior, not a
 * correctness bug.
 */
public final class StorageEngine implements AutoCloseable {

    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final WalWriter writer;
    private final RecoveryResult lastRecovery;
    private final ReentrantLock writeLock = new ReentrantLock();

    public StorageEngine(Path walFile) throws StorageException {
        try {
            if (walFile.getParent() != null) {
                java.nio.file.Files.createDirectories(walFile.getParent());
            }
        } catch (IOException e) {
            throw new StorageException("Failed to create parent directory for WAL file: " + walFile, e);
        }

        this.lastRecovery = RecoveryManager.recover(walFile);

        long lastSeq = -1;
        for (WalEntry entry : lastRecovery.recoveredEntries) {
            applyToMemory(entry);
            lastSeq = Math.max(lastSeq, entry.sequenceNumber);
        }

        try {
            this.writer = new WalWriter(walFile, lastSeq + 1);
        } catch (IOException e) {
            throw new StorageException("Failed to open WAL for writing: " + walFile, e);
        }
    }

    /** Exposes what happened during startup recovery, e.g. for logging or a health-check endpoint. */
    public RecoveryResult getLastRecoveryResult() {
        return lastRecovery;
    }

    private void applyToMemory(WalEntry entry) {
        switch (entry.opType) {
            case PUT -> data.put(entry.key, entry.value);
            case DELETE -> data.remove(entry.key);
        }
    }

    public void put(String key, String value) throws StorageException {
        writeLock.lock();
        try {
            WalEntry entry = writer.appendPut(key, value); // durable the moment this returns
            applyToMemory(entry); // still inside the lock -- memory order now matches WAL order
        } catch (IOException e) {
            throw new StorageException("Failed to durably write key: " + key, e);
        } finally {
            writeLock.unlock();
        }
    }

    public void delete(String key) throws StorageException {
        writeLock.lock();
        try {
            WalEntry entry = writer.appendDelete(key);
            applyToMemory(entry);
        } catch (IOException e) {
            throw new StorageException("Failed to durably delete key: " + key, e);
        } finally {
            writeLock.unlock();
        }
    }

    public String get(String key) {
        return data.get(key);
    }

    public int size() {
        return data.size();
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(data);
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
