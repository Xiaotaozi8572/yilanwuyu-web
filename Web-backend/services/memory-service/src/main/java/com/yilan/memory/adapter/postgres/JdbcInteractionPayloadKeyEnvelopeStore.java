package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.privacy.InteractionPayloadKeyEnvelopeStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-only source-envelope authority store. It never receives plaintext. */
@Repository
public class JdbcInteractionPayloadKeyEnvelopeStore implements InteractionPayloadKeyEnvelopeStore {

    private final JdbcClient jdbc;

    public JdbcInteractionPayloadKeyEnvelopeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Envelope> load(String subjectHash, UUID eventId, String schemaVersion) {
        InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
        return jdbc.sql("""
                        SELECT envelope.dek_key_reference, envelope.wrapped_dek,
                               envelope.wrap_key_reference, envelope.created_at
                        FROM interaction_payload_key_envelope envelope
                        JOIN learner_subject subject ON subject.learner_subject_id = envelope.learner_subject_id
                        WHERE subject.subject_hash = :subject
                          AND envelope.event_id = :event AND envelope.schema_version = :schema
                        """)
                .param("subject", subjectHash).param("event", eventId).param("schema", schemaVersion)
                .query((rs, ignored) -> new Envelope(rs.getString(1), rs.getBytes(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .optional();
    }

    @Override
    public void storeIfAbsent(String subjectHash, UUID eventId, String schemaVersion, Envelope envelope) {
        InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
        envelope = java.util.Objects.requireNonNull(envelope, "envelope");
        jdbc.sql("""
                        INSERT INTO interaction_payload_key_envelope (
                            event_id, schema_version, learner_subject_id, dek_key_reference,
                            wrapped_dek, wrap_key_reference, created_at)
                        SELECT event.event_id, event.schema_version, event.learner_subject_id,
                               :dek, :wrapped, :wrap, :created
                        FROM interaction_event event
                        JOIN learner_subject subject ON subject.learner_subject_id = event.learner_subject_id
                        WHERE subject.subject_hash = :subject
                          AND subject.status = 'ACTIVE'
                          AND event.event_id = :event AND event.schema_version = :schema
                        ON CONFLICT (event_id, schema_version) DO NOTHING
                        """)
                .param("subject", subjectHash).param("event", eventId).param("schema", schemaVersion)
                .param("dek", envelope.dekReference()).param("wrapped", envelope.wrappedDek())
                .param("wrap", envelope.wrappingKeyReference()).param("created", Timestamp.from(envelope.createdAt()))
                .update();
    }

    @Override
    @Transactional
    public boolean eraseForRetention(
            String subjectHash, UUID eventId, String schemaVersion,
            String policyVersion, Instant dueAt, Instant executedAt) {
        InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
        if (policyVersion == null || !policyVersion.matches("[A-Za-z0-9._-]{1,64}")
                || dueAt == null || executedAt == null) {
            throw new IllegalArgumentException("retention evidence");
        }
        var envelopeSubject = jdbc.sql("""
                        SELECT envelope.learner_subject_id
                        FROM interaction_payload_key_envelope envelope
                        JOIN learner_subject subject ON subject.learner_subject_id = envelope.learner_subject_id
                        WHERE subject.subject_hash = :subject
                          AND envelope.event_id = :event AND envelope.schema_version = :schema
                        FOR UPDATE OF envelope
                        """)
                .param("subject", subjectHash).param("event", eventId).param("schema", schemaVersion)
                .query(UUID.class).optional();
        if (envelopeSubject.isEmpty()) {
            return false;
        }
        var inserted = jdbc.sql("""
                        INSERT INTO retention_erasure (
                            retention_erasure_id, target_kind, target_id, event_schema_version,
                            learner_subject_id, policy_version, due_at, executed_at)
                        SELECT :id, 'INTERACTION_PAYLOAD', event.event_id, event.schema_version,
                               event.learner_subject_id, :policy, :due, :executed
                        FROM interaction_event event
                        JOIN learner_subject subject ON subject.learner_subject_id = event.learner_subject_id
                        WHERE subject.subject_hash = :subject
                          AND event.event_id = :event AND event.schema_version = :schema
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("subject", subjectHash).param("event", eventId)
                .param("schema", schemaVersion).param("policy", policyVersion)
                .param("due", Timestamp.from(dueAt)).param("executed", Timestamp.from(executedAt)).update();
        var deleted = jdbc.sql("""
                        DELETE FROM interaction_payload_key_envelope envelope
                        USING learner_subject subject
                        WHERE envelope.learner_subject_id = subject.learner_subject_id
                          AND subject.subject_hash = :subject
                          AND envelope.event_id = :event AND envelope.schema_version = :schema
                        """)
                .param("subject", subjectHash).param("event", eventId).param("schema", schemaVersion).update();
        return deleted == 1 || inserted == 1;
    }

    @Override
    @Transactional
    public int eraseAllForSubject(String subjectHash) {
        if (subjectHash == null || subjectHash.isBlank()) {
            throw new IllegalArgumentException("subjectHash");
        }
        return jdbc.sql("""
                        DELETE FROM interaction_payload_key_envelope envelope
                        USING learner_subject subject
                        WHERE envelope.learner_subject_id = subject.learner_subject_id
                          AND subject.subject_hash = :subject
                          AND subject.status = 'DISABLED'
                        """).param("subject", subjectHash).update();
    }
}
