package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.application.context.RetrievalChannel.AuthorizedMemoryQuery;
import com.yilan.memory.application.context.RetrievalChannel.RankedMemory;
import com.yilan.memory.application.context.ResolveMemoryContextUseCase.QueryEmbedding;
import com.yilan.memory.application.privacy.PayloadProtector;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/** Exact pgvector cosine retrieval after all PostgreSQL authority predicates. */
@Repository
public class VectorMemoryQuery implements RetrievalChannel {

    private final JdbcClient jdbcClient;
    private final PayloadProtector payloadProtector;

    @Autowired
    public VectorMemoryQuery(JdbcClient jdbcClient, PayloadProtector payloadProtector) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
    }

    /** Compatibility constructor for direct STANDARD-only query tests. */
    public VectorMemoryQuery(JdbcClient jdbcClient) {
        this(jdbcClient, PayloadProtector.disabled());
    }

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.queryEmbedding() == null || query.allowedTypes().isEmpty()) {
            return List.of();
        }
        if (!isExplicitL2UnitVector(query.queryEmbedding())) {
            throw new SemanticUnavailableException("EMBEDDING_PROFILE_MISMATCH");
        }
        if (hasStoredEmbeddingWithMismatchedProfile(query)) {
            throw new SemanticUnavailableException("EMBEDDING_PROFILE_MISMATCH");
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
                               (1 - (embedding.embedding <=> CAST(:embedding AS vector))) AS cosine_score,
                               (SELECT string_agg(link.source_event_id::text, ',' ORDER BY link.source_event_id)
                                FROM memory_source_link link
                                JOIN interaction_event source ON source.event_id = link.source_event_id
                                    AND source.schema_version = link.source_schema_version
                                    AND source.learner_subject_id = link.learner_subject_id
                                WHERE link.memory_version_id = version.memory_version_id
                                  AND link.learner_subject_id = version.learner_subject_id) AS source_ids
                        FROM memory_embedding embedding
                        JOIN memory_version version ON version.memory_version_id = embedding.memory_version_id
                            AND version.learner_subject_id = embedding.learner_subject_id
                        JOIN memory_head_projection head ON head.memory_version_id = version.memory_version_id
                            AND head.learner_subject_id = version.learner_subject_id
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
                          AND embedding.embedding_profile = :profile
                          AND embedding.embedding_profile_version = :profileVersion
                          AND embedding.embedding_dimension = :dimension
                          AND embedding.embedding_normalization = :normalization
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
                        ORDER BY embedding.embedding <=> CAST(:embedding AS vector)
                        LIMIT :limit
                        """.formatted(placeholders))
                .param("subjectHash", query.subjectHash())
                .param("asOf", Timestamp.from(query.asOf()))
                .param("embedding", vectorLiteral(query.queryEmbedding().values()))
                .param("profile", query.queryEmbedding().modelId())
                .param("profileVersion", query.queryEmbedding().modelVersion())
                .param("dimension", query.queryEmbedding().dimension())
                .param("normalization", query.queryEmbedding().normalization())
                .param("limit", query.candidateLimit());
        for (var index = 0; index < types.size(); index++) {
            statement = statement.param("type" + index, types.get(index).name());
        }
        return statement.query((resultSet, ignored) -> StructuredMemoryQuery.map(
                resultSet, resultSet.getBigDecimal("cosine_score"), payloadProtector)).list();
    }

    private static String vectorLiteral(List<Float> values) {
        return values.stream().map(value -> Float.toString(value))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static boolean isExplicitL2UnitVector(QueryEmbedding embedding) {
        if (embedding.dimension() != 1024 || embedding.values().size() != 1024
                || !"L2".equals(embedding.normalization())
                || !identifier(embedding.modelId()) || !identifier(embedding.modelVersion())) {
            return false;
        }
        double squaredNorm = 0;
        for (var value : embedding.values()) {
            if (value == null || !Float.isFinite(value)) {
                return false;
            }
            squaredNorm += (double) value * value;
        }
        return squaredNorm > 0.99d && squaredNorm < 1.01d;
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,96}");
    }

    private boolean hasStoredEmbeddingWithMismatchedProfile(AuthorizedMemoryQuery query) {
        var profile = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM memory_embedding embedding
                            JOIN learner_subject learner ON learner.learner_subject_id = embedding.learner_subject_id
                            WHERE learner.subject_hash = :subjectHash
                              AND learner.status = 'ACTIVE') AS has_embedding,
                               EXISTS (
                            SELECT 1 FROM memory_embedding embedding
                            JOIN learner_subject learner ON learner.learner_subject_id = embedding.learner_subject_id
                            WHERE learner.subject_hash = :subjectHash
                              AND learner.status = 'ACTIVE'
                              AND embedding.embedding_profile = :profile
                              AND embedding.embedding_profile_version = :profileVersion
                              AND embedding.embedding_dimension = :dimension
                              AND embedding.embedding_normalization = :normalization) AS has_matching_profile
                        """)
                .param("subjectHash", query.subjectHash())
                .param("profile", query.queryEmbedding().modelId())
                .param("profileVersion", query.queryEmbedding().modelVersion())
                .param("dimension", query.queryEmbedding().dimension())
                .param("normalization", query.queryEmbedding().normalization())
                .query((resultSet, ignored) -> new EmbeddingProfileState(
                        resultSet.getBoolean("has_embedding"), resultSet.getBoolean("has_matching_profile")))
                .single();
        return profile.hasEmbedding() && !profile.hasMatchingProfile();
    }

    private record EmbeddingProfileState(boolean hasEmbedding, boolean hasMatchingProfile) {
    }
}
