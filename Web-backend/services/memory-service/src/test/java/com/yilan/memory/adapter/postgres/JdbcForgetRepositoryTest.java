package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.privacy.LearnerDekEnvelopeStore;
import com.yilan.memory.application.privacy.DeletionVerifier;
import com.yilan.memory.application.management.MemoryManagementRepository;
import com.yilan.memory.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JdbcForgetRepositoryTest extends PostgresIntegrationTest {
    @Autowired private JdbcForgetRepository repository;
    @Autowired private JdbcLearnerDekEnvelopeStore envelopes;
    @Autowired private JdbcClient jdbc;
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @AfterEach void cleanUp() { jdbc.sql("TRUNCATE TABLE learner_subject CASCADE").update(); }

    @Test
    void fullForgetIsIdempotentDisablesSubjectAdvancesBothEpochsAndCryptoShredsEnvelope() {
        var fixture = fixture("f".repeat(43));
        envelopes.storeIfAbsent(fixture.subject(), envelope());
        insertSourceEnvelope(fixture);

        var first = repository.requestFull(fixture.subject(), "i".repeat(16), NOW);
        var replay = repository.requestFull(fixture.subject(), "k".repeat(16), NOW.plusSeconds(1));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.sql("SELECT status FROM learner_subject WHERE subject_hash=:subject").param("subject", fixture.subject()).query(String.class).single()).isEqualTo("DISABLED");
        assertThat(jdbc.sql("SELECT memory_epoch || ':' || consent_epoch FROM learner_epoch WHERE learner_subject_id=:id").param("id", fixture.subjectId()).query(String.class).single()).isEqualTo("1:1");
        assertThat(envelopes.load(fixture.subject())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM interaction_payload_key_envelope WHERE learner_subject_id=:id")
                .param("id", fixture.subjectId()).query(Long.class).single()).isZero();
        assertThat(repository.verify(first.requestId()).closed()).isFalse();
        assertThat(repository.completeFull(first.requestId(), NOW.plusSeconds(2))).isTrue();
        assertThat(jdbc.sql("SELECT payload_references_removed FROM forget_execution WHERE request_id=:request")
                .param("request", first.requestId()).query(Long.class).single()).isEqualTo(1L);
        var verification = repository.verify(first.requestId());
        assertThat(verification.closed()).isTrue();
        assertThat(verification.payloadReferencesRemaining()).isZero();
        assertThat(verification.scope()).isEqualTo(DeletionVerifier.Scope.FULL);
    }

    @Test
    void singleForgetTombstonesOnlyItsAssertionAndRetainsSubjectEnvelope() {
        var fixture = fixture("g".repeat(43));
        envelopes.storeIfAbsent(fixture.subject(), envelope());

        var receipt = repository.requestSingle(fixture.subject(), fixture.assertionId(), "j".repeat(16), NOW);

        assertThat(jdbc.sql("SELECT status FROM learner_subject WHERE learner_subject_id=:id").param("id", fixture.subjectId()).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(envelopes.load(fixture.subject())).isPresent();
        assertThat(jdbc.sql("SELECT status FROM memory_head_projection WHERE memory_assertion_id=:id").param("id", fixture.assertionId()).query(String.class).single()).isEqualTo("DISABLED");
        var verification = repository.verify(receipt.requestId());
        assertThat(verification.closed()).isTrue();
        assertThat(verification.scope()).isEqualTo(DeletionVerifier.Scope.ASSERTION);
        assertThat(verification.cryptoShredded()).isFalse();
    }

    @Test
    void fullVerificationSeparatesRetainedStandardReferencesFromShreddedProtectedReferences() {
        var fixture = fixture("p".repeat(43));
        envelopes.storeIfAbsent(fixture.subject(), envelope());
        var standardEvent = UUID.randomUUID();
        var standardAssertion = UUID.randomUUID();
        var standardVersion = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO interaction_event (event_id, schema_version, learner_subject_id, session_id, event_type,
                    occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext, payload_digest, trace_id)
                VALUES (:event, 'v1', :learner, 'standard', 'STANDARD_EVENT', :at, :at, 'STANDARD', 0, :payload, :digest, 'trace')
                """).param("event", standardEvent).param("learner", fixture.subjectId()).param("at", Timestamp.from(NOW))
                .param("payload", new byte[] {7}).param("digest", "7".repeat(64)).update();
        jdbc.sql("""
                INSERT INTO memory_candidate (candidate_id, learner_subject_id, event_id, event_schema_version, memory_type,
                    status, privacy_level, candidate_ciphertext, candidate_digest, source_count, created_at)
                VALUES (:candidate, :learner, :event, 'v1', 'PREFERENCE', 'PENDING', 'STANDARD', :payload, :digest, 1, :at)
                """).param("candidate", UUID.randomUUID()).param("learner", fixture.subjectId()).param("event", standardEvent)
                .param("payload", new byte[] {8}).param("digest", "8".repeat(64)).param("at", Timestamp.from(NOW)).update();
        jdbc.sql("""
                INSERT INTO memory_assertion (memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at)
                VALUES (:assertion, :learner, 'standard-assertion', 'PREFERENCE', :at)
                """).param("assertion", standardAssertion).param("learner", fixture.subjectId()).param("at", Timestamp.from(NOW)).update();
        jdbc.sql("""
                INSERT INTO memory_version (memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                    status, value_json, valid_from, recorded_at, confidence, stability_score, privacy_level, consent_revision, created_at)
                VALUES (:version, :assertion, :learner, 1, 'ACTIVE', '{}'::jsonb, :at, :at, 0.9, 0.9, 'STANDARD', 0, :at)
                """).param("version", standardVersion).param("assertion", standardAssertion).param("learner", fixture.subjectId()).param("at", Timestamp.from(NOW)).update();
        jdbc.sql("""
                INSERT INTO interaction_event (event_id, schema_version, learner_subject_id, session_id, event_type,
                    occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext, payload_digest, trace_id)
                VALUES (:event, 'v1', :learner, 'standard', 'EMPTY_STANDARD_EVENT', :at, :at, 'STANDARD', 0, null, :digest, 'trace')
                """).param("event", UUID.randomUUID()).param("learner", fixture.subjectId()).param("at", Timestamp.from(NOW))
                .param("digest", "0".repeat(64)).update();
        var emptyStandardAssertion = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO memory_assertion (memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at)
                VALUES (:assertion, :learner, 'empty-standard-assertion', 'PREFERENCE', :at)
                """).param("assertion", emptyStandardAssertion).param("learner", fixture.subjectId()).param("at", Timestamp.from(NOW)).update();
        jdbc.sql("""
                INSERT INTO memory_version (memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                    status, value_json, valid_from, recorded_at, confidence, stability_score, privacy_level, consent_revision, created_at)
                VALUES (:version, :assertion, :learner, 1, 'ACTIVE', null, :at, :at, 0.9, 0.9, 'STANDARD', 0, :at)
                """).param("version", UUID.randomUUID()).param("assertion", emptyStandardAssertion)
                .param("learner", fixture.subjectId()).param("at", Timestamp.from(NOW)).update();

        var receipt = repository.requestFull(fixture.subject(), "q".repeat(16), NOW);
        assertThat(repository.completeFull(receipt.requestId(), NOW.plusSeconds(1))).isTrue();

        var verification = repository.verify(receipt.requestId());
        assertThat(verification.closed()).isTrue();
        assertThat(verification.cryptoShredded()).isTrue();
        assertThat(verification.payloadReferencesRemaining()).isZero();
        assertThat(verification.retainedNonProtectedPayloadReferences()).isEqualTo(3);
    }

    @Test
    void singleForgetTreatsUnknownOrAnotherSubjectsAssertionAsNotFound() {
        var fixture = fixture("n".repeat(43));
        var other = fixture("o".repeat(43));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                repository.requestSingle(fixture.subject(), UUID.randomUUID(), "n".repeat(16), NOW))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                repository.requestSingle(fixture.subject(), other.assertionId(), "o".repeat(16), NOW))
                .isInstanceOf(MemoryManagementRepository.MemoryNotFoundException.class);
    }

    @Test
    void idempotencyKeyCannotBeReusedForAnotherForgetScope() {
        var fixture = fixture("h".repeat(43));
        repository.requestSingle(fixture.subject(), fixture.assertionId(), "k".repeat(16), NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                repository.requestFull(fixture.subject(), "k".repeat(16), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private Fixture fixture(String subject) {
        var subjectId = UUID.randomUUID(); var assertionId = UUID.randomUUID(); var versionId = UUID.randomUUID();
        var at = Timestamp.from(NOW);
        jdbc.sql("INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at) VALUES (:id,:subject,'ACTIVE',:at)").param("id", subjectId).param("subject", subject).param("at", at).update();
        jdbc.sql("INSERT INTO learner_epoch (learner_subject_id,memory_epoch,consent_epoch,updated_at) VALUES (:id,0,0,:at)").param("id", subjectId).param("at", at).update();
        jdbc.sql("INSERT INTO memory_assertion (memory_assertion_id,learner_subject_id,assertion_key,memory_type,created_at) VALUES (:assertion,:subject,:key,'PREFERENCE',:at)").param("assertion", assertionId).param("subject", subjectId).param("key", "key-" + assertionId).param("at", at).update();
        jdbc.sql("INSERT INTO memory_version (memory_version_id,memory_assertion_id,learner_subject_id,version_sequence,status,value_json,protected_value_ciphertext,protected_value_nonce,protected_value_key_reference,protected_value_algorithm,protected_value_crypto_version,valid_from,recorded_at,confidence,stability_score,privacy_level,consent_revision,created_at) VALUES (:version,:assertion,:subject,1,'ACTIVE',null,:ciphertext,:nonce,:keyReference,'AES-256-GCM',1,:at,:at,0.9,0.9,'SENSITIVE',0,:at)")
                .param("version", versionId).param("assertion", assertionId).param("subject", subjectId)
                .param("ciphertext", new byte[] {1, 2, 3}).param("nonce", new byte[12])
                .param("keyReference", "k-" + "a".repeat(32)).param("at", at).update();
        jdbc.sql("INSERT INTO memory_head_projection (memory_assertion_id,learner_subject_id,memory_version_id,status,memory_epoch,updated_at) VALUES (:assertion,:subject,:version,'ACTIVE',0,:at)").param("assertion", assertionId).param("subject", subjectId).param("version", versionId).param("at", at).update();
        return new Fixture(subject, subjectId, assertionId);
    }
    private static LearnerDekEnvelopeStore.Envelope envelope() { return new LearnerDekEnvelopeStore.Envelope("k-" + "1".repeat(32), "x".repeat(60).getBytes(), "k-" + "2".repeat(32), NOW); }
    private void insertSourceEnvelope(Fixture fixture) {
        var event = UUID.randomUUID();
        jdbc.sql("INSERT INTO interaction_event (event_id, schema_version, learner_subject_id, session_id, event_type, source_kind, occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext, payload_digest, trace_id) VALUES (:event,'v1',:learner,'source','PREFERENCE','GENERAL',:at,:at,'STANDARD',0,:payload,:digest,'trace')")
                .param("event", event).param("learner", fixture.subjectId()).param("at", Timestamp.from(NOW))
                .param("payload", new byte[] {9}).param("digest", "9".repeat(64)).update();
        jdbc.sql("INSERT INTO interaction_payload_key_envelope (event_id,schema_version,learner_subject_id,dek_key_reference,wrapped_dek,wrap_key_reference,created_at) VALUES (:event,'v1',:learner,:dek,:wrapped,:wrap,:at)")
                .param("event", event).param("learner", fixture.subjectId()).param("dek", "k-" + "3".repeat(32))
                .param("wrapped", new byte[60]).param("wrap", "k-" + "4".repeat(32)).param("at", Timestamp.from(NOW)).update();
    }
    private record Fixture(String subject, UUID subjectId, UUID assertionId) { }
}
