package com.interview.kvstore.wal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * One record in the write-ahead log.
 *
 * On-disk layout (all big-endian):
 *   [4 bytes]  total length of everything AFTER this field (body + checksum)
 *   [8 bytes]  sequence number
 *   [1 byte]   op type: 0 = PUT, 1 = DELETE
 *   [4 bytes]  key length
 *   [keyLen]   key bytes (UTF-8)
 *   [4 bytes]  value length (0 for DELETE)
 *   [valLen]   value bytes (UTF-8)
 *   [4 bytes]  CRC32 checksum of everything from seqNum through value
 *
 * Design choices worth being able to defend:
 * - Length prefix: lets the reader know exactly how many bytes to expect,
 *   so a truncated file (crash mid-write) is detectable rather than
 *   silently misparsed.
 * - CRC32, not a cryptographic hash: we're only defending against
 *   accidental corruption (a torn write, a bit flip), not a malicious
 *   actor tampering with the file. CRC32 is far cheaper to compute and
 *   sufficient for that threat model.
 */
public final class WalEntry {

    public enum OpType {
        PUT((byte) 0),
        DELETE((byte) 1);

        final byte code;
        OpType(byte code) { this.code = code; }

        public static OpType fromCode(byte code) {
            for (OpType t : values()) if (t.code == code) return t;
            throw new IllegalArgumentException("Unknown op code: " + code);
        }
    }

    public final long sequenceNumber;
    public final OpType opType;
    public final String key;
    public final String value; // null for DELETE

    public WalEntry(long sequenceNumber, OpType opType, String key, String value) {
        this.sequenceNumber = sequenceNumber;
        this.opType = opType;
        this.key = key;
        this.value = value;
    }

    /** Serializes this entry to the exact bytes written to the log file. */
    public byte[] serialize() {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valBytes = (value == null) ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);

        int bodyLen = 8 + 1 + 4 + keyBytes.length + 4 + valBytes.length;

        ByteBuffer body = ByteBuffer.allocate(bodyLen);
        body.putLong(sequenceNumber);
        body.put(opType.code);
        body.putInt(keyBytes.length);
        body.put(keyBytes);
        body.putInt(valBytes.length);
        body.put(valBytes);

        CRC32 crc = new CRC32();
        crc.update(body.array());
        int checksum = (int) crc.getValue();

        ByteBuffer full = ByteBuffer.allocate(4 + bodyLen + 4);
        full.putInt(bodyLen + 4);
        full.put(body.array());
        full.putInt(checksum);
        return full.array();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WalEntry other)) return false;
        return sequenceNumber == other.sequenceNumber
                && opType == other.opType
                && key.equals(other.key)
                && java.util.Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(sequenceNumber, opType, key, value);
    }

    @Override
    public String toString() {
        return "WalEntry{seq=" + sequenceNumber + ", op=" + opType +
                ", key='" + key + "', value=" + (value == null ? "null" : "'" + value + "'") + "}";
    }
}
