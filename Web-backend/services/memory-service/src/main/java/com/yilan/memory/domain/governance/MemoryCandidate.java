package com.yilan.memory.domain.governance;

import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An opaque, source-bound proposal. It is not a memory assertion until the
 * deterministic governance rule accepts it and history persistence succeeds.
 * observedAt is an untrusted proposal hint only; it never supplies authority
 * recorded time or version valid time.
 */
public record MemoryCandidate(
        UUID candidateId,
        String subjectHash,
        long consentRevision,
        MemoryType memoryType,
        String assertionKey,
        String valueJson,
        BigDecimal confidence,
        BigDecimal stabilityScore,
        PrivacyLevel privacyLevel,
        Instant observedAt,
        List<SourceReference> sources,
        byte[] candidateCiphertext) {

    /** Version of the closed JSON values that may enter governed memory. */
    public static final String VALUE_SCHEMA_VERSION = "memory-value/v1";

    private static final String JSON_WHITESPACE = "[ \\t\\r\\n]*";
    private static final Pattern PREFERENCE_VALUE = closedStringValue(
            "answer_style", "(?:concise|detailed|step_by_step)");
    private static final Pattern MASTERY_VALUE = closedStringValue(
            "mastery_level", "(?:beginner|developing|proficient)");
    private static final Pattern MISCONCEPTION_VALUE = closedStringValue(
            "misconception_code", "[a-z][a-z0-9_]{0,63}");
    private static final Pattern REFLECTION_VALUE = closedStringValue(
            "reflection_code", "[a-z][a-z0-9_]{0,63}");
    private static final Pattern EMPTY_GOVERNANCE_SENTINEL = Pattern.compile(
            JSON_WHITESPACE + "\\{" + JSON_WHITESPACE + "\\}" + JSON_WHITESPACE);

    public MemoryCandidate {
        Objects.requireNonNull(candidateId, "candidateId");
        if (subjectHash == null || subjectHash.isBlank() || subjectHash.length() > 128) {
            throw new IllegalArgumentException("subjectHash");
        }
        if (consentRevision < 0) {
            throw new IllegalArgumentException("consentRevision");
        }
        Objects.requireNonNull(memoryType, "memoryType");
        if (assertionKey == null || assertionKey.isBlank() || assertionKey.length() > 128) {
            throw new IllegalArgumentException("assertionKey");
        }
        if (!hasValidValueSchema(memoryType, valueJson)) {
            throw new IllegalArgumentException("valueJson");
        }
        confidence = unitInterval(confidence, "confidence");
        stabilityScore = unitInterval(stabilityScore, "stabilityScore");
        Objects.requireNonNull(privacyLevel, "privacyLevel");
        Objects.requireNonNull(observedAt, "observedAt");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        Set<SourceKey> sourceKeys = new HashSet<>();
        for (var source : sources) {
            if (!sourceKeys.add(new SourceKey(source.eventId(), source.schemaVersion()))) {
                throw new IllegalArgumentException("sources must be independent event references");
            }
        }
        candidateCiphertext = Objects.requireNonNull(candidateCiphertext, "candidateCiphertext").clone();
        if (candidateCiphertext.length == 0) {
            throw new IllegalArgumentException("candidateCiphertext must not be empty");
        }
    }

    @Override
    public byte[] candidateCiphertext() {
        return candidateCiphertext.clone();
    }

    public String candidateDigest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(candidateCiphertext));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public String redactedMetadataJson() {
        return "{\"candidate_id\":\"" + candidateId + "\",\"memory_type\":\"" + memoryType + "\"}";
    }

    /**
     * Returns an otherwise identical candidate carrying the strictest privacy
     * level supplied by the authority event ledger. Proposal privacy is an
     * untrusted lower bound; it can never reduce a source's authority level.
     */
    public MemoryCandidate withEffectivePrivacy(List<ValidatedSource> validatedSources) {
        var effectivePrivacy = effectivePrivacyLevel(privacyLevel, validatedSources);
        if (effectivePrivacy == privacyLevel) {
            return this;
        }
        return new MemoryCandidate(
                candidateId, subjectHash, consentRevision, memoryType, assertionKey,
                valueJson, confidence, stabilityScore, effectivePrivacy, observedAt,
                sources, candidateCiphertext);
    }

    /**
     * The stored levels deliberately preserve the wire ordering
     * LOW &lt; STANDARD &lt; HIGH as STANDARD &lt; SENSITIVE &lt; HIGH. Do not use enum
     * ordinal: the mapping is part of the compatibility contract.
     */
    public static PrivacyLevel effectivePrivacyLevel(
            PrivacyLevel candidatePrivacy,
            List<ValidatedSource> validatedSources) {
        Objects.requireNonNull(candidatePrivacy, "candidatePrivacy");
        validatedSources = List.copyOf(Objects.requireNonNull(validatedSources, "validatedSources"));
        var effectivePrivacy = candidatePrivacy;
        for (var source : validatedSources) {
            Objects.requireNonNull(source, "validatedSources must not contain null");
            if (privacySeverity(source.authorityPrivacyLevel()) > privacySeverity(effectivePrivacy)) {
                effectivePrivacy = source.authorityPrivacyLevel();
            }
        }
        return effectivePrivacy;
    }

    /**
     * Validates the complete, versioned value grammar without deserializing an
     * unbounded JSON shape. This is intentionally reusable by transport
     * defense-in-depth checks for persisted values that bypass construction.
     */
    public static boolean hasValidValueSchema(MemoryType memoryType, String valueJson) {
        if (memoryType == null || valueJson == null) {
            return false;
        }
        return switch (memoryType) {
            case PREFERENCE -> PREFERENCE_VALUE.matcher(valueJson).matches();
            case MASTERY -> MASTERY_VALUE.matcher(valueJson).matches();
            case MISCONCEPTION -> MISCONCEPTION_VALUE.matcher(valueJson).matches();
            case REFLECTION -> REFLECTION_VALUE.matcher(valueJson).matches();
            case AVIATION_FACT, OPTIMIZATION -> EMPTY_GOVERNANCE_SENTINEL.matcher(valueJson).matches();
        };
    }

    private static Pattern closedStringValue(String key, String value) {
        return Pattern.compile(JSON_WHITESPACE + "\\{" + JSON_WHITESPACE
                + "\"" + key + "\"" + JSON_WHITESPACE + ":" + JSON_WHITESPACE
                + "\"" + value + "\"" + JSON_WHITESPACE + "\\}" + JSON_WHITESPACE);
    }

    private static BigDecimal unitInterval(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be within [0,1]");
        }
        return value;
    }

    private static int privacySeverity(PrivacyLevel privacyLevel) {
        Objects.requireNonNull(privacyLevel, "privacyLevel");
        return switch (privacyLevel) {
            case STANDARD -> 1;
            case SENSITIVE -> 2;
            case HIGH -> 3;
        };
    }

    public enum MemoryType {
        PREFERENCE,
        MASTERY,
        MISCONCEPTION,
        REFLECTION,
        AVIATION_FACT,
        OPTIMIZATION
    }

    /** An untrusted pointer; PostgreSQL supplies its trusted source metadata. */
    public record SourceReference(UUID eventId, String schemaVersion) {

        public SourceReference {
            Objects.requireNonNull(eventId, "eventId");
            if (schemaVersion == null || !schemaVersion.matches("[A-Za-z0-9._-]{1,16}")) {
                throw new IllegalArgumentException("schemaVersion");
            }
        }
    }

    /**
     * Source evidence read from the Java authority event ledger after learner
     * ownership closure. Candidate/worker input cannot construct its semantic
     * kind or occurred time through SourceReference.
     */
    public record ValidatedSource(
            SourceReference reference,
            SourceKind sourceKind,
            Instant occurredAt,
            PrivacyLevel authorityPrivacyLevel) {

        public ValidatedSource {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(authorityPrivacyLevel, "authorityPrivacyLevel");
        }
    }

    private record SourceKey(UUID eventId, String schemaVersion) {
    }
}
