package com.interview.kvstore.core;

import com.interview.kvstore.exception.StorageException;
import com.interview.kvstore.recovery.CompactionResult;
import com.interview.kvstore.recovery.RecoveryManager;
import com.interview.kvstore.recovery.RecoveryResult;
import com.interview.kvstore.wal.BatchOp;
import com.interview.kvstore.wal.WalEntry;
import com.interview.kvstore.wal.WalWriter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The durable, production-ready key-value store. This is the piece application
 * code actually talks to; it hides the WAL/recovery/compaction machinery behind a
 * rich CRUD, range-query, and batch-write API.
 *
 * Key capabilities:
 *   1. Write-Ahead Logging with hardware fsync guarantees durability.
 *   2. ConcurrentSkipListMap powers lock-free reads and O(log N) prefix/range scans.
 *   3. Group Commit / Batching: write multiple keys in a single fsync.
 *   4. Zero-loss Atomic Compaction: reclaims disk space from deleted/overwritten
 *      keys without taking down the service.
 */
public final class StorageEngine implements AutoCloseable {

    public static final long DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES = 50 * 1024 * 1024L; // 50 MB

    public record EngineStats(
            int keyCount,
            long walFileSizeBytes,
            long lastCompactedAt,
            long recoveredEntriesCount,
            boolean tornWriteDetected,
            long currentSequenceNumber
    ) {}

    private final ConcurrentSkipListMap<String, String> data = new ConcurrentSkipListMap<>();
    private final Path walFile;
    private final long autoCompactThresholdBytes;
    private final RecoveryResult lastRecovery;
    private final ReentrantLock writeLock = new ReentrantLock();

    private WalWriter writer;
    private volatile long lastCompactedAt = -1;

    public StorageEngine(Path walFile) throws StorageException {
        this(walFile, DEFAULT_AUTO_COMPACT_THRESHOLD_BYTES);
    }

    public StorageEngine(Path walFile, long autoCompactThresholdBytes) throws StorageException {
        this.walFile = walFile;
        this.autoCompactThresholdBytes = autoCompactThresholdBytes;

        try {
            if (walFile.getParent() != null) {
                Files.createDirectories(walFile.getParent());
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
            checkAutoCompact();
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
            checkAutoCompact();
        } catch (IOException e) {
            throw new StorageException("Failed to durably delete key: " + key, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Durably executes an atomic batch of PUTs and DELETEs with a single fsync.
     */
    public void batchWrite(List<BatchOp> ops) throws StorageException {
        if (ops == null || ops.isEmpty()) return;
        writeLock.lock();
        try {
            List<WalEntry> entries = writer.appendBatch(ops);
            for (WalEntry entry : entries) {
                applyToMemory(entry);
            }
            checkAutoCompact();
        } catch (IOException e) {
            throw new StorageException("Failed to durably write batch of " + ops.size() + " operations", e);
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
        return Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * Returns matching key-value pairs whose keys start with the given prefix,
     * ordered lexicographically.
     *
     * @param prefix key prefix to match (empty or null matches from start)
     * @param limit maximum items to return (0 or negative for unlimited)
     */
    public Map<String, String> scan(String prefix, int limit) {
        if (prefix == null || prefix.isEmpty()) {
            return range(null, null, limit);
        }
        Map<String, String> results = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : data.tailMap(prefix).entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                break;
            }
            results.put(entry.getKey(), entry.getValue());
            if (limit > 0 && results.size() >= limit) {
                break;
            }
        }
        return Collections.unmodifiableMap(results);
    }

    /**
     * Returns matching key-value pairs between fromKey and toKey (inclusive),
     * ordered lexicographically.
     *
     * @param fromKey start key inclusive (null to start from beginning)
     * @param toKey end key inclusive (null to read until end)
     * @param limit maximum items to return (0 or negative for unlimited)
     */
    public Map<String, String> range(String fromKey, String toKey, int limit) {
        ConcurrentNavigableMap<String, String> sub;
        if (fromKey != null && toKey != null) {
            sub = data.subMap(fromKey, true, toKey, true);
        } else if (fromKey != null) {
            sub = data.tailMap(fromKey, true);
        } else if (toKey != null) {
            sub = data.headMap(toKey, true);
        } else {
            sub = data;
        }

        Map<String, String> results = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sub.entrySet()) {
            results.put(entry.getKey(), entry.getValue());
            if (limit > 0 && results.size() >= limit) {
                break;
            }
        }
        return Collections.unmodifiableMap(results);
    }

    /**
     * Compacts the WAL file by writing a clean snapshot of only the active, live keys
     * and atomically replacing the existing WAL file. This drops obsolete historical
     * updates and deleted tombstones, reclaiming disk space and making subsequent
     * startups instantaneous.
     */
    public CompactionResult compact() throws StorageException {
        writeLock.lock();
        try {
            long sizeBefore = Files.exists(walFile) ? Files.size(walFile) : 0;
            Path parent = walFile.getParent();
            Path tmpFile = parent != null
                    ? parent.resolve(walFile.getFileName().toString() + ".compact.tmp")
                    : Path.of(walFile.getFileName().toString() + ".compact.tmp");

            Files.deleteIfExists(tmpFile);

            // Step 1: Write only currently live entries into the temporary file
            long seq = 0;
            try (WalWriter tmpWriter = new WalWriter(tmpFile, 0)) {
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    tmpWriter.appendPut(entry.getKey(), entry.getValue());
                    seq++;
                }
            }

            // Step 2: Safely close active writer
            writer.close();

            // Step 3: Atomic file replacement (or fallback to standard replace)
            try {
                Files.move(tmpFile, walFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFile, walFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // Step 4: Reopen writer on the clean compacted file
            this.writer = new WalWriter(walFile, seq);
            this.lastCompactedAt = System.currentTimeMillis();

            long sizeAfter = Files.exists(walFile) ? Files.size(walFile) : 0;
            return new CompactionResult(sizeBefore, sizeAfter, data.size());
        } catch (IOException e) {
            try {
                if (this.writer == null) {
                    this.writer = new WalWriter(walFile, data.size());
                }
            } catch (Exception ignored) {}
            throw new StorageException("Failed to compact WAL file: " + walFile, e);
        } finally {
            writeLock.unlock();
        }
    }

    private void checkAutoCompact() {
        if (autoCompactThresholdBytes <= 0) return;
        try {
            if (Files.exists(walFile) && Files.size(walFile) > autoCompactThresholdBytes) {
                compact();
            }
        } catch (Exception e) {
            System.err.println("Warning: Auto-compaction failed: " + e.getMessage());
        }
    }

    public EngineStats getStats() {
        long walSize = 0;
        try {
            if (Files.exists(walFile)) {
                walSize = Files.size(walFile);
            }
        } catch (IOException ignored) {}

        return new EngineStats(
                data.size(),
                walSize,
                lastCompactedAt,
                lastRecovery != null ? lastRecovery.recoveredEntries.size() : 0,
                lastRecovery != null && lastRecovery.tornWriteFound,
                writer != null ? writer.currentSequence() : 0
        );
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        try {
            if (writer != null) {
                writer.close();
            }
        } finally {
            writeLock.unlock();
        }
    }
}
