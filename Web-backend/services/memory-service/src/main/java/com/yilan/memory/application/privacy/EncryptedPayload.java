package com.yilan.memory.application.privacy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Strict binary representation of an AES-256-GCM protected authority payload.
 */
public record EncryptedPayload(String keyReference, byte[] nonce, byte[] ciphertextAndTag) {

    private static final byte[] MAGIC = {'S', 'M', 'E', '1'};
    public static final int CRYPTO_VERSION = 1;
    public static final String ALGORITHM = "AES-256-GCM";
    public static final int GCM_NONCE_BYTES = 12;
    private static final int FORMAT_VERSION = CRYPTO_VERSION;
    private static final int AES_256_GCM = 1;
    private static final int GCM_TAG_BYTES = 16;
    private static final int HEADER_BYTES = MAGIC.length + 1 + 1 + 1 + 1 + Integer.BYTES;

    public EncryptedPayload {
        if (keyReference == null || !keyReference.matches("k-[0-9a-f]{32}")) {
            throw new SecurityException("encrypted payload rejected");
        }
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        ciphertextAndTag = Objects.requireNonNull(ciphertextAndTag, "ciphertextAndTag").clone();
        if (nonce.length != GCM_NONCE_BYTES || ciphertextAndTag.length < GCM_TAG_BYTES) {
            throw new SecurityException("encrypted payload rejected");
        }
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertextAndTag() {
        return ciphertextAndTag.clone();
    }

    public byte[] serialize() {
        var reference = keyReference.getBytes(StandardCharsets.US_ASCII);
        if (reference.length > 255 || ciphertextAndTag.length > Integer.MAX_VALUE - HEADER_BYTES - reference.length - nonce.length) {
            throw new SecurityException("encrypted payload rejected");
        }
        return ByteBuffer.allocate(HEADER_BYTES + reference.length + nonce.length + ciphertextAndTag.length)
                .put(MAGIC)
                .put((byte) FORMAT_VERSION)
                .put((byte) AES_256_GCM)
                .put((byte) reference.length)
                .put((byte) nonce.length)
                .putInt(ciphertextAndTag.length)
                .put(reference)
                .put(nonce)
                .put(ciphertextAndTag)
                .array();
    }

    public static EncryptedPayload parse(byte[] framed) {
        if (framed == null || framed.length < HEADER_BYTES + 1 + GCM_NONCE_BYTES + GCM_TAG_BYTES) {
            throw new SecurityException("encrypted payload rejected");
        }
        try {
            var buffer = ByteBuffer.wrap(framed);
            var magic = new byte[MAGIC.length];
            buffer.get(magic);
            var version = Byte.toUnsignedInt(buffer.get());
            var algorithm = Byte.toUnsignedInt(buffer.get());
            var referenceLength = Byte.toUnsignedInt(buffer.get());
            var nonceLength = Byte.toUnsignedInt(buffer.get());
            var ciphertextLength = buffer.getInt();
            if (!Arrays.equals(magic, MAGIC)
                    || version != FORMAT_VERSION
                    || algorithm != AES_256_GCM
                    || referenceLength == 0
                    || nonceLength != GCM_NONCE_BYTES
                    || ciphertextLength < GCM_TAG_BYTES
                    || referenceLength > buffer.remaining()
                    || nonceLength > buffer.remaining() - referenceLength
                    || ciphertextLength != buffer.remaining() - referenceLength - nonceLength) {
                throw new SecurityException("encrypted payload rejected");
            }
            var reference = new byte[referenceLength];
            var nonce = new byte[nonceLength];
            var ciphertext = new byte[ciphertextLength];
            buffer.get(reference);
            buffer.get(nonce);
            buffer.get(ciphertext);
            if (buffer.hasRemaining()) {
                throw new SecurityException("encrypted payload rejected");
            }
            return new EncryptedPayload(new String(reference, StandardCharsets.US_ASCII), nonce, ciphertext);
        } catch (RuntimeException malformed) {
            if (malformed instanceof SecurityException securityException) {
                throw securityException;
            }
            throw new SecurityException("encrypted payload rejected");
        }
    }
}
