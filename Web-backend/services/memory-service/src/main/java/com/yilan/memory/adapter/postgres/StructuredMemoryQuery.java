package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.application.context.RetrievalChannel.AuthorizedMemoryQuery;
import com.yilan.memory.application.context.RetrievalChannel.RankedMemory;
import com.yilan.memory.application.context.RetrievalChannel.UseClass;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact assertion-key lookup over active, learner-closed PostgreSQL history. */
@Repository
public class StructuredMemoryQuery implements RetrievalChannel {

    private final JdbcClient jdbcClient;
    private final PayloadProtector payloadProtector;

    @Autowired
    public StructuredMemoryQuery(JdbcClient jdbcClient, PayloadProtector payloadProtector) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
    }

    /** Compatibility constructor for direct STANDARD-only query tests. */
    public StructuredMemoryQuery(JdbcClient jdbcClient) {
        this(jdbcClient, PayloadProtector.disabled());
    }

    @Override
    public String name() {
        return "structured";
    }

    @Override
    public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.queryText().isBlank() || query.allowedTypes().isEmpty()) {
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
                          AND lower(assertion.assertion_key) = lower(:queryText)
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
                        LIMIT :limit
                        """.formatted(placeholders))
                .param("subjectHash", query.subjectHash())
                .param("asOf", Timestamp.from(query.asOf()))
                .param("queryText", query.queryText())
                .param("limit", query.candidateLimit());
        for (var index = 0; index < types.size(); index++) {
            statement = statement.param("type" + index, types.get(index).name());
        }
        return statement.query((resultSet, ignored) -> map(resultSet, BigDecimal.ONE, payloadProtector)).list();
    }

    static RankedMemory map(ResultSet resultSet, BigDecimal score) throws SQLException {
        return map(resultSet, score, PayloadProtector.disabled());
    }

    static RankedMemory map(ResultSet resultSet, BigDecimal score, PayloadProtector payloadProtector) throws SQLException {
        var sourceIds = resultSet.getString("source_ids");
        var valueJson = readValueJson(resultSet, payloadProtector);
        return new RankedMemory(
                resultSet.getObject("memory_assertion_id", UUID.class),
                resultSet.getObject("memory_version_id", UUID.class),
                MemoryType.valueOf(resultSet.getString("memory_type")),
                valueJson,
                resultSet.getBigDecimal("confidence"),
                resultSet.getBigDecimal("stability_score"),
                instant(resultSet, "valid_from"),
                nullableInstant(resultSet, "valid_until"),
                instant(resultSet, "recorded_at"),
                nullableInstant(resultSet, "recorded_until"),
                estimateTokens(valueJson),
                sourceIds != null && !sourceIds.isBlank(),
                PrivacyLevel.valueOf(resultSet.getString("privacy_level")),
                "ACTIVE", "CONFIRMED",
                sourceIds == null || sourceIds.isBlank() ? List.of() : Arrays.asList(sourceIds.split(",")),
                null, score, UseClass.OPTIONAL);
    }

    static String readValueJson(ResultSet resultSet, PayloadProtector payloadProtector) throws SQLException {
        var stored = resultSet.getString("value_json");
        var privacyLevel = PrivacyLevel.valueOf(resultSet.getString("privacy_level"));
        if ((privacyLevel == PrivacyLevel.SENSITIVE || privacyLevel == PrivacyLevel.HIGH) && stored != null) {
            throw new SecurityException("protected memory value contains plaintext");
        }
        if (stored != null) {
            return stored;
        }
        if (privacyLevel != PrivacyLevel.SENSITIVE) {
            throw new SecurityException("protected memory value is unavailable for retrieval");
        }
        if (!EncryptedPayload.ALGORITHM.equals(resultSet.getString("protected_value_algorithm"))
                || resultSet.getInt("protected_value_crypto_version") != EncryptedPayload.CRYPTO_VERSION) {
            throw new SecurityException("encrypted payload rejected");
        }
        var payload = new EncryptedPayload(
                resultSet.getString("protected_value_key_reference"),
                resultSet.getBytes("protected_value_nonce"),
                resultSet.getBytes("protected_value_ciphertext"));
        var plaintext = payloadProtector.decrypt(new PayloadProtector.PayloadBinding(
                resultSet.getString("subject_hash"), resultSet.getObject("memory_version_id", UUID.class),
                MemoryCandidate.VALUE_SCHEMA_VERSION, "memory_version", KeyPurpose.MEMORY_VERSION_VALUE), payload);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static int estimateTokens(String valueJson) {
        return Math.max(1, (valueJson.length() + 3) / 4);
    }
}
