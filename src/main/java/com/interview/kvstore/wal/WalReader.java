package com.interview.kvstore.wal;

import com.interview.kvstore.exception.CorruptedEntryException;
import com.interview.kvstore.exception.TornWriteException;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Reads WAL entries sequentially from a given byte offset.
 *
 * Two distinct failure modes are reported as two distinct exception
 * types, and the caller (RecoveryManager) is expected to treat them very
 * differently:
 *
 *  - TornWriteException: the file ends before a declared entry finishes.
 *    This is the EXPECTED signature of a crash mid-append and is safe to
 *    truncate away.
 *
 *  - CorruptedEntryException: the bytes are all present, but something
 *    about the content is wrong -- checksum mismatch, an impossible
 *    length, an unknown op code, a sequence number out of order, or
 *    leftover bytes that don't match the entry's own declared lengths.
 *    This is NOT a normal crash artifact and must not be silently
 *    discarded.
 */
public final class WalReader implements AutoCloseable {

    /**
     * Hard upper bound on a single entry's declared size, checked BEFORE
     * any allocation is attempted. Without this, a corrupted length
     * prefix (e.g. a stray bit flip turning a small number into
     * something close to Integer.MAX_VALUE) could cause the reader to
     * try to allocate a multi-gigabyte buffer, either crashing the
     * process with an OutOfMemoryError or being used as a cheap
     * denial-of-service vector against anything that reads untrusted WAL
     * files. 16 MiB is generously larger than any reasonable key/value
     * pair for this store while still catching clearly-bogus lengths.
     */
    static final int MAX_ENTRY_SIZE_BYTES = 16 * 1024 * 1024;

    private final FileChannel channel;
    private final long fileSize;
    private long pos;

    public WalReader(Path walFile) throws IOException {
        this.channel = FileChannel.open(walFile, StandardOpenOption.READ);
        this.fileSize = channel.size();
        this.pos = 0;
    }

    public long position() {
        return pos;
    }

    public boolean hasNext() {
        return pos < fileSize;
    }

    public WalEntry next() throws IOException, TornWriteException, CorruptedEntryException {
        long recordStart = pos;

        if (fileSize - pos < 4) {
            throw new TornWriteException(recordStart);
        }

        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        readFully(lenBuf, pos);
        lenBuf.flip();
        int bodyPlusChecksumLen = lenBuf.getInt();

        // Validate the declared size BEFORE allocating anything based on
        // it. A negative or absurdly large value is treated as corrupt,
        // not as "let's try to allocate that much and see what happens."
        if (bodyPlusChecksumLen <= 0) {
            throw new TornWriteException(recordStart);
        }
        if (bodyPlusChecksumLen > MAX_ENTRY_SIZE_BYTES) {
            throw new CorruptedEntryException(recordStart,
                    "declared entry size " + bodyPlusChecksumLen + " bytes exceeds the " +
                            MAX_ENTRY_SIZE_BYTES + "-byte sanity limit -- refusing to allocate a buffer for it");
        }

        long recordTotalLen = 4L + bodyPlusChecksumLen;
        if (recordStart + recordTotalLen > fileSize) {
            // Declares more bytes than actually exist in the file -- the
            // file simply ends here. This is a torn write, not corruption:
            // the length field itself is plausible, there just isn't
            // enough file left to satisfy it (a real crash mid-write).
            throw new TornWriteException(recordStart);
        }

        ByteBuffer bodyAndChecksum = ByteBuffer.allocate(bodyPlusChecksumLen);
        readFully(bodyAndChecksum, pos + 4);
        bodyAndChecksum.flip();

        byte[] all = bodyAndChecksum.array();
        int bodyLen = bodyPlusChecksumLen - 4;
        byte[] body = new byte[bodyLen];
        System.arraycopy(all, 0, body, 0, bodyLen);
        int storedChecksum = ByteBuffer.wrap(all, bodyLen, 4).getInt();

        CRC32 crc = new CRC32();
        crc.update(body);
        int actualChecksum = (int) crc.getValue();

        if (actualChecksum != storedChecksum) {
            throw new CorruptedEntryException(recordStart);
        }

        WalEntry entry = parseBody(body, recordStart);
        pos += recordTotalLen;
        return entry;
    }

    /**
     * Parses the body bytes into a WalEntry, validating structure along
     * the way: key/value lengths must be non-negative and must not run
     * past the bytes actually available, the op code must be a known
     * value, and after reading everything the buffer must be exactly
     * exhausted (no leftover bytes, no premature end) -- any mismatch
     * there means the declared lengths don't actually describe the
     * content, which is corruption even though the checksum happened to
     * pass (extremely unlikely, but defense in depth costs little here).
     */
    private static WalEntry parseBody(byte[] body, long recordStart) throws CorruptedEntryException {
        try {
            ByteBuffer buf = ByteBuffer.wrap(body);
            long seq = buf.getLong();

            byte opCode = buf.get();
            WalEntry.OpType op;
            try {
                op = WalEntry.OpType.fromCode(opCode);
            } catch (IllegalArgumentException e) {
                throw new CorruptedEntryException(recordStart, "unknown operation code " + opCode);
            }

            int keyLen = buf.getInt();
            if (keyLen < 0 || keyLen > buf.remaining()) {
                throw new CorruptedEntryException(recordStart,
                        "declared key length " + keyLen + " is invalid for the remaining " + buf.remaining() + " bytes");
            }
            byte[] keyBytes = new byte[keyLen];
            buf.get(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            if (buf.remaining() < 4) {
                throw new CorruptedEntryException(recordStart, "record ends before value-length field");
            }
            int valLen = buf.getInt();
            if (valLen < 0 || valLen > buf.remaining()) {
                throw new CorruptedEntryException(recordStart,
                        "declared value length " + valLen + " is invalid for the remaining " + buf.remaining() + " bytes");
            }
            String value = null;
            if (valLen > 0) {
                byte[] valBytes = new byte[valLen];
                buf.get(valBytes);
                value = new String(valBytes, StandardCharsets.UTF_8);
            }

            if (buf.remaining() != 0) {
                // Every declared field was read, but bytes are left over --
                // the lengths don't actually describe this record's content.
                throw new CorruptedEntryException(recordStart,
                        buf.remaining() + " unexplained trailing bytes after parsing all declared fields");
            }

            return new WalEntry(seq, op, key, value);
        } catch (BufferUnderflowException e) {
            // Any field read that ran past the buffer's actual bytes ends
            // up here -- also corruption, not a torn write, since we
            // already confirmed the full declared byte count was present
            // and checksum-valid before we started parsing.
            throw new CorruptedEntryException(recordStart, "record ended unexpectedly while parsing fields");
        }
    }

    private void readFully(ByteBuffer buf, long position) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf, position + buf.position());
            if (n < 0) throw new IOException("Unexpected EOF while reading WAL");
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
