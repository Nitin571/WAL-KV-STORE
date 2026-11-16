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
    private final byte[] integritySecret;
    private final int formatVersion;
    private long pos;
    private byte[] lastHmacTag;

    public WalReader(Path walFile) throws IOException {
        this(walFile, null);
    }

    public WalReader(Path walFile, byte[] integritySecret) throws IOException {
        this.channel = FileChannel.open(walFile, StandardOpenOption.READ);
        this.fileSize = channel.size();
        this.integritySecret = (integritySecret != null && integritySecret.length > 0)
                ? integritySecret.clone() : null;

        if (fileSize >= WalIntegrity.HEADER_SIZE) {
            ByteBuffer hBuf = ByteBuffer.allocate(WalIntegrity.HEADER_SIZE);
            readFully(hBuf, 0);
            hBuf.flip();
            byte[] magic = new byte[8];
            hBuf.get(magic);
            int version = hBuf.getInt();
            int flags = hBuf.getInt();

            if (java.util.Arrays.equals(magic, WalIntegrity.MAGIC_HEADER) && version == WalIntegrity.FORMAT_VERSION_2) {
                this.formatVersion = 2;
                this.pos = WalIntegrity.HEADER_SIZE;
                this.lastHmacTag = WalIntegrity.GENESIS_TAG.clone();
            } else {
                this.formatVersion = 1;
                this.pos = 0;
                this.lastHmacTag = null;
            }
        } else {
            this.formatVersion = 1;
            this.pos = 0;
            this.lastHmacTag = null;
        }
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public byte[] getLastHmacTag() {
        return lastHmacTag != null ? lastHmacTag.clone() : null;
    }

    public long getHeaderSize() {
        return formatVersion == 2 ? WalIntegrity.HEADER_SIZE : 0;
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
        int declaredLen = lenBuf.getInt();

        if (declaredLen <= 0) {
            throw new CorruptedEntryException(recordStart,
                    "declared entry size " + declaredLen + " is zero or negative -- " +
                    "this is not a plausible crash artifact and indicates corruption");
        }

        if (formatVersion == 2) {
            int minIntegrityLen = 21 + WalIntegrity.TAG_LENGTH;
            if (declaredLen < minIntegrityLen) {
                throw new CorruptedEntryException(recordStart,
                        "declared entry size " + declaredLen + " bytes is smaller than the minimum integrity entry size (" +
                                minIntegrityLen + " bytes)");
            }
            if (declaredLen > MAX_ENTRY_SIZE_BYTES + WalIntegrity.TAG_LENGTH) {
                throw new CorruptedEntryException(recordStart,
                        "declared entry size " + declaredLen + " bytes exceeds the sanity limit");
            }

            long recordTotalLen = 4L + declaredLen;
            if (recordStart + recordTotalLen > fileSize) {
                throw new TornWriteException(recordStart);
            }

            ByteBuffer entryBuf = ByteBuffer.allocate(declaredLen);
            readFully(entryBuf, pos + 4);
            entryBuf.flip();

            byte[] all = entryBuf.array();
            int bodyPlusCrcLen = declaredLen - WalIntegrity.TAG_LENGTH;
            byte[] bodyAndChecksum = new byte[bodyPlusCrcLen];
            System.arraycopy(all, 0, bodyAndChecksum, 0, bodyPlusCrcLen);
            byte[] tag = new byte[WalIntegrity.TAG_LENGTH];
            System.arraycopy(all, bodyPlusCrcLen, tag, 0, WalIntegrity.TAG_LENGTH);

            int bodyLen = bodyPlusCrcLen - 4;
            byte[] body = new byte[bodyLen];
            System.arraycopy(bodyAndChecksum, 0, body, 0, bodyLen);
            int storedChecksum = ByteBuffer.wrap(bodyAndChecksum, bodyLen, 4).getInt();

            CRC32 crc = new CRC32();
            crc.update(body);
            int actualChecksum = (int) crc.getValue();

            if (actualChecksum != storedChecksum) {
                throw new CorruptedEntryException(recordStart, "CRC32 checksum mismatch in integrity record");
            }

            ByteBuffer canonicalBuf = ByteBuffer.allocate(4 + bodyPlusCrcLen);
            canonicalBuf.putInt(declaredLen);
            canonicalBuf.put(bodyAndChecksum);
            byte[] canonicalBytes = canonicalBuf.array();

            if (integritySecret != null) {
                if (!WalIntegrity.verifyHmac(integritySecret, lastHmacTag, canonicalBytes, tag)) {
                    throw new CorruptedEntryException(recordStart,
                            "HMAC integrity verification failed for record at byte offset " + recordStart);
                }
                this.lastHmacTag = tag.clone();
            }

            WalEntry entry = parseBody(body, recordStart, tag);
            pos += recordTotalLen;
            return entry;
        }

        // Format Version 1 (Legacy)
        if (declaredLen > MAX_ENTRY_SIZE_BYTES) {
            throw new CorruptedEntryException(recordStart,
                    "declared entry size " + declaredLen + " bytes exceeds the " +
                            MAX_ENTRY_SIZE_BYTES + "-byte sanity limit -- refusing to allocate a buffer for it");
        }

        long recordTotalLen = 4L + declaredLen;
        if (recordStart + recordTotalLen > fileSize) {
            throw new TornWriteException(recordStart);
        }

        ByteBuffer bodyAndChecksum = ByteBuffer.allocate(declaredLen);
        readFully(bodyAndChecksum, pos + 4);
        bodyAndChecksum.flip();

        byte[] all = bodyAndChecksum.array();
        int bodyLen = declaredLen - 4;
        byte[] body = new byte[bodyLen];
        System.arraycopy(all, 0, body, 0, bodyLen);
        int storedChecksum = ByteBuffer.wrap(all, bodyLen, 4).getInt();

        CRC32 crc = new CRC32();
        crc.update(body);
        int actualChecksum = (int) crc.getValue();

        if (actualChecksum != storedChecksum) {
            throw new CorruptedEntryException(recordStart);
        }

        WalEntry entry = parseBody(body, recordStart, null);
        pos += recordTotalLen;
        return entry;
    }

    private static WalEntry parseBody(byte[] body, long recordStart) throws CorruptedEntryException {
        return parseBody(body, recordStart, null);
    }

    private static WalEntry parseBody(byte[] body, long recordStart, byte[] hmacTag) throws CorruptedEntryException {
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
            } else if (op == WalEntry.OpType.PUT) {
                // A PUT with valLen == 0 is a valid empty-string value, not
                // null. Only DELETE entries have a genuinely null value.
                value = "";
            }

            if (buf.remaining() != 0) {
                // Every declared field was read, but bytes are left over --
                // the lengths don't actually describe this record's content.
                throw new CorruptedEntryException(recordStart,
                        buf.remaining() + " unexplained trailing bytes after parsing all declared fields");
            }

            return new WalEntry(seq, op, key, value, hmacTag);
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
