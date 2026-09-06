package com.yilan.memory.adapter.cache;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.identity.LearnerIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only PostgreSQL cache eligibility gate. Every cache-tier operation is
 * preceded by a fresh call so epochs, current consent, and HIGH privacy never
 * come from a cache.
 */
public final class PostgresCacheAuthorityGate {

    private static final String READ_ELIGIBILITY = """
            SELECT epoch.memory_epoch,
                   epoch.consent_epoch,
                   subject.status AS subject_status,
                   policy.status AS policy_status,
                   policy.valid_from AS policy_valid_from,
                   policy.valid_until AS policy_valid_until,
                   EXISTS (
                       SELECT 1
                       FROM memory_head_projection head
                       JOIN memory_version version
                         ON version.memory_version_id = head.memory_version_id
                        AND version.learner_subject_id = head.learner_subject_id
                       JOIN memory_assertion assertion
                         ON assertion.memory_assertion_id = head.memory_assertion_id
                        AND assertion.learner_subject_id = head.learner_subject_id
                       WHERE head.learner_subject_id = subject.learner_subject_id
                         AND head.status = 'ACTIVE'
                         AND version.status = 'ACTIVE'
                         AND version.valid_from <= :asOf
                         AND (version.valid_until IS NULL OR :asOf < version.valid_until)
                         AND (version.recorded_until IS NULL OR :asOf < version.recorded_until)
                         AND version.privacy_level = 'HIGH'
                         AND assertion.memory_type IN (:memoryTypes)
                   ) AS high_privacy
            FROM learner_subject subject
            JOIN learner_epoch epoch ON epoch.learner_subject_id = subject.learner_subject_id
            LEFT JOIN LATERAL (
                SELECT status, valid_from, valid_until
                FROM consent_policy_version
                WHERE learner_subject_id = subject.learner_subject_id
                ORDER BY revision DESC, created_at DESC
                LIMIT 1
            ) policy ON TRUE
            WHERE subject.subject_hash = :subjectHash
            """;

    private final AuthorityReader authorityReader;
    private final ContextCacheKeyFactory keyFactory;
    private final Clock clock;

    public PostgresCacheAuthorityGate(JdbcClient jdbcClient, ContextCacheKeyFactory keyFactory, Clock clock) {
        this(new JdbcAuthorityReader(jdbcClient), keyFactory, clock);
    }

    public PostgresCacheAuthorityGate(AuthorityReader authorityReader, ContextCacheKeyFactory keyFactory, Clock clock) {
        this.authorityReader = Objects.requireNonNull(authorityReader, "authorityReader");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns no key when authority state says neither tier may be touched. */
    public Optional<String> eligibleKey(
            LearnerIdentity identity,
            ResolveMemoryContextUseCase.MemoryQuery query) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(query, "query");
        if (query.graphRequired()) {
            return Optional.empty();
        }
        return authorityReader.read(identity.subjectHash(), query.allowedTypes(), Instant.now(clock))
                .filter(snapshot -> snapshot.consentPermitsCaching() && !snapshot.highPrivacy())
                .flatMap(snapshot -> keyFactory.create(
                        identity, query, snapshot.memoryEpoch(), snapshot.consentEpoch()));
    }

    @FunctionalInterface
    public interface AuthorityReader {
        Optional<AuthoritySnapshot> read(String subjectHash, Set<MemoryType> requestedTypes, Instant asOf);
    }

    /** Snapshot has only eligibility metadata, never memory content or profile data. */
    public record AuthoritySnapshot(
            long memoryEpoch,
            long consentEpoch,
            boolean consentPermitsCaching,
            boolean highPrivacy) {
        public AuthoritySnapshot {
            if (memoryEpoch < 0 || consentEpoch < 0) {
                throw new IllegalArgumentException("epoch");
            }
        }
    }

    private static final class JdbcAuthorityReader implements AuthorityReader {
        private final JdbcClient jdbcClient;

        private JdbcAuthorityReader(JdbcClient jdbcClient) {
            this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        }

        @Override
        public Optional<AuthoritySnapshot> read(String subjectHash, Set<MemoryType> requestedTypes, Instant asOf) {
            var types = requestedTypes.stream().map(Enum::name).sorted().toList();
            if (types.isEmpty()) {
                return Optional.empty();
            }
            return jdbcClient.sql(READ_ELIGIBILITY)
                    .param("subjectHash", subjectHash)
                    .param("memoryTypes", types)
                    .param("asOf", Timestamp.from(asOf))
                    .query((resultSet, rowNumber) -> mapSnapshot(resultSet, asOf))
                    .optional();
        }

        private static AuthoritySnapshot mapSnapshot(ResultSet resultSet, Instant asOf) throws SQLException {
            var validFrom = resultSet.getTimestamp("policy_valid_from");
            var validUntil = resultSet.getTimestamp("policy_valid_until");
            var currentPolicy = "ACTIVE".equals(resultSet.getString("policy_status"))
                    && validFrom != null && !asOf.isBefore(validFrom.toInstant())
                    && (validUntil == null || asOf.isBefore(validUntil.toInstant()));
            var consentPermitsCaching = "ACTIVE".equals(resultSet.getString("subject_status")) && currentPolicy;
            return new AuthoritySnapshot(
                    resultSet.getLong("memory_epoch"),
                    resultSet.getLong("consent_epoch"),
                    consentPermitsCaching,
                    resultSet.getBoolean("high_privacy"));
        }
    }
}
