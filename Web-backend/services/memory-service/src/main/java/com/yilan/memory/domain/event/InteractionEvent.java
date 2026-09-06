package com.yilan.memory.domain.event;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport-free event record retained by the PostgreSQL authority. The payload
 * is opaque to this layer; its digest is used for integrity and outbox tracing.
 */
public record InteractionEvent(
        UUID eventId,
        String schemaVersion,
        String eventType,
        SourceKind sourceKind,
        Instant occurredAt,
        Instant receivedAt,
        PrivacyLevel privacyLevel,
        long consentRevision,
        byte[] payloadCiphertext,
        String payloadDigest,
        String traceId) {

    public InteractionEvent {
        Objects.requireNonNull(eventId, "eventId");
        schemaVersion = requiredBounded(schemaVersion, "schemaVersion", 16);
        eventType = requiredBounded(eventType, "eventType", 96);
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(privacyLevel, "privacyLevel");
        if (consentRevision < 0) {
            throw new IllegalArgumentException("consentRevision must be non-negative");
        }
        payloadCiphertext = Objects.requireNonNull(payloadCiphertext, "payloadCiphertext").clone();
        if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadDigest must be a lowercase SHA-256 digest");
        }
        traceId = requiredBounded(traceId, "traceId", 64);
    }

    @Override
    public byte[] payloadCiphertext() {
        return payloadCiphertext.clone();
    }

    public static InteractionEvent opaque(
            UUID eventId,
            String schemaVersion,
            String eventType,
            SourceKind sourceKind,
            Instant occurredAt,
            Instant receivedAt,
            PrivacyLevel privacyLevel,
            long consentRevision,
            byte[] payloadCiphertext,
            String traceId) {
        var ciphertext = Objects.requireNonNull(payloadCiphertext, "payloadCiphertext").clone();
        return new InteractionEvent(
                eventId,
                schemaVersion,
                eventType,
                sourceKind,
                occurredAt,
                receivedAt,
                privacyLevel,
                consentRevision,
                ciphertext,
                sha256(ciphertext),
                traceId);
    }

    private static String requiredBounded(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public enum PrivacyLevel {
        STANDARD,
        SENSITIVE,
        HIGH
    }

    /**
     * Durable authority-owned provenance classification. GENERAL is the safe
     * compatibility default for legacy/unspecified contract producers.
     */
    public enum SourceKind {
        GENERAL,
        EXPLICIT_DECLARATION,
        EPISODE,
        SCORED_ASSESSMENT
    }
}
