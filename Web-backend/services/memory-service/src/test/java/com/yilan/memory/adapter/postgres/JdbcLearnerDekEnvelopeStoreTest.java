package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.privacy.LearnerDekEnvelopeStore;
import com.yilan.memory.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JdbcLearnerDekEnvelopeStoreTest extends PostgresIntegrationTest {

    @Autowired private JdbcLearnerDekEnvelopeStore store;
    @Autowired private JdbcClient jdbc;

    @AfterEach void cleanUp() { jdbc.sql("TRUNCATE TABLE learner_subject CASCADE").update(); }

    @Test
    void persistsOnlyOpaqueEnvelopeFieldsAtomicallyAndDestroysThem() {
        var subject = "e".repeat(43);
        jdbc.sql("INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at) VALUES (:id,:subject,'ACTIVE',CURRENT_TIMESTAMP)")
                .param("id", UUID.randomUUID()).param("subject", subject).update();
        var first = envelope("1");
        var racing = envelope("2");

        store.storeIfAbsent(subject, first);
        store.storeIfAbsent(subject, racing);

        var stored = store.load(subject).orElseThrow();
        assertThat(stored.dekReference()).isEqualTo(first.dekReference());
        assertThat(stored.wrappingKeyReference()).isEqualTo(first.wrappingKeyReference());
        assertThat(stored.wrappedDek()).isEqualTo(first.wrappedDek());
        assertThat(stored.wrappedDek()).hasSize(60).doesNotContain((byte) 0);

        store.destroy(subject);
        assertThat(store.load(subject)).isEmpty();
    }

    private static LearnerDekEnvelopeStore.Envelope envelope(String fill) {
        return new LearnerDekEnvelopeStore.Envelope("k-" + fill.repeat(32), (fill.repeat(60)).getBytes(),
                "k-" + "a".repeat(32), Instant.parse("2026-07-22T00:00:00Z"));
    }
}
