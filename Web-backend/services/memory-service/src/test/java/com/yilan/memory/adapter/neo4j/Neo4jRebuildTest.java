package com.yilan.memory.adapter.neo4j;

import com.yilan.memory.support.Neo4jIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jRebuildTest extends Neo4jIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-21T10:00:00Z");

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void resetAuthority() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject CASCADE").update();
    }

    @Test
    void graphCanBeDroppedAndRebuiltFromOrderedPostgresAuthorityRows() {
        var learnerId = UUID.randomUUID();
        var learnerPartitionHash = "learner-partition-rebuild";
        var firstAssertion = insertAssertion(learnerId, "preference-key", "PREFERENCE");
        var secondAssertion = insertAssertion(learnerId, "mastery-key", "MASTERY");
        insertVersion(learnerId, firstAssertion, 1, "ACTIVE");
        insertVersion(learnerId, secondAssertion, 1, "ACTIVE");
        insertRelation(learnerId, firstAssertion, secondAssertion);
        var writer = new RelationProjectionWriter(driver);
        var rebuilder = new Neo4jRebuilder(jdbcClient, writer);

        var rebuilt = rebuilder.rebuildLearner(learnerId);
        writer.deleteLearner(learnerPartitionHash);
        var rebuiltAgain = rebuilder.rebuildLearner(learnerId);

        assertThat(rebuilt.nodeCount()).isEqualTo(2);
        assertThat(rebuilt.relationshipCount()).isEqualTo(1);
        assertThat(rebuiltAgain).isEqualTo(rebuilt);
        var snapshot = writer.snapshot(learnerPartitionHash);
        assertThat(snapshot.nodeCount()).isEqualTo(2);
        assertThat(snapshot.relationshipCount()).isEqualTo(1);
        assertThat(snapshot.watermarkEventId()).isNotNull();
    }

    @Test
    void rebuildProjectsOnlyTheLatestVersionForEachStableAssertion() {
        var learnerId = UUID.randomUUID();
        var learnerPartitionHash = "learner-partition-rebuild";
        var assertion = insertAssertion(learnerId, "versioned-preference", "PREFERENCE");
        insertVersion(learnerId, assertion, 1, "SUPERSEDED");
        var latestVersionId = insertVersion(learnerId, assertion, 2, "ACTIVE");
        var writer = new RelationProjectionWriter(driver);

        var rebuilt = new Neo4jRebuilder(jdbcClient, writer).rebuildLearner(learnerId);

        assertThat(rebuilt.nodeCount()).isOne();
        try (var session = driver.session()) {
            var versionIds = session.run("""
                            MATCH (node:MemoryProjection {learnerPartitionHash: $learnerPartitionHash})
                            RETURN node.versionId AS versionId
                            """, java.util.Map.of("learnerPartitionHash", learnerPartitionHash))
                    .list(record -> record.get("versionId").asString());
            assertThat(versionIds).containsExactly(latestVersionId.toString());
        }
    }

    private UUID insertAssertion(UUID learnerId, String assertionKey, String type) {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:learnerId, :partitionHash, 'ACTIVE', :now)
                        ON CONFLICT (learner_subject_id) DO NOTHING
                        """)
                .param("learnerId", learnerId).param("partitionHash", "learner-partition-rebuild")
                .param("now", Timestamp.from(NOW)).update();
        var assertionId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO memory_assertion (
                            memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at)
                        VALUES (:assertionId, :learnerId, :assertionKey, :type, :now)
                        """)
                .param("assertionId", assertionId).param("learnerId", learnerId)
                .param("assertionKey", assertionKey).param("type", type).param("now", Timestamp.from(NOW)).update();
        return assertionId;
    }

    private UUID insertVersion(UUID learnerId, UUID assertionId, long sequence, String status) {
        var versionId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO memory_version (
                            memory_version_id, memory_assertion_id, learner_subject_id, version_sequence, status,
                            value_json, valid_from, valid_until, recorded_at, recorded_until, confidence,
                            stability_score, privacy_level, consent_revision, created_at)
                        VALUES (:versionId, :assertionId, :learnerId, :sequence, :status, CAST('{}' AS jsonb),
                                :now, null, :now, null, 0.9, 0.8, 'STANDARD', 1, :now)
                        """)
                .param("versionId", versionId).param("assertionId", assertionId).param("learnerId", learnerId)
                .param("sequence", sequence).param("status", status).param("now", Timestamp.from(NOW)).update();
        return versionId;
    }

    private void insertRelation(UUID learnerId, UUID fromAssertionId, UUID toAssertionId) {
        jdbcClient.sql("""
                        INSERT INTO memory_relation_event (
                            memory_relation_event_id, learner_subject_id, from_memory_assertion_id,
                            to_memory_assertion_id, relation_type, relation_status, event_id,
                            event_schema_version, created_at)
                        VALUES (:relationId, :learnerId, :fromAssertionId, :toAssertionId, 'RELATES_TO',
                                'ACTIVE', null, null, :now)
                        """)
                .param("relationId", UUID.randomUUID()).param("learnerId", learnerId)
                .param("fromAssertionId", fromAssertionId).param("toAssertionId", toAssertionId)
                .param("now", Timestamp.from(NOW)).update();
    }
}
