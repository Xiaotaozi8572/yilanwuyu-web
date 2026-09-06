package com.yilan.memory.application.privacy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Boundary for locally supplied, non-production source-material keys. Key
 * material is never serialized or logged by the application layer.
 */
public interface DataKeyProvider {

    KeyMaterial activeKeyFor(String subjectHash, KeyPurpose purpose);

    KeyMaterial keyFor(String subjectHash, KeyPurpose purpose, String keyReference);

    /** Creates a fresh key material candidate for one immutable interaction frame. */
    default SourceKeyMaterial activeSourceKeyFor(String subjectHash, UUID eventId, String schemaVersion) {
        throw new SecurityException("protected source key unavailable");
    }

    /** Resolves only the envelope bound to the exact authenticated interaction frame. */
    default KeyMaterial sourceKeyFor(String subjectHash, UUID eventId, String schemaVersion, String keyReference) {
        throw new SecurityException("protected source key unavailable");
    }

    /** Convenience path for isolated local/test use; authority ingress stores after event insertion. */
    default void storeSourceEnvelopeIfAbsent(
            String subjectHash,
            UUID eventId,
            String schemaVersion,
            InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
        throw new SecurityException("protected source key unavailable");
    }

    record KeyMaterial(String keyReference, byte[] key) {

        public KeyMaterial {
            if (keyReference == null || !keyReference.matches("k-[0-9a-f]{32}")) {
                throw new IllegalArgumentException("keyReference");
            }
            key = Objects.requireNonNull(key, "key").clone();
            if (key.length != 32) {
                throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
            }
        }

        @Override
        public byte[] key() {
            return key.clone();
        }
    }

    record SourceKeyMaterial(KeyMaterial keyMaterial, InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
        public SourceKeyMaterial {
            keyMaterial = Objects.requireNonNull(keyMaterial, "keyMaterial");
            envelope = Objects.requireNonNull(envelope, "envelope");
            if (!keyMaterial.keyReference().equals(envelope.dekReference())) {
                throw new IllegalArgumentException("source key envelope reference");
            }
        }
    }

    static String opaqueReference(byte[] key) {
        Objects.requireNonNull(key, "key");
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "k-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

}
