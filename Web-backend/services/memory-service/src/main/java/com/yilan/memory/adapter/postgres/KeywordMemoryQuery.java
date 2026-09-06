package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.application.context.RetrievalChannel.AuthorizedMemoryQuery;
import com.yilan.memory.application.context.RetrievalChannel.RankedMemory;
import com.yilan.memory.application.privacy.PayloadProtector;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/** Controlled keyword matching; user text never changes SQL structure. */
@Repository
public class KeywordMemoryQuery implements RetrievalChannel {

    private final JdbcClient jdbcClient;
    private final PayloadProtector payloadProtector;

    @Autowired
    public KeywordMemoryQuery(JdbcClient jdbcClient, PayloadProtector payloadProtector) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
    }

    /** Compatibility constructor for direct STANDARD-only query tests. */
    public KeywordMemoryQuery(JdbcClient jdbcClient) {
        this(jdbcClient, PayloadProtector.disabled());
    }

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
        Objects.requireNonNull(query, "query");
        var term = controlledTerm(query.queryText());
        if (term == null || query.allowedTypes().isEmpty()) {
            return List.of();
        }
        var types = query.allowedTypes().stream().sorted().toList();
        var placeholders = java.util.stream.IntStream.range(0, types.size())
                .mapToObj(index -> ":type" + index)
                .collect(java.util.stream.Collectors.joining(", "));
        var statement = jdbcClient.sql("""
                        SELECT learner.subject_hash, assertion.memory_assertion_id, version.memory_version_id,
                               assertion.memory_type, assertion.assertion_key, version.value_json::text AS value_json,
                               version.protected_value_ciphertext, version.protected_value_nonce,
                               version.protected_value_key_reference, version.protected_value_algorithm,
                               version.protected_value_crypto_version, version.confidence, version.stability_score,
                               version.valid_from, version.valid_until, version.recorded_at, version.recorded_until,
                               version.privacy_level,
                               (SELECT string_agg(link.source_event_id::text, ',' ORDER BY link.source_event_id)
                                FROM memory_source_link link
                                JOIN interaction_event source ON source.event_id = link.source_event_id
                                    AND source.schema_version = link.source_schema_version
                                    AND source.learner_subject_id = link.learner_subject_id
                                WHERE link.memory_version_id = version.memory_version_id
                                  AND link.learner_subject_id = version.learner_subject_id) AS source_ids
                        FROM memory_head_projection head
                        JOIN memory_version version ON version.memory_version_id = head.memory_version_id
                            AND version.learner_subject_id = head.learner_subject_id
                        JOIN memory_assertion assertion ON assertion.memory_assertion_id = version.memory_assertion_id
                            AND assertion.learner_subject_id = version.learner_subject_id
                        JOIN learner_subject learner ON learner.learner_subject_id = version.learner_subject_id
                        WHERE learner.subject_hash = :subjectHash
                          AND learner.status = 'ACTIVE'
                          AND head.status = 'ACTIVE'
                          AND version.status = 'ACTIVE'
                          AND version.privacy_level <> 'HIGH'
                          AND version.valid_from <= :asOf
                          AND (version.valid_until IS NULL OR version.valid_until > :asOf)
                          AND version.recorded_at <= :asOf
                          AND (version.recorded_until IS NULL OR version.recorded_until > :asOf)
                          AND (lower(assertion.assertion_key) LIKE :pattern ESCAPE '\\'
                               OR (version.value_json IS NOT NULL
                                   AND lower(version.value_json::text) LIKE :pattern ESCAPE '\\')
                               OR version.privacy_level = 'SENSITIVE')
                          AND assertion.memory_type IN (%s)
                          AND EXISTS (
                              SELECT 1 FROM memory_source_link link
                              JOIN interaction_event source ON source.event_id = link.source_event_id
                                  AND source.schema_version = link.source_schema_version
                                  AND source.learner_subject_id = link.learner_subject_id
                              WHERE link.memory_version_id = version.memory_version_id
                                AND link.learner_subject_id = version.learner_subject_id)
                          AND NOT EXISTS (
                              SELECT 1 FROM memory_relation_event relation
                              WHERE relation.learner_subject_id = version.learner_subject_id
                                AND relation.relation_status = 'ACTIVE'
                                AND relation.relation_type IN ('CONFLICTS', 'CONFLICTS_WITH')
                                AND (relation.from_memory_assertion_id = assertion.memory_assertion_id
                                     OR relation.to_memory_assertion_id = assertion.memory_assertion_id))
                        ORDER BY version.confidence DESC, version.stability_score DESC, version.valid_from DESC
                        """.formatted(placeholders))
                .param("subjectHash", query.subjectHash())
                .param("asOf", Timestamp.from(query.asOf()))
                .param("pattern", "%" + escapeLike(term) + "%");
        for (var index = 0; index < types.size(); index++) {
            statement = statement.param("type" + index, types.get(index).name());
        }
        return statement.query((resultSet, ignored) -> new KeywordCandidate(
                        resultSet.getString("assertion_key"),
                        StructuredMemoryQuery.map(resultSet, BigDecimal.ONE, payloadProtector)))
                .list().stream()
                .filter(candidate -> candidate.assertionKey().toLowerCase(java.util.Locale.ROOT).contains(term)
                        || candidate.memory().valueJson().toLowerCase(java.util.Locale.ROOT).contains(term))
                .map(KeywordCandidate::memory)
                .limit(query.candidateLimit())
                .toList();
    }

    private static String controlledTerm(String raw) {
        if (raw == null) {
            return null;
        }
        var normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[\\p{L}\\p{N} _.-]{1,128}")) {
            return null;
        }
        return normalized;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record KeywordCandidate(String assertionKey, RankedMemory memory) {
    }
}
