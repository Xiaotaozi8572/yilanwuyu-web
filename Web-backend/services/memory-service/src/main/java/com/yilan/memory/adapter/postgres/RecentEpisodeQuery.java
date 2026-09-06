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

/** Recency channel limited to readable memories with authority-classified episode sources. */
@Repository
public class RecentEpisodeQuery implements RetrievalChannel {

    private final JdbcClient jdbcClient;
    private final PayloadProtector payloadProtector;

    @Autowired
    public RecentEpisodeQuery(JdbcClient jdbcClient, PayloadProtector payloadProtector) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
    }

    /** Compatibility constructor for direct STANDARD-only query tests. */
    public RecentEpisodeQuery(JdbcClient jdbcClient) {
        this(jdbcClient, PayloadProtector.disabled());
    }

    @Override
    public String name() {
        return "recent_episode";
    }

    @Override
    public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.allowedTypes().isEmpty()) {
            return List.of();
        }
        var types = query.allowedTypes().stream().sorted().toList();
        var placeholders = java.util.stream.IntStream.range(0, types.size())
                .mapToObj(index -> ":type" + index)
                .collect(java.util.stream.Collectors.joining(", "));
        var statement = jdbcClient.sql("""
                        SELECT learner.subject_hash, assertion.memory_assertion_id, version.memory_version_id, assertion.memory_type,
                               version.value_json::text AS value_json, version.protected_value_ciphertext,
                               version.protected_value_nonce, version.protected_value_key_reference,
                               version.protected_value_algorithm, version.protected_value_crypto_version,
                               version.confidence, version.stability_score,
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
                          AND EXISTS (
                              SELECT 1 FROM memory_source_link episode_link
                              JOIN interaction_event episode ON episode.event_id = episode_link.source_event_id
                                  AND episode.schema_version = episode_link.source_schema_version
                                  AND episode.learner_subject_id = episode_link.learner_subject_id
                              WHERE episode_link.memory_version_id = version.memory_version_id
                                AND episode_link.learner_subject_id = version.learner_subject_id
                                AND episode.source_kind = 'EPISODE')
                          AND assertion.memory_type IN (%s)
                          AND NOT EXISTS (
                              SELECT 1 FROM memory_relation_event relation
                              WHERE relation.learner_subject_id = version.learner_subject_id
                                AND relation.relation_status = 'ACTIVE'
                                AND relation.relation_type IN ('CONFLICTS', 'CONFLICTS_WITH')
                                AND (relation.from_memory_assertion_id = assertion.memory_assertion_id
                                     OR relation.to_memory_assertion_id = assertion.memory_assertion_id))
                        ORDER BY (
                            SELECT max(episode.occurred_at)
                            FROM memory_source_link episode_link
                            JOIN interaction_event episode ON episode.event_id = episode_link.source_event_id
                                AND episode.schema_version = episode_link.source_schema_version
                                AND episode.learner_subject_id = episode_link.learner_subject_id
                            WHERE episode_link.memory_version_id = version.memory_version_id
                              AND episode_link.learner_subject_id = version.learner_subject_id
                              AND episode.source_kind = 'EPISODE') DESC
                        LIMIT :limit
                        """.formatted(placeholders))
                .param("subjectHash", query.subjectHash())
                .param("asOf", Timestamp.from(query.asOf()))
                .param("limit", query.candidateLimit());
        for (var index = 0; index < types.size(); index++) {
            statement = statement.param("type" + index, types.get(index).name());
        }
        return statement.query((resultSet, ignored) -> StructuredMemoryQuery.map(
                resultSet, BigDecimal.ONE, payloadProtector)).list();
    }
}
