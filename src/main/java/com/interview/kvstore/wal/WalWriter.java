package com.interview.kvstore.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends entries to the write-ahead log and forces them to physical disk
 * before returning. This is the durability boundary of the whole system:
 * once append() returns normally, the entry is guaranteed to survive a
 * process crash.
 *
 * Why FileChannel.force(true) and not just write()?
 * write() may only land in the OS page cache. The kernel can hold dirty
 * pages in memory before flushing them to physical disk; a crash before
 * that flush happens can lose data even though write() returned
 * successfully. force(true) issues an fsync AND flushes file metadata
 * (like the new file length, since every append extends the file) so the
 * bytes are actually durable.
 */
public final class WalWriter implements AutoCloseable {

    private final FileChannel channel;
    private final AtomicLong nextSeq;

    public WalWriter(Path walFile, long startingSeq) throws IOException {
        this.channel = FileChannel.open(
                walFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        this.nextSeq = new AtomicLong(startingSeq);
    }

    public synchronized WalEntry appendPut(String key, String value) throws IOException {
        WalEntry entry = new WalEntry(nextSeq.getAndIncrement(), WalEntry.OpType.PUT, key, value);
        writeAndForce(entry);
        return entry;
    }

    public synchronized WalEntry appendDelete(String key) throws IOException {
        WalEntry entry = new WalEntry(nextSeq.getAndIncrement(), WalEntry.OpType.DELETE, key, null);
        writeAndForce(entry);
        return entry;
    }

    /**
     * Appends an entire batch of operations to the WAL and performs a SINGLE
     * fsync (force) across all of them. This provides group commit semantics:
     * writing 1,000 items in a batch incurs the disk sync latency of 1 write,
     * rather than 1,000 separate disk syncs.
     */
    public synchronized java.util.List<WalEntry> appendBatch(java.util.List<BatchOp> ops) throws IOException {
        if (ops == null || ops.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<WalEntry> entries = new java.util.ArrayList<>(ops.size());
        java.util.List<byte[]> byteArrays = new java.util.ArrayList<>(ops.size());
        int totalBytes = 0;

        for (BatchOp op : ops) {
            WalEntry entry = new WalEntry(nextSeq.getAndIncrement(), op.opType(), op.key(), op.value());
            byte[] bytes = entry.serialize();
            entries.add(entry);
            byteArrays.add(bytes);
            totalBytes += bytes.length;
        }

        ByteBuffer combined = ByteBuffer.allocate(totalBytes);
        for (byte[] bytes : byteArrays) {
            combined.put(bytes);
        }
        combined.flip();

        while (combined.hasRemaining()) {
            channel.write(combined);
        }
        channel.force(true);

        return entries;
    }

    private void writeAndForce(WalEntry entry) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(entry.serialize());
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        channel.force(true); // the actual durability guarantee lives here
    }

    public long currentSequence() {
        return nextSeq.get();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
