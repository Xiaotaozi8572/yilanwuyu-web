package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.privacy.InteractionPayloadKeyEnvelopeStore;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JdbcInteractionPayloadKeyEnvelopeStoreTest extends PostgresIntegrationTest {

    @Autowired private JdbcClient jdbc;
    @Autowired private JdbcInteractionPayloadKeyEnvelopeStore store;

    @AfterEach void cleanUp() { jdbc.sql("TRUNCATE TABLE learner_subject CASCADE").update(); }

    @Test
    void persistsOnlyTheEventBoundEnvelope() {
        var learner = UUID.randomUUID();
        var event = UUID.randomUUID();
        var subject = "retention-source-subject";
        jdbc.sql("INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at) VALUES (:id,:subject,'ACTIVE',CURRENT_TIMESTAMP)")
                .param("id", learner).param("subject", subject).update();
        jdbc.sql("INSERT INTO interaction_event (event_id,schema_version,learner_subject_id,session_id,event_type,source_kind,occurred_at,received_at,privacy_level,consent_revision,payload_ciphertext,payload_digest,trace_id) VALUES (:event,'v1',:learner,'session','PREFERENCE','GENERAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'STANDARD',0,:payload,:digest,'trace')")
                .param("event", event).param("learner", learner).param("payload", new byte[] {1}).param("digest", "0".repeat(64)).update();

        var envelope = new InteractionPayloadKeyEnvelopeStore.Envelope(
                "k-" + "a".repeat(32), new byte[60], "k-" + "b".repeat(32), Instant.parse("2026-07-22T00:00:00Z"));
        store.storeIfAbsent(subject, event, "v1", envelope);

        assertThat(store.load(subject, event, "v1")).hasValueSatisfying(stored -> {
            assertThat(stored.dekReference()).isEqualTo(envelope.dekReference());
            assertThat(stored.wrappedDek()).isEqualTo(envelope.wrappedDek());
        });
    }

    @Test
    void doesNotRecordRetentionErasureForLegacyEventWithoutAnEnvelope() {
        var learner = UUID.randomUUID();
        var event = UUID.randomUUID();
        var subject = "legacy-retention-subject";
        jdbc.sql("INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at) VALUES (:id,:subject,'ACTIVE',CURRENT_TIMESTAMP)")
                .param("id", learner).param("subject", subject).update();
        jdbc.sql("INSERT INTO interaction_event (event_id,schema_version,learner_subject_id,session_id,event_type,source_kind,occurred_at,received_at,privacy_level,consent_revision,payload_ciphertext,payload_digest,trace_id) VALUES (:event,'v1',:learner,'session','PREFERENCE','GENERAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'STANDARD',0,:payload,:digest,'trace')")
                .param("event", event).param("learner", learner).param("payload", new byte[] {1}).param("digest", "0".repeat(64)).update();

        assertThat(store.eraseForRetention(subject, event, "v1", "retention-v1",
                Instant.parse("2026-07-22T00:00:00Z"), Instant.parse("2026-07-22T00:01:00Z")))
                .isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM retention_erasure WHERE target_kind='INTERACTION_PAYLOAD' AND target_id=:event")
                .param("event", event).query(Long.class).single()).isZero();
    }
}
