package com.yilan.memory.adapter.neo4j;

import com.yilan.memory.observability.MemoryMetrics;

import org.neo4j.driver.Driver;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Values;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes an erasable Neo4j projection from already-trusted PostgreSQL authority
 * identifiers. It never receives raw sources or memory values.
 */
public final class RelationProjectionWriter {

    private static final String PROJECT_NODES = """
            UNWIND $nodes AS node
            MERGE (projection:MemoryProjection {assertionId: node.assertionId})
            SET projection.versionId = node.versionId,
                projection.learnerPartitionHash = node.learnerPartitionHash,
                projection.type = node.type,
                projection.effectiveFrom = node.effectiveFrom,
                projection.effectiveUntil = node.effectiveUntil,
                projection.status = node.status
            """;
    private static final String PROJECT_RELATIONSHIPS = """
            UNWIND $relationships AS relation
            MATCH (from:MemoryProjection {
                assertionId: relation.fromAssertionId,
                learnerPartitionHash: $learnerPartitionHash
            })
            MATCH (to:MemoryProjection {
                assertionId: relation.toAssertionId,
                learnerPartitionHash: $learnerPartitionHash
            })
            MERGE (from)-[projection:MEMORY_RELATION {relationEventId: relation.relationEventId}]->(to)
            SET projection.effectiveFrom = relation.effectiveFrom,
                projection.effectiveUntil = relation.effectiveUntil
            RETURN count(projection) AS projectedCount
            """;
    private static final String UPSERT_WATERMARK = """
            MERGE (watermark:ProjectionWatermark {learnerPartitionHash: $learnerPartitionHash})
            SET watermark.eventId = $eventId,
                watermark.nodeCount = $nodeCount,
                watermark.relationshipCount = $relationshipCount,
                watermark.nodeDigest = $nodeDigest,
                watermark.relationshipDigest = $relationshipDigest
            """;

    private final Driver driver;
    private final MemoryMetrics metrics;

    public RelationProjectionWriter(Driver driver) {
        this(driver, MemoryMetrics.noop());
    }

    public RelationProjectionWriter(Driver driver, MemoryMetrics metrics) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Projects a trusted outbox batch and stores its checkpoint only in the acknowledged graph transaction. */
    public void project(ProjectionBatch batch) {
        validateBatch(batch);
        try (var session = driver.session()) {
            session.executeWrite(transaction -> {
                writeBatch(transaction, batch);
                writeWatermark(transaction, batch, batch.nodes().size(), batch.relationships().size(),
                        digest(batch.nodes()), digest(batch.relationships()));
                return null;
            });
            metrics.record("project", "accepted", "java.neo4j", "neo4j", "none", null, 0L);
        } catch (RuntimeException failure) {
            metrics.record("project", "unavailable", "java.neo4j", "neo4j", "unavailable", null, 0L);
            throw failure;
        }
    }

    /** Replaces a learner graph, verifies the written count/digest, then advances its watermark atomically. */
    public ProjectionSnapshot replaceVerifiedSnapshot(ProjectionBatch batch) {
        validateBatch(batch);
        try (var session = driver.session()) {
            var result = session.executeWrite(transaction -> {
                deleteLearner(transaction, batch.learnerPartitionHash());
                writeBatch(transaction, batch);
                var actual = snapshot(transaction, batch.learnerPartitionHash());
                var expectedNodeDigest = digest(batch.nodes());
                var expectedRelationshipDigest = digest(batch.relationships());
                if (actual.nodeCount() != batch.nodes().size()
                        || actual.relationshipCount() != batch.relationships().size()
                        || !expectedNodeDigest.equals(actual.nodeDigest())
                        || !expectedRelationshipDigest.equals(actual.relationshipDigest())) {
                    throw new IllegalStateException("projection rebuild verification failed");
                }
                writeWatermark(transaction, batch, actual.nodeCount(), actual.relationshipCount(),
                        actual.nodeDigest(), actual.relationshipDigest());
                return new ProjectionSnapshot(actual.nodeCount(), actual.relationshipCount(), actual.nodeDigest(),
                        actual.relationshipDigest(), batch.checkpointEventId());
            });
            metrics.record("project", "accepted", "java.neo4j", "neo4j", "none", null, 0L);
            return result;
        } catch (RuntimeException failure) {
            metrics.record("project", "unavailable", "java.neo4j", "neo4j", "unavailable", null, 0L);
            throw failure;
        }
    }

    public ProjectionSnapshot snapshot(String learnerPartitionHash) {
        requirePartition(learnerPartitionHash);
        try (var session = driver.session()) {
            return session.executeRead(transaction -> snapshotWithWatermark(transaction, learnerPartitionHash));
        }
    }

    public void deleteLearner(String learnerPartitionHash) {
        requirePartition(learnerPartitionHash);
        try (var session = driver.session()) {
            session.executeWrite(transaction -> {
                deleteLearner(transaction, learnerPartitionHash);
                return null;
            });
            metrics.record("project", "accepted", "java.neo4j", "neo4j", "none", null, 0L);
        } catch (RuntimeException failure) {
            metrics.record("project", "unavailable", "java.neo4j", "neo4j", "unavailable", null, 0L);
            throw failure;
        }
    }

    public String digest(Collection<?> values) {
        Objects.requireNonNull(values, "values");
        var canonical = values.stream()
                .map(this::canonical)
                .sorted()
                .toList();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : canonical) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void writeBatch(TransactionContext transaction, ProjectionBatch batch) {
        if (!batch.nodes().isEmpty()) {
            transaction.run(PROJECT_NODES, Values.parameters("nodes", batch.nodes().stream()
                    .map(RelationProjectionWriter::nodeParameters).toList())).consume();
        }
        if (!batch.relationships().isEmpty()) {
            var projectedCount = transaction.run(PROJECT_RELATIONSHIPS, Values.parameters(
                    "learnerPartitionHash", batch.learnerPartitionHash(),
                    "relationships", batch.relationships().stream()
                            .map(RelationProjectionWriter::relationshipParameters).toList()))
                    .single().get("projectedCount").asLong();
            if (projectedCount != batch.relationships().size()) {
                throw new IllegalStateException("projection relationship endpoints are missing");
            }
        }
    }

    private void writeWatermark(
            TransactionContext transaction,
            ProjectionBatch batch,
            long nodeCount,
            long relationshipCount,
            String nodeDigest,
            String relationshipDigest) {
        transaction.run(UPSERT_WATERMARK, Values.parameters(
                "learnerPartitionHash", batch.learnerPartitionHash(),
                "eventId", batch.checkpointEventId().toString(),
                "nodeCount", nodeCount,
                "relationshipCount", relationshipCount,
                "nodeDigest", nodeDigest,
                "relationshipDigest", relationshipDigest)).consume();
    }

    private ProjectionSnapshot snapshotWithWatermark(TransactionContext transaction, String learnerPartitionHash) {
        var graph = snapshot(transaction, learnerPartitionHash);
        var watermarks = transaction.run("""
                        MATCH (watermark:ProjectionWatermark {learnerPartitionHash: $learnerPartitionHash})
                        RETURN watermark.eventId AS eventId
                        """, Values.parameters("learnerPartitionHash", learnerPartitionHash))
                .list();
        var watermark = watermarks.isEmpty() ? null : watermarks.getFirst();
        return new ProjectionSnapshot(graph.nodeCount(), graph.relationshipCount(), graph.nodeDigest(),
                graph.relationshipDigest(), watermark == null ? null : UUID.fromString(watermark.get("eventId").asString()));
    }

    private ProjectionSnapshot snapshot(TransactionContext transaction, String learnerPartitionHash) {
        var nodes = transaction.run("""
                        MATCH (node:MemoryProjection {learnerPartitionHash: $learnerPartitionHash})
                        RETURN node.assertionId AS assertionId, node.versionId AS versionId,
                               node.learnerPartitionHash AS learnerPartitionHash, node.type AS type,
                               node.effectiveFrom AS effectiveFrom, node.effectiveUntil AS effectiveUntil,
                               node.status AS status
                        """, Values.parameters("learnerPartitionHash", learnerPartitionHash))
                .list(record -> new ProjectionNode(
                        UUID.fromString(record.get("assertionId").asString()),
                        UUID.fromString(record.get("versionId").asString()),
                        record.get("learnerPartitionHash").asString(),
                        record.get("type").asString(),
                        record.get("effectiveFrom").asZonedDateTime().toInstant(),
                        instantOrNull(record.get("effectiveUntil")),
                        record.get("status").asString()));
        var relationships = transaction.run("""
                        MATCH (from:MemoryProjection {learnerPartitionHash: $learnerPartitionHash})
                              -[relation:MEMORY_RELATION]->
                              (to:MemoryProjection {learnerPartitionHash: $learnerPartitionHash})
                        RETURN relation.relationEventId AS relationEventId,
                               from.assertionId AS fromAssertionId, to.assertionId AS toAssertionId,
                               relation.effectiveFrom AS effectiveFrom, relation.effectiveUntil AS effectiveUntil
                        """, Values.parameters("learnerPartitionHash", learnerPartitionHash))
                .list(record -> new StoredProjectionRelation(
                        UUID.fromString(record.get("relationEventId").asString()),
                        UUID.fromString(record.get("fromAssertionId").asString()),
                        UUID.fromString(record.get("toAssertionId").asString()),
                        record.get("effectiveFrom").asZonedDateTime().toInstant(),
                        instantOrNull(record.get("effectiveUntil"))));
        return new ProjectionSnapshot(nodes.size(), relationships.size(), digest(nodes), digest(relationships), null);
    }

    private static Instant instantOrNull(org.neo4j.driver.Value value) {
        return value.isNull() ? null : value.asZonedDateTime().toInstant();
    }

    private void deleteLearner(TransactionContext transaction, String learnerPartitionHash) {
        transaction.run("""
                        MATCH (node:MemoryProjection {learnerPartitionHash: $learnerPartitionHash})
                        DETACH DELETE node
                        """, Values.parameters("learnerPartitionHash", learnerPartitionHash)).consume();
        transaction.run("""
                        MATCH (watermark:ProjectionWatermark {learnerPartitionHash: $learnerPartitionHash})
                        DELETE watermark
                        """, Values.parameters("learnerPartitionHash", learnerPartitionHash)).consume();
    }

    private static Map<String, Object> nodeParameters(ProjectionNode node) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("assertionId", node.assertionId().toString());
        parameters.put("versionId", node.versionId().toString());
        parameters.put("learnerPartitionHash", node.learnerPartitionHash());
        parameters.put("type", node.type());
        parameters.put("effectiveFrom", neo4jTemporal(node.effectiveFrom()));
        parameters.put("effectiveUntil", neo4jTemporal(node.effectiveUntil()));
        parameters.put("status", node.status());
        return parameters;
    }

    private static Map<String, Object> relationshipParameters(ProjectionRelation relationship) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("relationEventId", relationship.relationEventId().toString());
        parameters.put("fromAssertionId", relationship.fromAssertionId().toString());
        parameters.put("toAssertionId", relationship.toAssertionId().toString());
        parameters.put("effectiveFrom", neo4jTemporal(relationship.effectiveFrom()));
        parameters.put("effectiveUntil", neo4jTemporal(relationship.effectiveUntil()));
        return parameters;
    }

    private static java.time.OffsetDateTime neo4jTemporal(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private String canonical(Object value) {
        return switch (value) {
            case ProjectionNode node -> String.join("|", "node", node.assertionId().toString(), node.versionId().toString(),
                    node.learnerPartitionHash(), node.type(), node.effectiveFrom().toString(),
                    String.valueOf(node.effectiveUntil()), node.status());
            case ProjectionRelation relation -> String.join("|", "relation", relation.relationEventId().toString(),
                    relation.fromAssertionId().toString(), relation.toAssertionId().toString(),
                    relation.effectiveFrom().toString(), String.valueOf(relation.effectiveUntil()));
            case StoredProjectionRelation relation -> String.join("|", "relation", relation.relationEventId().toString(),
                    relation.fromAssertionId().toString(), relation.toAssertionId().toString(),
                    relation.effectiveFrom().toString(), String.valueOf(relation.effectiveUntil()));
            default -> throw new IllegalArgumentException("projection digest accepts only projection records");
        };
    }

    private record StoredProjectionRelation(
            UUID relationEventId,
            UUID fromAssertionId,
            UUID toAssertionId,
            Instant effectiveFrom,
            Instant effectiveUntil) {
    }

    private static void validateBatch(ProjectionBatch batch) {
        Objects.requireNonNull(batch, "batch");
        requirePartition(batch.learnerPartitionHash());
        for (var node : batch.nodes()) {
            if (!batch.learnerPartitionHash().equals(node.learnerPartitionHash())) {
                throw new IllegalArgumentException("node learner partition does not match batch");
            }
        }
    }

    private static void requirePartition(String learnerPartitionHash) {
        if (learnerPartitionHash == null || !learnerPartitionHash.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("learnerPartitionHash");
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,96}")) {
            throw new IllegalArgumentException(field);
        }
    }

    public record ProjectionBatch(
            String learnerPartitionHash,
            UUID checkpointEventId,
            List<ProjectionNode> nodes,
            List<ProjectionRelation> relationships) {

        public ProjectionBatch {
            requirePartition(learnerPartitionHash);
            Objects.requireNonNull(checkpointEventId, "checkpointEventId");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships"));
        }
    }

    public record ProjectionNode(
            UUID assertionId,
            UUID versionId,
            String learnerPartitionHash,
            String type,
            Instant effectiveFrom,
            Instant effectiveUntil,
            String status) {

        public ProjectionNode {
            Objects.requireNonNull(assertionId, "assertionId");
            Objects.requireNonNull(versionId, "versionId");
            requirePartition(learnerPartitionHash);
            requireIdentifier(type, "type");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            requireIdentifier(status, "status");
        }
    }

    public record ProjectionRelation(
            UUID relationEventId,
            UUID fromAssertionId,
            UUID toAssertionId,
            String relationType,
            String relationStatus,
            Instant effectiveFrom,
            Instant effectiveUntil) {

        public ProjectionRelation {
            Objects.requireNonNull(relationEventId, "relationEventId");
            Objects.requireNonNull(fromAssertionId, "fromAssertionId");
            Objects.requireNonNull(toAssertionId, "toAssertionId");
            if (fromAssertionId.equals(toAssertionId)) {
                throw new IllegalArgumentException("relation endpoints must differ");
            }
            requireIdentifier(relationType, "relationType");
            requireIdentifier(relationStatus, "relationStatus");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        }
    }

    public record ProjectionSnapshot(
            long nodeCount,
            long relationshipCount,
            String nodeDigest,
            String relationshipDigest,
            UUID watermarkEventId) {
    }
}
