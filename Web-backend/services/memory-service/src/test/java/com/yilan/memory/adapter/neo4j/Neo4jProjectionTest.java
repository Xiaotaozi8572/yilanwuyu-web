package com.yilan.memory.adapter.neo4j;

import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.support.Neo4jIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jProjectionTest extends Neo4jIntegrationTest {

    @Test
    void projectsAuthorityIdentifiersIdempotentlyAndAdvancesCheckpointAfterAcknowledgement() {
        var writer = new RelationProjectionWriter(driver);
        var learnerPartitionHash = "learner-partition-hash";
        var firstAssertionId = UUID.randomUUID();
        var secondAssertionId = UUID.randomUUID();
        var firstVersionId = UUID.randomUUID();
        var secondVersionId = UUID.randomUUID();
        var relationEventId = UUID.randomUUID();
        var checkpointEventId = UUID.randomUUID();
        var effectiveFrom = Instant.parse("2026-07-21T10:00:00Z");
        var batch = new RelationProjectionWriter.ProjectionBatch(
                learnerPartitionHash,
                checkpointEventId,
                List.of(
                        new RelationProjectionWriter.ProjectionNode(firstAssertionId, firstVersionId,
                                learnerPartitionHash, "PREFERENCE", effectiveFrom, null, "ACTIVE"),
                        new RelationProjectionWriter.ProjectionNode(secondAssertionId, secondVersionId,
                                learnerPartitionHash, "MASTERY", effectiveFrom, null, "ACTIVE")),
                List.of(new RelationProjectionWriter.ProjectionRelation(relationEventId, firstAssertionId,
                        secondAssertionId, "RELATES_TO", "ACTIVE", effectiveFrom, null)));

        writer.project(batch);
        writer.project(batch);

        var snapshot = writer.snapshot(learnerPartitionHash);
        assertThat(snapshot.nodeCount()).isEqualTo(2);
        assertThat(snapshot.relationshipCount()).isEqualTo(1);
        assertThat(snapshot.watermarkEventId()).isEqualTo(checkpointEventId);
        assertThat(snapshot.nodeDigest()).isEqualTo(writer.digest(batch.nodes()));
        assertThat(snapshot.relationshipDigest()).isEqualTo(writer.digest(batch.relationships()));
        try (var session = driver.session()) {
            var keys = session.run("MATCH (node:MemoryProjection) RETURN keys(node) AS keys")
                    .list(record -> record.get("keys").asList(value -> value.asString()));
            var allowedKeys = Set.of(
                    "assertionId", "versionId", "learnerPartitionHash", "type",
                    "effectiveFrom", "effectiveUntil", "status");
            assertThat(keys).allSatisfy(properties -> {
                assertThat(properties).contains(
                        "assertionId", "versionId", "learnerPartitionHash", "type", "effectiveFrom", "status");
                assertThat(properties).allMatch(allowedKeys::contains);
            });
            var relationshipKeys = session.run("MATCH ()-[relation:MEMORY_RELATION]->() RETURN keys(relation) AS keys")
                    .list(record -> record.get("keys").asList(value -> value.asString()));
            var allowedRelationshipKeys = Set.of("relationEventId", "effectiveFrom", "effectiveUntil");
            assertThat(relationshipKeys).isNotEmpty().allSatisfy(properties -> {
                assertThat(properties).contains("relationEventId", "effectiveFrom");
                assertThat(properties).allMatch(allowedRelationshipKeys::contains);
            });
        }
    }

    @Test
    void missingRelationshipEndpointRollsBackProjectionAndDoesNotAdvanceWatermark() {
        var writer = new RelationProjectionWriter(driver);
        var learnerPartitionHash = "missing-endpoint-partition";
        var existingAssertionId = UUID.randomUUID();
        var missingAssertionId = UUID.randomUUID();
        var effectiveFrom = Instant.parse("2026-07-21T10:00:00Z");
        var batch = new RelationProjectionWriter.ProjectionBatch(
                learnerPartitionHash,
                UUID.randomUUID(),
                List.of(new RelationProjectionWriter.ProjectionNode(existingAssertionId, UUID.randomUUID(),
                        learnerPartitionHash, "PREFERENCE", effectiveFrom, null, "ACTIVE")),
                List.of(new RelationProjectionWriter.ProjectionRelation(UUID.randomUUID(), existingAssertionId,
                        missingAssertionId, "RELATES_TO", "ACTIVE", effectiveFrom, null)));

        assertThatThrownBy(() -> writer.project(batch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relationship endpoints");

        var snapshot = writer.snapshot(learnerPartitionHash);
        assertThat(snapshot.nodeCount()).isZero();
        assertThat(snapshot.relationshipCount()).isZero();
        assertThat(snapshot.watermarkEventId()).isNull();
    }

    @Test
    void readerExcludesHintsWhenEitherEndpointIsInactiveOrOutsideTheAsOfInterval() {
        var writer = new RelationProjectionWriter(driver);
        var learnerPartitionHash = "reader-temporal-partition";
        var asOf = Instant.parse("2026-07-21T12:00:00Z");
        var activeFrom = UUID.randomUUID();
        var activeTo = UUID.randomUUID();
        var inactiveTo = UUID.randomUUID();
        var expiredTo = UUID.randomUUID();
        var futureTo = UUID.randomUUID();
        var expectedFromVersion = UUID.randomUUID();
        var expectedToVersion = UUID.randomUUID();
        writer.project(new RelationProjectionWriter.ProjectionBatch(
                learnerPartitionHash,
                UUID.randomUUID(),
                List.of(
                        new RelationProjectionWriter.ProjectionNode(activeFrom, expectedFromVersion,
                                learnerPartitionHash, "PREFERENCE", asOf.minusSeconds(60), null, "ACTIVE"),
                        new RelationProjectionWriter.ProjectionNode(activeTo, expectedToVersion,
                                learnerPartitionHash, "MASTERY", asOf.minusSeconds(60), null, "ACTIVE"),
                        new RelationProjectionWriter.ProjectionNode(inactiveTo, UUID.randomUUID(),
                                learnerPartitionHash, "MASTERY", asOf.minusSeconds(60), null, "SUPERSEDED"),
                        new RelationProjectionWriter.ProjectionNode(expiredTo, UUID.randomUUID(),
                                learnerPartitionHash, "MASTERY", asOf.minusSeconds(120), asOf.minusSeconds(1), "ACTIVE"),
                        new RelationProjectionWriter.ProjectionNode(futureTo, UUID.randomUUID(),
                                learnerPartitionHash, "MASTERY", asOf.plusSeconds(1), null, "ACTIVE")),
                List.of(
                        relation(activeFrom, activeTo, asOf.minusSeconds(30)),
                        relation(activeFrom, inactiveTo, asOf.minusSeconds(30)),
                        relation(activeFrom, expiredTo, asOf.minusSeconds(30)),
                        relation(activeFrom, futureTo, asOf.minusSeconds(30)))));
        var reader = new RelationProjectionReader(driver, new GraphCircuitBreaker());
        var query = new RetrievalChannel.AuthorizedMemoryQuery(
                learnerPartitionHash, "session-a", asOf, Set.of(MemoryType.PREFERENCE), Set.of(),
                "question", "TEACHING", null, 8);

        var result = reader.read(query, Duration.ofSeconds(5));

        assertThat(result.available()).isTrue();
        assertThat(result.hints()).singleElement().satisfies(hint -> {
            assertThat(hint.fromAssertionId()).isEqualTo(activeFrom);
            assertThat(hint.fromVersionId()).isEqualTo(expectedFromVersion);
            assertThat(hint.toAssertionId()).isEqualTo(activeTo);
            assertThat(hint.toVersionId()).isEqualTo(expectedToVersion);
        });
    }

    private static RelationProjectionWriter.ProjectionRelation relation(
            UUID fromAssertionId,
            UUID toAssertionId,
            Instant effectiveFrom) {
        return new RelationProjectionWriter.ProjectionRelation(
                UUID.randomUUID(), fromAssertionId, toAssertionId, "RELATES_TO", "ACTIVE", effectiveFrom, null);
    }
}
