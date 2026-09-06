package com.yilan.memory.application.management;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authority boundary for learner-bound management reads and append-only
 * actions. Implementations own transactionality and persistence; callers
 * only receive minimized views and stable failure categories.
 */
public interface MemoryManagementRepository {

    MemoryPage list(ListQuery query);

    Optional<MemoryDetail> findDetail(String subjectHash, UUID memoryAssertionId);

    ActionResult confirm(Mutation mutation);

    ActionResult correct(Correction mutation);

    ActionResult disable(Mutation mutation);

    record ListQuery(
            String subjectHash,
            String cursor,
            String type,
            String status,
            Instant effectiveAfter) {

        public ListQuery {
            subjectHash = requireSubject(subjectHash);
        }
    }

    record Mutation(String subjectHash, UUID memoryAssertionId, String expectedVersion, String idempotencyKey) {

        public Mutation {
            subjectHash = requireSubject(subjectHash);
            memoryAssertionId = Objects.requireNonNull(memoryAssertionId, "memoryAssertionId");
            expectedVersion = requireVersion(expectedVersion);
            idempotencyKey = requireIdempotencyKey(idempotencyKey);
        }
    }

    record Correction(
            String subjectHash,
            UUID memoryAssertionId,
            String expectedVersion,
            String idempotencyKey,
            Map<String, Object> correctedValue,
            String reason) {

        public Correction {
            subjectHash = requireSubject(subjectHash);
            memoryAssertionId = Objects.requireNonNull(memoryAssertionId, "memoryAssertionId");
            expectedVersion = requireVersion(expectedVersion);
            idempotencyKey = requireIdempotencyKey(idempotencyKey);
            correctedValue = immutableMap(correctedValue, "correctedValue");
            if (reason != null && reason.length() > 512) {
                throw new IllegalArgumentException("reason");
            }
        }
    }

    record MemoryPage(List<MemorySummary> items, String nextCursor) {

        public MemoryPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    record MemorySummary(
            UUID id,
            String version,
            String type,
            String status,
            String confirmationStatus,
            Map<String, Object> displayValue,
            String confidenceBand,
            Instant effectiveAt,
            Instant expiresAt) {

        public MemorySummary {
            id = Objects.requireNonNull(id, "id");
            version = requireVersion(version);
            type = requireNonBlank(type, "type");
            status = requireNonBlank(status, "status");
            confirmationStatus = requireNonBlank(confirmationStatus, "confirmationStatus");
            displayValue = immutableMap(displayValue, "displayValue");
            confidenceBand = requireNonBlank(confidenceBand, "confidenceBand");
            effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
        }
    }

    record MemoryDetail(
            UUID id,
            String version,
            String type,
            String status,
            String confirmationStatus,
            Map<String, Object> displayValue,
            String confidenceBand,
            Instant effectiveAt,
            Instant expiresAt,
            String useClass,
            String privacyClass,
            List<String> sourceSummary,
            String reason,
            List<UsageHistory> usageHistory) {

        public MemoryDetail {
            id = Objects.requireNonNull(id, "id");
            version = requireVersion(version);
            type = requireNonBlank(type, "type");
            status = requireNonBlank(status, "status");
            confirmationStatus = requireNonBlank(confirmationStatus, "confirmationStatus");
            displayValue = immutableMap(displayValue, "displayValue");
            confidenceBand = requireNonBlank(confidenceBand, "confidenceBand");
            effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
            useClass = requireNonBlank(useClass, "useClass");
            privacyClass = requireNonBlank(privacyClass, "privacyClass");
            sourceSummary = List.copyOf(Objects.requireNonNull(sourceSummary, "sourceSummary"));
            usageHistory = List.copyOf(Objects.requireNonNull(usageHistory, "usageHistory"));
        }

        public boolean requiresFreshAuthentication() {
            return "HIGH".equals(privacyClass);
        }
    }

    record UsageHistory(Instant occurredAt, String requestRef, String taskType, String outcome) {

        public UsageHistory {
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            outcome = requireNonBlank(outcome, "outcome");
        }
    }

    record ActionResult(MemoryDetail detail) {

        public ActionResult {
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    final class MemoryNotFoundException extends RuntimeException {
        public MemoryNotFoundException() {
            super("memory not found");
        }
    }

    final class StaleVersionException extends RuntimeException {
        public StaleVersionException() {
            super("stale memory version");
        }
    }

    final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() {
            super("idempotency conflict");
        }
    }

    final class PolicyDisabledException extends RuntimeException {
        public PolicyDisabledException() {
            super("memory policy disabled");
        }
    }

    final class FreshAuthenticationRequiredException extends RuntimeException {
        public FreshAuthenticationRequiredException() {
            super("fresh authentication required");
        }
    }

    private static String requireSubject(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("subjectHash");
        }
        return value;
    }

    private static String requireVersion(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) {
            throw new IllegalArgumentException("expectedVersion");
        }
        return value;
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value, String name) {
        Objects.requireNonNull(value, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
