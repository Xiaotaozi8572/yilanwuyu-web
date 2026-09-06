package com.yilan.memory.adapter.worker;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Strict Java-side closure checks for every untrusted worker response. */
public final class WorkerProposalValidator {

    public static final String BOUNDARY_VIOLATION = "WORKER_BOUNDARY_VIOLATION";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ValidationResult validate(
            CandidateWorkerClient.WorkerResponse response,
            CandidateWorkerClient.WorkerRequest request,
            MemoryProperties.WorkerSettings settings) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(settings, "settings");
        if (!request.requestId().equals(response.requestId())
                || !request.schemaVersion().equals(response.schemaVersion())
                || !settings.schemaVersion().equals(response.schemaVersion())
                || !response.diagnosticCode().isBlank()
                || response.proposals().isEmpty()
                || response.proposals().size() > settings.maxProposals()) {
            return ValidationResult.rejected();
        }
        var validated = new ArrayList<ValidatedProposal>(response.proposals().size());
        for (var proposal : response.proposals()) {
            var one = validateOne(proposal, request, settings);
            if (one.isEmpty()) {
                return ValidationResult.rejected();
            }
            validated.add(one.orElseThrow());
        }
        return ValidationResult.accepted(validated);
    }

    private Optional<ValidatedProposal> validateOne(
            CandidateWorkerClient.WorkerProposal proposal,
            CandidateWorkerClient.WorkerRequest request,
            MemoryProperties.WorkerSettings settings) {
        try {
            var memoryType = MemoryType.valueOf(proposal.memoryType());
            if (!settings.allowedCandidateTypes().contains(memoryType)
                    || !request.allowedCandidateTypes().contains(memoryType)
                    || memoryType == MemoryType.AVIATION_FACT || memoryType == MemoryType.OPTIMIZATION) {
                return Optional.empty();
            }
            var canonicalValueJson = canonicalNormalizedJson(memoryType, proposal.normalizedValueJson());
            if (canonicalValueJson.isEmpty()) {
                return Optional.empty();
            }
            if (!Double.isFinite(proposal.confidence()) || proposal.confidence() < 0.0d || proposal.confidence() > 1.0d) {
                return Optional.empty();
            }
            var privacy = PrivacyLevel.valueOf(proposal.privacyClass());
            if (privacySeverity(privacy) < privacySeverity(request.sourcePrivacyLevel())) {
                return Optional.empty();
            }
            if (request.authorizedSourceText().getBytes(StandardCharsets.UTF_8).length > settings.maxSourceBytes()
                    || proposal.sourceSpans().isEmpty()) {
                return Optional.empty();
            }
            var validatedSpans = validatedAndSortedSpans(proposal.sourceSpans(), request);
            if (validatedSpans.isEmpty()) {
                return Optional.empty();
            }
            var value = canonicalValueJson.orElseThrow();
            var candidateId = deterministicCandidateId(request, memoryType, value, validatedSpans.orElseThrow());
            return Optional.of(new ValidatedProposal(
                    candidateId, memoryType, value, BigDecimal.valueOf(proposal.confidence()),
                    privacy, assertionKey(memoryType, value), candidateCiphertext(value)));
        } catch (IllegalArgumentException | NullPointerException failure) {
            return Optional.empty();
        }
    }

    private static Optional<String> canonicalNormalizedJson(MemoryType memoryType, String rawValueJson) {
        try {
            JsonNode value = JSON.readTree(rawValueJson);
            if (value == null || !value.isObject()) {
                return Optional.empty();
            }
            var canonical = JSON.writeValueAsString(value);
            return MemoryCandidate.hasValidValueSchema(memoryType, canonical)
                    ? Optional.of(canonical) : Optional.empty();
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    private static Optional<List<ValidatedSpan>> validatedAndSortedSpans(
            List<CandidateWorkerClient.SourceSpan> sourceSpans,
            CandidateWorkerClient.WorkerRequest request) {
        var boundaries = utf8Boundaries(request.authorizedSourceText());
        var sourceBytes = request.authorizedSourceText().getBytes(StandardCharsets.UTF_8);
        var spans = new ArrayList<ValidatedSpan>(sourceSpans.size());
        for (var span : sourceSpans) {
            if (span.sourceId() == null || !span.sourceId().equals(request.sourceId())
                    || span.sourceDigest() == null || !span.sourceDigest().equals(request.sourceDigest())
                    || span.startOffset() < 0 || span.startOffset() >= span.endOffset()
                    || span.endOffset() > sourceBytes.length
                    || !boundaries.contains(span.startOffset()) || !boundaries.contains(span.endOffset())) {
                return Optional.empty();
            }
            spans.add(new ValidatedSpan(span.sourceId(), span.startOffset(), span.endOffset(), span.sourceDigest()));
        }
        spans.sort(Comparator.comparing(ValidatedSpan::sourceId)
                .thenComparingInt(ValidatedSpan::startOffset)
                .thenComparingInt(ValidatedSpan::endOffset)
                .thenComparing(ValidatedSpan::sourceDigest));
        return Optional.of(List.copyOf(spans));
    }

    public static String sha256(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    public static byte[] sha256Bytes(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static UUID deterministicCandidateId(
            CandidateWorkerClient.WorkerRequest request,
            MemoryType memoryType,
            String canonicalNormalizedJson,
            List<ValidatedSpan> spans) {
        var material = new StringBuilder("candidate/v2|")
                .append(request.eventId()).append('|')
                .append(request.sourceId()).append('|')
                .append(request.sourceDigest()).append('|')
                .append(memoryType.name()).append('|')
                .append(canonicalNormalizedJson);
        for (var span : spans) {
            material.append('|').append(span.sourceId())
                    .append(':').append(span.startOffset())
                    .append(':').append(span.endOffset())
                    .append(':').append(span.sourceDigest());
        }
        return UUID.nameUUIDFromBytes(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] candidateCiphertext(String normalizedValueJson) {
        return sha256Bytes("worker-candidate/v1|" + normalizedValueJson);
    }

    private static String assertionKey(MemoryType memoryType, String valueJson) {
        return switch (memoryType) {
            case PREFERENCE -> "preference:answer_style";
            case MASTERY -> "mastery:level";
            case MISCONCEPTION -> "misconception:" + stringValue(valueJson, "misconception_code");
            case REFLECTION -> "reflection:" + stringValue(valueJson, "reflection_code");
            case AVIATION_FACT, OPTIMIZATION -> throw new IllegalArgumentException("unsafe memory type");
        };
    }

    private static String stringValue(String valueJson, String key) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([a-z][a-z0-9_]{0,63})\\\"")
                .matcher(valueJson);
        if (!matcher.find()) {
            throw new IllegalArgumentException("invalid value schema");
        }
        return matcher.group(1);
    }

    private static java.util.Set<Integer> utf8Boundaries(String value) {
        var boundaries = new java.util.HashSet<Integer>();
        boundaries.add(0);
        int offset = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            offset += new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            boundaries.add(offset);
            index += Character.charCount(codePoint);
        }
        return boundaries;
    }

    private static int privacySeverity(PrivacyLevel level) {
        return switch (level) {
            case STANDARD -> 1;
            case SENSITIVE -> 2;
            case HIGH -> 3;
        };
    }

    public record ValidationResult(List<ValidatedProposal> proposals, String diagnosticCode) {
        public ValidationResult {
            proposals = List.copyOf(Objects.requireNonNull(proposals, "proposals"));
            Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        }

        static ValidationResult accepted(List<ValidatedProposal> proposals) {
            return new ValidationResult(proposals, "");
        }

        static ValidationResult rejected() {
            return new ValidationResult(List.of(), BOUNDARY_VIOLATION);
        }

        public boolean accepted() {
            return diagnosticCode.isEmpty();
        }
    }

    public record ValidatedProposal(
            UUID candidateId,
            MemoryType memoryType,
            String valueJson,
            BigDecimal confidence,
            PrivacyLevel privacyLevel,
            String assertionKey,
            byte[] candidateCiphertext) {

        public ValidatedProposal {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(memoryType, "memoryType");
            Objects.requireNonNull(valueJson, "valueJson");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(privacyLevel, "privacyLevel");
            Objects.requireNonNull(assertionKey, "assertionKey");
            candidateCiphertext = Objects.requireNonNull(candidateCiphertext, "candidateCiphertext").clone();
        }

        @Override
        public byte[] candidateCiphertext() {
            return candidateCiphertext.clone();
        }
    }

    private record ValidatedSpan(String sourceId, int startOffset, int endOffset, String sourceDigest) {
    }
}
