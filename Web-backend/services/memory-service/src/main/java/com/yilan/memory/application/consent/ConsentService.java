package com.yilan.memory.application.consent;

import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.security.MemoryRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Application boundary for append-only, subject-bound consent versions. */
@Service
public final class ConsentService {

    private static final Duration DEFAULT_MAXIMUM_RETENTION = Duration.ofDays(180);
    private static final String DEFAULT_PURPOSE_TEXT_VERSION = "memory-consent-v1";

    private final ConsentStore store;
    private final PolicyEpochService policyEpochService;
    private final Clock clock;
    private final String purposeTextVersion;
    private final Duration maximumRetention;

    @Autowired
    public ConsentService(ConsentStore store, PolicyEpochService policyEpochService) {
        this(store, policyEpochService, Clock.systemUTC(), DEFAULT_PURPOSE_TEXT_VERSION, DEFAULT_MAXIMUM_RETENTION);
    }

    public ConsentService(
            ConsentStore store,
            PolicyEpochService policyEpochService,
            Clock clock,
            String purposeTextVersion,
            Duration maximumRetention) {
        this.store = Objects.requireNonNull(store, "store");
        this.policyEpochService = Objects.requireNonNull(policyEpochService, "policyEpochService");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (purposeTextVersion == null || !purposeTextVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("purposeTextVersion");
        }
        this.purposeTextVersion = purposeTextVersion;
        this.maximumRetention = Objects.requireNonNull(maximumRetention, "maximumRetention");
        if (maximumRetention.isZero() || maximumRetention.isNegative()) {
            throw new IllegalArgumentException("maximumRetention");
        }
    }

    public ConsentView current(String subjectHash) {
        return store.find(requireSubject(subjectHash))
                .map(ConsentService::toView)
                .orElseGet(() -> new ConsentView("0", false, Set.of(), Instant.EPOCH));
    }

    /**
     * The persistence port must atomically append policy, advance the
     * PostgreSQL authority epoch, and enqueue the identifier-only purge.
     */
    public ConsentView replace(
            String subjectHash,
            ConsentCommand command,
            String expectedVersion,
            MemoryRole actorRole) {
        var request = prepareReplace(subjectHash, command, expectedVersion, actorRole);
        var committed = store.append(request.subjectHash(), request.expectedRevision(), request.policy());
        policyEpochService.invalidateAfterAuthorityCommit(request.subjectHash(), committed.consentEpoch());
        return toView(new StoredConsent(
                committed.revision(),
                request.policy().longTermEnabled(),
                request.policy().allowedCategories(),
                request.policy().effectiveAt()));
    }

    /**
     * Uses the frozen consent route's required idempotency key to preserve a
     * committed response across a retry without treating a replay as a new
     * authority write.
     */
    public ConsentView replace(
            String subjectHash,
            ConsentCommand command,
            String expectedVersion,
            String idempotencyKey,
            MemoryRole actorRole) {
        var request = prepareReplace(subjectHash, command, expectedVersion, actorRole);
        requireIdempotencyKey(idempotencyKey);
        var result = store.append(
                request.subjectHash(), request.expectedRevision(), request.policy(), idempotencyKey);
        if (!result.replayed()) {
            policyEpochService.invalidateAfterAuthorityCommit(request.subjectHash(), result.consentEpoch());
        }
        return toView(result.result());
    }

    private ReplaceRequest prepareReplace(
            String subjectHash,
            ConsentCommand command,
            String expectedVersion,
            MemoryRole actorRole) {
        subjectHash = requireSubject(subjectHash);
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(actorRole, "actorRole");
        if (actorRole != MemoryRole.LEARNER) {
            throw new IllegalArgumentException("actorRole");
        }
        var expectedRevision = parseVersion(expectedVersion);
        var categories = parseCategories(command.allowedCategories());
        validateCommand(command, categories);
        var policy = new Policy(
                command.longTermEnabled(),
                categories,
                purposeTextVersion,
                command.longTermEnabled(),
                command.retentionDays(),
                Instant.now(clock),
                actorRole);
        return new ReplaceRequest(subjectHash, expectedRevision, policy);
    }

    private void validateCommand(ConsentCommand command, Set<MemoryCategory> categories) {
        if (!command.longTermEnabled() && (!categories.isEmpty() || command.retentionDays() != null)) {
            throw new IllegalArgumentException("disabled consent cannot permit categories or retention");
        }
        if (command.longTermEnabled() && categories.isEmpty()) {
            throw new IllegalArgumentException("explicit opt-in requires category permission");
        }
        if (command.retentionDays() != null
                && Duration.ofDays(command.retentionDays()).compareTo(maximumRetention) > 0) {
            throw new IllegalArgumentException("retentionDays exceeds server maximum");
        }
    }

    private static Set<MemoryCategory> parseCategories(Set<String> values) {
        var categories = EnumSet.noneOf(MemoryCategory.class);
        for (var value : values) {
            try {
                categories.add(MemoryCategory.valueOf(value));
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new IllegalArgumentException("unknown memory category", error);
            }
        }
        return Set.copyOf(categories);
    }

    private static long parseVersion(String value) {
        if (value == null) {
            throw new IllegalArgumentException("If-Match");
        }
        var normalized = value.trim();
        if (normalized.length() > 1 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            var version = Long.parseLong(normalized);
            if (version < 0) {
                throw new NumberFormatException("negative");
            }
            return version;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("If-Match", error);
        }
    }

    private static String requireSubject(String subjectHash) {
        if (subjectHash == null || subjectHash.isBlank()) {
            throw new IllegalArgumentException("subjectHash");
        }
        return subjectHash;
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key");
        }
    }

    private static ConsentView toView(StoredConsent consent) {
        return new ConsentView(
                Long.toString(consent.revision()),
                consent.longTermEnabled(),
                (consent.longTermEnabled() ? consent.allowedCategories() : Set.<MemoryCategory>of()).stream().map(Enum::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                consent.effectiveAt());
    }

    public interface ConsentStore {
        Optional<StoredConsent> find(String subjectHash);

        Commit append(String subjectHash, long expectedRevision, Policy policy);

        AppendResult append(String subjectHash, long expectedRevision, Policy policy, String idempotencyKey);
    }

    public record StoredConsent(
            long revision,
            boolean longTermEnabled,
            Set<MemoryCategory> allowedCategories,
            Instant effectiveAt) {
        public StoredConsent {
            if (revision < 0) {
                throw new IllegalArgumentException("revision");
            }
            allowedCategories = Set.copyOf(Objects.requireNonNull(allowedCategories, "allowedCategories"));
            Objects.requireNonNull(effectiveAt, "effectiveAt");
        }
    }

    public record Policy(
            boolean longTermEnabled,
            Set<MemoryCategory> allowedCategories,
            String purposeTextVersion,
            boolean sourceCaptureEnabled,
            Integer retentionDays,
            Instant effectiveAt,
            MemoryRole actorRole) {
        public Policy {
            allowedCategories = Set.copyOf(Objects.requireNonNull(allowedCategories, "allowedCategories"));
            Objects.requireNonNull(purposeTextVersion, "purposeTextVersion");
            if (!longTermEnabled && sourceCaptureEnabled) {
                throw new IllegalArgumentException("sourceCaptureEnabled");
            }
            if (retentionDays != null && retentionDays < 1) {
                throw new IllegalArgumentException("retentionDays");
            }
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            Objects.requireNonNull(actorRole, "actorRole");
        }
    }

    public record Commit(long revision, long consentEpoch) {
        public Commit {
            if (revision < 1 || consentEpoch < 1) {
                throw new IllegalArgumentException("commit");
            }
        }
    }

    public record AppendResult(StoredConsent result, long consentEpoch, boolean replayed) {
        public AppendResult {
            Objects.requireNonNull(result, "result");
            if (consentEpoch < 0 || (!replayed && consentEpoch < 1)) {
                throw new IllegalArgumentException("consentEpoch");
            }
        }
    }

    private record ReplaceRequest(String subjectHash, long expectedRevision, Policy policy) {
    }

    public static final class StaleConsentVersionException extends RuntimeException {
        public StaleConsentVersionException() {
            super("stale consent version");
        }
    }

    public static final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() {
            super("consent idempotency conflict");
        }
    }
}
