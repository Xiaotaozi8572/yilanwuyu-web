package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.privacy.RetentionRepository;
import org.junit.jupiter.api.AfterEach;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JdbcRetentionRepositoryTest extends PostgresIntegrationTest {

    @Autowired private JdbcRetentionRepository repository;
    @Autowired private JdbcClient jdbc;
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @AfterEach
    void cleanUp() {
        jdbc.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }

    @Test
    void exposesOnlyFiniteAuthorityMaintenanceOperations() {
        assertThat(repository).isInstanceOf(RetentionRepository.class);
    }

    @Test
    void writesSourceErasureEvidenceBeforeRemovingOnlyTheDueEnvelope() {
        var learner = learner("retention-source-subject");
        var event = event(learner, NOW.minusSeconds(31L * 24 * 60 * 60));
        envelope(learner, event);

        assertThat(repository.eraseDueSourcePayloads(NOW, 8, "retention-v1")).isEqualTo(1);
        assertThat(repository.eraseDueSourcePayloads(NOW, 8, "retention-v1")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM interaction_payload_key_envelope WHERE event_id=:event")
                .param("event", event).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM retention_erasure WHERE target_kind='INTERACTION_PAYLOAD' AND target_id=:event")
                .param("event", event).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void deletesOnlyDueAuditRowsAfterTheirImmutableProof() {
        var learner = learner("retention-audit-subject");
        var audit = UUID.randomUUID();
        jdbc.sql("INSERT INTO memory_audit_event (memory_audit_event_id,learner_subject_id,audit_type,actor_type,correlation_id,redacted_metadata,created_at) VALUES (:id,:learner,'RETENTION_TEST','SYSTEM','audit', '{}'::jsonb,:at)")
                .param("id", audit).param("learner", learner).param("at", Timestamp.from(NOW.minusSeconds(366L * 24 * 60 * 60))).update();

        assertThat(repository.eraseDueAuditEvents(NOW, 8, "retention-v1")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM memory_audit_event WHERE memory_audit_event_id=:id")
                .param("id", audit).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM retention_erasure WHERE target_kind='AUDIT_EVENT' AND target_id=:id")
                .param("id", audit).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void expiresGovernedMemoryOnceAndEmitsOnlyIdentifierPurge() {
        var learner = learner("retention-memory-subject");
        var fixture = head(learner, "MASTERY", NOW.minusSeconds(1));

        assertThat(repository.expireDueMemory(NOW, 8)).isEqualTo(1);
        assertThat(repository.expireDueMemory(NOW, 8)).isZero();
        assertThat(jdbc.sql("SELECT status FROM memory_head_projection WHERE memory_assertion_id=:id")
                .param("id", fixture.assertion()).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("SELECT memory_epoch FROM learner_epoch WHERE learner_subject_id=:id")
                .param("id", learner).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT event_type FROM transactional_outbox WHERE aggregate_id=:id")
                .param("id", fixture.assertion()).query(String.class).single()).isEqualTo("MEMORY_PROJECTION_PURGE");
    }

    @Test
    void stalesPreferenceWithoutAutoConfirmation() {
        var learner = learner("retention-preference-subject");
        var fixture = head(learner, "PREFERENCE", NOW.minusSeconds(1));

        assertThat(repository.staleDuePreferences(NOW, 8)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM memory_head_projection WHERE memory_assertion_id=:id")
                .param("id", fixture.assertion()).query(String.class).single()).isEqualTo("STALE");
        assertThat(jdbc.sql("SELECT count(*) FROM memory_transition WHERE memory_assertion_id=:id AND reason_code='USER_CONFIRMED'")
                .param("id", fixture.assertion()).query(Long.class).single()).isZero();
    }

    @Test
    void expiryBatchLeavesPreferencesForTheExplicitReconfirmationScheduler() {
        var learner = learner("retention-preference-isolation-subject");
        var fixture = head(learner, "PREFERENCE", NOW.minusSeconds(1));

        assertThat(repository.expireDueMemory(NOW, 8)).isZero();
        assertThat(jdbc.sql("SELECT status FROM memory_head_projection WHERE memory_assertion_id=:id")
                .param("id", fixture.assertion()).query(String.class).single()).isEqualTo("ACTIVE");
    }

    private UUID learner(String subject) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO learner_subject (learner_subject_id,subject_hash,status,created_at) VALUES (:id,:subject,'ACTIVE',:at)")
                .param("id", id).param("subject", subject).param("at", Timestamp.from(NOW)).update();
        return id;
    }

    private UUID event(UUID learner, Instant occurredAt) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO interaction_event (event_id,schema_version,learner_subject_id,session_id,event_type,source_kind,occurred_at,received_at,privacy_level,consent_revision,payload_ciphertext,payload_digest,trace_id) VALUES (:id,'v1',:learner,'session','PREFERENCE','GENERAL',:occurred,:received,'STANDARD',0,:payload,:digest,'trace')")
                .param("id", id).param("learner", learner).param("occurred", Timestamp.from(occurredAt)).param("received", Timestamp.from(NOW))
                .param("payload", new byte[] {1}).param("digest", "1".repeat(64)).update();
        return id;
    }

    private void envelope(UUID learner, UUID event) {
        jdbc.sql("INSERT INTO interaction_payload_key_envelope (event_id,schema_version,learner_subject_id,dek_key_reference,wrapped_dek,wrap_key_reference,created_at) VALUES (:event,'v1',:learner,:dek,:wrapped,:wrap,:at)")
                .param("event", event).param("learner", learner).param("dek", "k-" + "a".repeat(32))
                .param("wrapped", new byte[60]).param("wrap", "k-" + "b".repeat(32)).param("at", Timestamp.from(NOW)).update();
    }

    private HeadFixture head(UUID learner, String type, Instant validUntil) {
        var assertion = UUID.randomUUID(); var version = UUID.randomUUID();
        jdbc.sql("INSERT INTO memory_assertion (memory_assertion_id,learner_subject_id,assertion_key,memory_type,created_at) VALUES (:assertion,:learner,:key,:type,:at)")
                .param("assertion", assertion).param("learner", learner).param("key", "retention-" + assertion).param("type", type).param("at", Timestamp.from(NOW)).update();
        jdbc.sql("INSERT INTO memory_version (memory_version_id,memory_assertion_id,learner_subject_id,version_sequence,status,value_json,valid_from,valid_until,recorded_at,confidence,stability_score,privacy_level,consent_revision,created_at) VALUES (:version,:assertion,:learner,1,'ACTIVE','{}'::jsonb,:from,:until,:from,0.9,0.9,'STANDARD',0,:from)")
                .param("version", version).param("assertion", assertion).param("learner", learner)
                .param("from", Timestamp.from(NOW.minusSeconds(100))).param("until", Timestamp.from(validUntil)).update();
        jdbc.sql("INSERT INTO memory_head_projection (memory_assertion_id,learner_subject_id,memory_version_id,status,memory_epoch,updated_at) VALUES (:assertion,:learner,:version,'ACTIVE',0,:at)")
                .param("assertion", assertion).param("learner", learner).param("version", version).param("at", Timestamp.from(NOW)).update();
        return new HeadFixture(assertion);
    }

    private record HeadFixture(UUID assertion) { }
}
