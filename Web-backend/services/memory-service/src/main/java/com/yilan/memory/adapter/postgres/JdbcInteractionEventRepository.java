package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.event.SubmitMemoryEventsUseCase.InsertOutcome;
import com.yilan.memory.application.event.SubmitMemoryEventsUseCase.InteractionEventRepository;
import com.yilan.memory.application.privacy.EncryptedPayload;
import com.yilan.memory.domain.event.InteractionEvent;
import com.yilan.memory.domain.identity.LearnerIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * JDBC implementation of the global (event_id, schema_version) idempotency
 * boundary. It never derives a learner identity from event input.
 */
@Repository
public class JdbcInteractionEventRepository implements InteractionEventRepository {

    private final JdbcClient jdbcClient;

    public JdbcInteractionEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public InsertOutcome insertIfAbsent(LearnerIdentity identity, InteractionEvent event) {
        var protection = protectedSourceMetadata(event);
        var inserted = jdbcClient.sql("""
                        INSERT INTO interaction_event (
                            event_id, schema_version, learner_subject_id, session_id, event_type,
                            source_kind, occurred_at, received_at, privacy_level, consent_revision,
                            payload_ciphertext, payload_digest, trace_id, payload_key_reference,
                            payload_nonce, payload_algorithm, payload_crypto_version)
                        SELECT :eventId, :schemaVersion, learner_subject_id, :sessionId, :eventType,
                               :sourceKind, :occurredAt, :receivedAt, :privacyLevel, :consentRevision,
                               :payloadCiphertext, :payloadDigest, :traceId, :keyReference,
                               :nonce, :algorithm, :cryptoVersion
                        FROM learner_subject
                        WHERE subject_hash = :subjectHash AND status = 'ACTIVE'
                        ON CONFLICT DO NOTHING
                        """)
                .param("eventId", event.eventId())
                .param("schemaVersion", event.schemaVersion())
                .param("sessionId", identity.sessionId())
                .param("eventType", event.eventType())
                .param("sourceKind", event.sourceKind().name())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("receivedAt", Timestamp.from(event.receivedAt()))
                .param("privacyLevel", event.privacyLevel().name())
                .param("consentRevision", event.consentRevision())
                .param("payloadCiphertext", event.payloadCiphertext())
                .param("payloadDigest", event.payloadDigest())
                .param("traceId", event.traceId())
                .param("keyReference", protection == null ? null : protection.keyReference())
                .param("nonce", protection == null ? null : protection.nonce())
                .param("algorithm", protection == null ? null : EncryptedPayload.ALGORITHM)
                .param("cryptoVersion", protection == null ? null : EncryptedPayload.CRYPTO_VERSION)
                .param("subjectHash", identity.subjectHash())
                .update();
        if (inserted == 1) {
            return InsertOutcome.INSERTED;
        }

        var existingSubjectHash = jdbcClient.sql("""
                        SELECT subject.subject_hash
                        FROM interaction_event event
                        JOIN learner_subject subject ON subject.learner_subject_id = event.learner_subject_id
                        WHERE event.event_id = :eventId AND event.schema_version = :schemaVersion
                        """)
                .param("eventId", event.eventId())
                .param("schemaVersion", event.schemaVersion())
                .query(String.class)
                .optional();
        if (existingSubjectHash.isEmpty()) {
            throw new SubjectUnavailableException();
        }
        return existingSubjectHash.orElseThrow().equals(identity.subjectHash())
                ? InsertOutcome.DUPLICATE
                : InsertOutcome.CROSS_SUBJECT;
    }

    private static EncryptedPayload protectedSourceMetadata(InteractionEvent event) {
        if (event.privacyLevel() != InteractionEvent.PrivacyLevel.SENSITIVE
                && event.privacyLevel() != InteractionEvent.PrivacyLevel.HIGH) {
            return null;
        }
        try {
            return EncryptedPayload.parse(event.payloadCiphertext());
        } catch (SecurityException rejected) {
            throw new SecurityException("protected interaction event requires an AES-256-GCM envelope");
        }
    }

    public static final class SubjectUnavailableException extends RuntimeException {

        public SubjectUnavailableException() {
            super("authenticated active learner subject is unavailable");
        }
    }
}
