package com.interview.kvstore.wal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Cryptographic helper for the Tamper-Evident WAL Integrity Chain.
 * Uses standard-library HMAC-SHA-256 to cryptographically bind each log record
 * to the preceding record, providing auditability and detecting intentional
 * modification, removal of records from the middle, or reordering.
 */
public final class WalIntegrity {

    public static final String HMAC_ALGORITHM = "HmacSHA256";
    public static final int TAG_LENGTH = 32; // SHA-256 produces 32-byte digest
    public static final byte[] GENESIS_TAG = new byte[TAG_LENGTH]; // 32 zero bytes

    public static final byte[] MAGIC_HEADER = "WALKVS02".getBytes(StandardCharsets.US_ASCII); // 8 bytes
    public static final int FORMAT_VERSION_2 = 2;
    public static final int HEADER_SIZE = 16; // 8 bytes magic + 4 bytes version + 4 bytes flags

    private static final byte[] DOMAIN_MARKER = "WAL-v2-HMAC-SHA256".getBytes(StandardCharsets.US_ASCII);

    private WalIntegrity() {}

    /**
     * Calculates the HMAC-SHA-256 tag for a record:
     * HMAC(secret, DOMAIN_MARKER || prevHmacTag || canonicalBytes)
     */
    public static byte[] calculateHmac(byte[] secret, byte[] prevTag, byte[] canonicalBytes) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("Integrity secret cannot be null or empty");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            mac.update(DOMAIN_MARKER);
            mac.update(prevTag != null ? prevTag : GENESIS_TAG);
            mac.update(canonicalBytes);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA-256", e);
        }
    }

    /**
     * Verifies the HMAC-SHA-256 tag in constant time using MessageDigest.isEqual.
     */
    public static boolean verifyHmac(byte[] secret, byte[] prevTag, byte[] canonicalBytes, byte[] expectedHmac) {
        if (expectedHmac == null || expectedHmac.length != TAG_LENGTH) {
            return false;
        }
        byte[] computed = calculateHmac(secret, prevTag, canonicalBytes);
        return MessageDigest.isEqual(computed, expectedHmac);
    }

    /**
     * Returns the first charCount hex characters of the given tag safely.
     */
    public static String toHexPrefix(byte[] tag, int charCount) {
        if (tag == null || tag.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(tag.length * 2);
        for (byte b : tag) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, Math.min(charCount, sb.length()));
    }
}
