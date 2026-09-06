package com.yilan.memory.adapter.neo4j;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Rebuilds an erasable graph projection exclusively from ordered PostgreSQL authority rows. */
public final class Neo4jRebuilder {

    private final JdbcClient jdbcClient;
    private final RelationProjectionWriter projectionWriter;

    public Neo4jRebuilder(JdbcClient jdbcClient, RelationProjectionWriter projectionWriter) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.projectionWriter = Objects.requireNonNull(projectionWriter, "projectionWriter");
    }

    public RebuildResult rebuildLearner(UUID learnerId) {
        Objects.requireNonNull(learnerId, "learnerId");
        var learnerPartitionHash = jdbcClient.sql("""
                        SELECT subject_hash
                        FROM learner_subject
                        WHERE learner_subject_id = :learnerId AND status = 'ACTIVE'
                        """)
                .param("learnerId", learnerId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("active learner is unavailable"));
        var nodes = loadNodes(learnerId, learnerPartitionHash);
        var relationships = loadRelationships(learnerId);
        var checkpoint = deterministicCheckpoint(learnerId, projectionWriter.digest(nodes), projectionWriter.digest(relationships));
        var snapshot = projectionWriter.replaceVerifiedSnapshot(new RelationProjectionWriter.ProjectionBatch(
                learnerPartitionHash, checkpoint, nodes, relationships));
        return new RebuildResult(snapshot.nodeCount(), snapshot.relationshipCount(), snapshot.nodeDigest(),
                snapshot.relationshipDigest(), snapshot.watermarkEventId());
    }

    private List<RelationProjectionWriter.ProjectionNode> loadNodes(UUID learnerId, String learnerPartitionHash) {
        return jdbcClient.sql("""
                        SELECT DISTINCT ON (assertion.memory_assertion_id)
                               assertion.memory_assertion_id, version.memory_version_id, assertion.memory_type,
                               version.valid_from, version.valid_until, version.status
                        FROM memory_version version
                        JOIN memory_assertion assertion
                          ON assertion.memory_assertion_id = version.memory_assertion_id
                         AND assertion.learner_subject_id = version.learner_subject_id
                        WHERE version.learner_subject_id = :learnerId
                        ORDER BY assertion.memory_assertion_id, version.version_sequence DESC,
                                 version.memory_version_id DESC
                        """)
                .param("learnerId", learnerId)
                .query((resultSet, ignored) -> new RelationProjectionWriter.ProjectionNode(
                        resultSet.getObject("memory_assertion_id", UUID.class),
                        resultSet.getObject("memory_version_id", UUID.class),
                        learnerPartitionHash,
                        resultSet.getString("memory_type"),
                        resultSet.getTimestamp("valid_from").toInstant(),
                        timestampOrNull(resultSet.getTimestamp("valid_until")),
                        resultSet.getString("status")))
                .list();
    }

    private List<RelationProjectionWriter.ProjectionRelation> loadRelationships(UUID learnerId) {
        return jdbcClient.sql("""
                        SELECT memory_relation_event_id, from_memory_assertion_id, to_memory_assertion_id,
                               relation_type, relation_status, created_at
                        FROM memory_relation_event
                        WHERE learner_subject_id = :learnerId
                        ORDER BY created_at, memory_relation_event_id
                        """)
                .param("learnerId", learnerId)
                .query((resultSet, ignored) -> new RelationProjectionWriter.ProjectionRelation(
                        resultSet.getObject("memory_relation_event_id", UUID.class),
                        resultSet.getObject("from_memory_assertion_id", UUID.class),
                        resultSet.getObject("to_memory_assertion_id", UUID.class),
                        resultSet.getString("relation_type"),
                        resultSet.getString("relation_status"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        null))
                .list();
    }

    private static Instant timestampOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static UUID deterministicCheckpoint(UUID learnerId, String nodeDigest, String relationshipDigest) {
        var material = learnerId + "|" + nodeDigest + "|" + relationshipDigest;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    public record RebuildResult(
            long nodeCount,
            long relationshipCount,
            String nodeDigest,
            String relationshipDigest,
            UUID watermarkEventId) {
    }
}
