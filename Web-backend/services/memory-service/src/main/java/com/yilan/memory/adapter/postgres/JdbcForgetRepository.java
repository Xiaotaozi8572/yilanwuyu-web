package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.privacy.DeletionReceipt;
import com.yilan.memory.application.privacy.DeletionVerifier;
import com.yilan.memory.application.privacy.ForgetRepository;
import com.yilan.memory.application.management.MemoryManagementRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** PostgreSQL authority implementation. Facts are appended; source/history are never rewritten. */
@Repository
public class JdbcForgetRepository implements ForgetRepository {
    private final JdbcClient jdbc;
    public JdbcForgetRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override @Transactional
    public DeletionReceipt requestFull(String subjectHash, String key, Instant at) {
        var learner = learnerForUpdate(subjectHash);
        var existing = existing(learner.id(), key);
        if (existing != null) return sameRequest(existing, "FULL", null);
        var previousFull = existingFull(learner.id());
        if (previousFull != null) return previousFull;
        if (!"ACTIVE".equals(learner.status())) throw new IllegalArgumentException("active subject unavailable");
        jdbc.sql("UPDATE learner_subject SET status = 'DISABLED' WHERE learner_subject_id = :id").param("id", learner.id()).update();
        jdbc.sql("INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at) VALUES (:id, 1, 1, :at) ON CONFLICT (learner_subject_id) DO UPDATE SET memory_epoch = learner_epoch.memory_epoch + 1, consent_epoch = learner_epoch.consent_epoch + 1, updated_at = EXCLUDED.updated_at")
                .param("id", learner.id()).param("at", Timestamp.from(at)).update();
        var receipt = append(learner.id(), null, "FULL", key, at);
        jdbc.sql("DELETE FROM learner_dek_envelope WHERE learner_subject_id = :id").param("id", learner.id()).update();
        jdbc.sql("DELETE FROM interaction_payload_key_envelope WHERE learner_subject_id = :id").param("id", learner.id()).update();
        return receipt;
    }

    @Override @Transactional
    public DeletionReceipt requestSingle(String subjectHash, UUID assertionId, String key, Instant at) {
        var existing = existing(subjectHash, key);
        if (existing != null) return sameRequest(existing, "ASSERTION", assertionId);
        var learner = learnerId(subjectHash);
        var exists = jdbc.sql("SELECT 1 FROM memory_assertion WHERE learner_subject_id = :learner AND memory_assertion_id = :assertion")
                .param("learner", learner).param("assertion", assertionId).query(Integer.class).optional().isPresent();
        if (!exists) throw new MemoryManagementRepository.MemoryNotFoundException();
        jdbc.sql("INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at) VALUES (:id, 1, 0, :at) ON CONFLICT (learner_subject_id) DO UPDATE SET memory_epoch = learner_epoch.memory_epoch + 1, updated_at = EXCLUDED.updated_at")
                .param("id", learner).param("at", Timestamp.from(at)).update();
        jdbc.sql("UPDATE memory_head_projection SET status = 'DISABLED', memory_epoch = (SELECT memory_epoch FROM learner_epoch WHERE learner_subject_id = :learner), updated_at = :at WHERE learner_subject_id = :learner AND memory_assertion_id = :assertion")
                .param("learner", learner).param("assertion", assertionId).param("at", Timestamp.from(at)).update();
        return append(learner, assertionId, "ASSERTION", key, at);
    }

    @Override @Transactional
    public boolean completeFull(UUID requestId, Instant at) {
        jdbc.sql("""
                INSERT INTO forget_execution (forget_execution_id, request_id, payload_references_removed, executed_at)
                SELECT :execution, request.forget_request_id,
                       (SELECT count(*) FROM interaction_event event
                        WHERE event.learner_subject_id = request.learner_subject_id
                          AND event.privacy_level IN ('SENSITIVE', 'HIGH')
                          AND event.payload_ciphertext IS NOT NULL)
                       + (SELECT count(*) FROM memory_candidate candidate
                          WHERE candidate.learner_subject_id = request.learner_subject_id
                            AND candidate.privacy_level IN ('SENSITIVE', 'HIGH')
                            AND candidate.candidate_ciphertext IS NOT NULL)
                       + (SELECT count(*) FROM memory_version version
                          WHERE version.learner_subject_id = request.learner_subject_id
                            AND version.privacy_level IN ('SENSITIVE', 'HIGH')
                            AND version.protected_value_ciphertext IS NOT NULL),
                       :at
                FROM forget_request request
                JOIN learner_subject subject ON subject.learner_subject_id = request.learner_subject_id
                WHERE request.forget_request_id = :request
                  AND request.scope = 'FULL'
                  AND request.memory_assertion_id IS NULL
                  AND subject.status = 'DISABLED'
                  AND EXISTS (SELECT 1 FROM forget_tombstone tombstone
                              WHERE tombstone.request_id = request.forget_request_id
                                AND tombstone.learner_subject_id = request.learner_subject_id
                                AND tombstone.scope = 'FULL'
                                AND tombstone.memory_assertion_id IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM learner_dek_envelope envelope
                                  WHERE envelope.learner_subject_id = request.learner_subject_id)
                  AND NOT EXISTS (SELECT 1 FROM interaction_payload_key_envelope envelope
                                  WHERE envelope.learner_subject_id = request.learner_subject_id)
                ON CONFLICT (request_id) DO NOTHING
                """)
                .param("execution", UUID.randomUUID()).param("request", requestId).param("at", Timestamp.from(at)).update();
        return fullEvidenceExists(requestId);
    }

    @Override public DeletionVerifier.Verification verify(UUID requestId) {
        var request = jdbc.sql("SELECT scope, learner_subject_id, memory_assertion_id FROM forget_request WHERE forget_request_id = :id")
                .param("id", requestId).query((rs, n) -> new Request(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class))).optional().orElseThrow();
        if ("FULL".equals(request.scope())) {
            var evidence = fullEvidence(requestId);
            var closed = evidence.subjectDisabled() && evidence.fullTombstone() && evidence.envelopes() == 0
                    && evidence.executionReferences() != null
                    && evidence.executionReferences() == evidence.protectedReferences();
            // A nonzero result means at least one payload remains potentially decryptable or lacks shred evidence.
            var remaining = closed ? 0 : Math.max(1L, evidence.protectedReferences());
            return new DeletionVerifier.Verification(requestId, DeletionVerifier.Scope.FULL, remaining,
                    evidence.retainedNonProtectedReferences(), closed, closed);
        }
        var remaining = jdbc.sql("SELECT count(*) FROM learner_dek_envelope WHERE learner_subject_id = :id")
                .param("id", request.learnerId()).query(Long.class).single();
        var closed = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM forget_tombstone tombstone
                               WHERE tombstone.request_id = :request AND tombstone.scope = 'ASSERTION'
                                 AND tombstone.memory_assertion_id = :assertion)
                   AND EXISTS (SELECT 1 FROM memory_head_projection head
                               WHERE head.learner_subject_id = :learner AND head.memory_assertion_id = :assertion
                                 AND head.status = 'DISABLED')
                """).param("request", requestId).param("learner", request.learnerId()).param("assertion", request.assertionId()).query(Boolean.class).single();
        return new DeletionVerifier.Verification(requestId, DeletionVerifier.Scope.ASSERTION, remaining, 0, false, closed);
    }

    @Override public List<UUID> pendingFullRequests() {
        return jdbc.sql("""
                SELECT request.forget_request_id FROM forget_request request
                WHERE request.scope = 'FULL'
                  AND NOT EXISTS (SELECT 1 FROM forget_execution execution WHERE execution.request_id = request.forget_request_id)
                ORDER BY request.requested_at
                """).query(UUID.class).list();
    }

    private boolean fullEvidenceExists(UUID requestId) {
        var evidence = fullEvidence(requestId);
        return evidence.subjectDisabled() && evidence.fullTombstone() && evidence.envelopes() == 0
                && evidence.executionReferences() != null
                && evidence.executionReferences() == evidence.protectedReferences();
    }

    private FullEvidence fullEvidence(UUID requestId) {
        return jdbc.sql("""
                SELECT subject.status = 'DISABLED',
                       EXISTS (SELECT 1 FROM forget_tombstone tombstone
                               WHERE tombstone.request_id = request.forget_request_id
                                 AND tombstone.learner_subject_id = request.learner_subject_id
                                 AND tombstone.scope = 'FULL'
                                 AND tombstone.memory_assertion_id IS NULL),
                       (SELECT count(*) FROM learner_dek_envelope envelope
                        WHERE envelope.learner_subject_id = request.learner_subject_id)
                       + (SELECT count(*) FROM interaction_payload_key_envelope envelope
                          WHERE envelope.learner_subject_id = request.learner_subject_id),
                       (SELECT execution.payload_references_removed FROM forget_execution execution
                        WHERE execution.request_id = request.forget_request_id),
                       (SELECT count(*) FROM interaction_event event
                        WHERE event.learner_subject_id = request.learner_subject_id
                          AND event.privacy_level IN ('SENSITIVE', 'HIGH')
                          AND event.payload_ciphertext IS NOT NULL)
                       + (SELECT count(*) FROM memory_candidate candidate
                          WHERE candidate.learner_subject_id = request.learner_subject_id
                            AND candidate.privacy_level IN ('SENSITIVE', 'HIGH')
                            AND candidate.candidate_ciphertext IS NOT NULL)
                       + (SELECT count(*) FROM memory_version version
                          WHERE version.learner_subject_id = request.learner_subject_id
                          AND version.privacy_level IN ('SENSITIVE', 'HIGH')
                          AND version.protected_value_ciphertext IS NOT NULL)
                       + 0,
                       (SELECT count(*) FROM interaction_event event
                        WHERE event.learner_subject_id = request.learner_subject_id
                          AND event.privacy_level = 'STANDARD'
                          AND event.payload_ciphertext IS NOT NULL)
                       + (SELECT count(*) FROM memory_candidate candidate
                          WHERE candidate.learner_subject_id = request.learner_subject_id
                            AND candidate.privacy_level = 'STANDARD'
                            AND candidate.candidate_ciphertext IS NOT NULL)
                       + (SELECT count(*) FROM memory_version version
                          WHERE version.learner_subject_id = request.learner_subject_id
                            AND version.privacy_level = 'STANDARD'
                            AND version.value_json IS NOT NULL)
                FROM forget_request request
                JOIN learner_subject subject ON subject.learner_subject_id = request.learner_subject_id
                WHERE request.forget_request_id = :request AND request.scope = 'FULL'
                """).param("request", requestId)
                .query((rs, ignored) -> new FullEvidence(rs.getBoolean(1), rs.getBoolean(2), rs.getLong(3),
                        rs.getObject(4, Long.class), rs.getLong(5), rs.getLong(6))).single();
    }

    private DeletionReceipt append(UUID learner, UUID assertion, String scope, String key, Instant at) {
        var request = UUID.randomUUID(); var digest = digest(key);
        jdbc.sql("INSERT INTO forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at) VALUES (:request,:learner,:assertion,:scope,:digest,'BLOCKED',:at)")
                .param("request", request).param("learner", learner).param("assertion", assertion).param("scope", scope).param("digest", digest).param("at", Timestamp.from(at)).update();
        jdbc.sql("INSERT INTO forget_tombstone (forget_tombstone_id, learner_subject_id, memory_assertion_id, scope, request_id, created_at) VALUES (:id,:learner,:assertion,:scope,:request,:at)")
                .param("id", UUID.randomUUID()).param("learner", learner).param("assertion", assertion).param("scope", scope).param("request", request).param("at", Timestamp.from(at)).update();
        return new DeletionReceipt(request, "BLOCKED", at);
    }
    private ExistingRequest existing(String subject, String key) {
        return jdbc.sql("SELECT r.forget_request_id, r.requested_at, r.scope, r.memory_assertion_id FROM forget_request r JOIN learner_subject s ON s.learner_subject_id=r.learner_subject_id WHERE s.subject_hash=:subject AND r.idempotency_key_digest=:digest")
                .param("subject", subject).param("digest", digest(key)).query((rs,n)->new ExistingRequest(new DeletionReceipt(rs.getObject(1, UUID.class), "BLOCKED", rs.getTimestamp(2).toInstant()), rs.getString(3), rs.getObject(4, UUID.class))).optional().orElse(null);
    }
    private ExistingRequest existing(UUID learner, String key) {
        return jdbc.sql("SELECT forget_request_id, requested_at, scope, memory_assertion_id FROM forget_request WHERE learner_subject_id=:learner AND idempotency_key_digest=:digest")
                .param("learner", learner).param("digest", digest(key)).query((rs,n)->new ExistingRequest(new DeletionReceipt(rs.getObject(1, UUID.class), "BLOCKED", rs.getTimestamp(2).toInstant()), rs.getString(3), rs.getObject(4, UUID.class))).optional().orElse(null);
    }
    private DeletionReceipt existingFull(UUID learner) {
        return jdbc.sql("SELECT forget_request_id, requested_at FROM forget_request WHERE learner_subject_id=:learner AND scope='FULL' ORDER BY requested_at DESC LIMIT 1")
                .param("learner", learner).query((rs,n)->new DeletionReceipt(rs.getObject(1, UUID.class), "BLOCKED", rs.getTimestamp(2).toInstant())).optional().orElse(null);
    }
    private static DeletionReceipt sameRequest(ExistingRequest existing, String scope, UUID assertionId) {
        if (!scope.equals(existing.scope()) || !Objects.equals(assertionId, existing.assertionId())) throw new IllegalStateException("idempotency key request mismatch");
        return existing.receipt();
    }
    private UUID learnerId(String subject) { return jdbc.sql("SELECT learner_subject_id FROM learner_subject WHERE subject_hash=:subject AND status='ACTIVE'").param("subject", subject).query(UUID.class).optional().orElseThrow(() -> new IllegalArgumentException("active subject unavailable")); }
    private Learner learnerForUpdate(String subject) { return jdbc.sql("SELECT learner_subject_id, status FROM learner_subject WHERE subject_hash=:subject FOR UPDATE").param("subject", subject).query((rs, n) -> new Learner(rs.getObject(1, UUID.class), rs.getString(2))).optional().orElseThrow(() -> new IllegalArgumentException("active subject unavailable")); }
    private static String digest(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private record ExistingRequest(DeletionReceipt receipt, String scope, UUID assertionId) { }
    private record Request(String scope, UUID learnerId, UUID assertionId) { }
    private record Learner(UUID id, String status) { }
    private record FullEvidence(boolean subjectDisabled, boolean fullTombstone, long envelopes, Long executionReferences,
                                long protectedReferences, long retainedNonProtectedReferences) { }
}
