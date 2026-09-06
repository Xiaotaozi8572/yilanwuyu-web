package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.privacy.RetentionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** PostgreSQL authority retention maintenance using small SKIP LOCKED batches. */
@Repository
public class JdbcRetentionRepository implements RetentionRepository {

    private static final String PROJECTION_PURGE = "MEMORY_PROJECTION_PURGE";
    private final JdbcClient jdbc;

    public JdbcRetentionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int eraseDueSourcePayloads(Instant now, int batchSize, String policyVersion) {
        require(now, batchSize, policyVersion);
        var due = now.minus(30, ChronoUnit.DAYS);
        var rows = jdbc.sql("""
                        SELECT subject.subject_hash, envelope.event_id, envelope.schema_version,
                               event.occurred_at
                        FROM interaction_payload_key_envelope envelope
                        JOIN interaction_event event ON event.event_id = envelope.event_id
                            AND event.schema_version = envelope.schema_version
                            AND event.learner_subject_id = envelope.learner_subject_id
                        JOIN learner_subject subject ON subject.learner_subject_id = envelope.learner_subject_id
                        WHERE event.occurred_at <= :due
                        ORDER BY event.occurred_at, envelope.event_id
                        FOR UPDATE OF envelope SKIP LOCKED
                        LIMIT :batch
                        """).param("due", Timestamp.from(due)).param("batch", batchSize)
                .query((rs, ignored) -> new SourceRow(rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getTimestamp(4).toInstant())).list();
        for (var row : rows) {
            jdbc.sql("""
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
                            """).param("id", UUID.randomUUID()).param("policy", policyVersion)
                    .param("due", Timestamp.from(row.occurredAt().plus(30, ChronoUnit.DAYS)))
                    .param("executed", Timestamp.from(now)).param("subject", row.subjectHash())
                    .param("event", row.eventId()).param("schema", row.schemaVersion()).update();
            jdbc.sql("DELETE FROM interaction_payload_key_envelope WHERE event_id = :event AND schema_version = :schema")
                    .param("event", row.eventId()).param("schema", row.schemaVersion()).update();
        }
        return rows.size();
    }

    @Override
    @Transactional
    public int eraseDueAuditEvents(Instant now, int batchSize, String policyVersion) {
        require(now, batchSize, policyVersion);
        var due = now.minus(365, ChronoUnit.DAYS);
        var rows = jdbc.sql("""
                        SELECT audit.memory_audit_event_id, audit.learner_subject_id, audit.created_at
                        FROM memory_audit_event audit
                        WHERE audit.created_at <= :due
                        ORDER BY audit.created_at, audit.memory_audit_event_id
                        FOR UPDATE SKIP LOCKED
                        LIMIT :batch
                        """).param("due", Timestamp.from(due)).param("batch", batchSize)
                .query((rs, ignored) -> new AuditRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant())).list();
        for (var row : rows) {
            jdbc.sql("""
                            INSERT INTO retention_erasure (
                                retention_erasure_id, target_kind, target_id, event_schema_version,
                                learner_subject_id, policy_version, due_at, executed_at)
                            VALUES (:id, 'AUDIT_EVENT', :audit, NULL, :learner, :policy, :due, :executed)
                            ON CONFLICT DO NOTHING
                            """).param("id", UUID.randomUUID()).param("audit", row.auditId()).param("learner", row.learnerId())
                    .param("policy", policyVersion).param("due", Timestamp.from(row.createdAt().plus(365, ChronoUnit.DAYS)))
                    .param("executed", Timestamp.from(now)).update();
            jdbc.sql("DELETE FROM memory_audit_event WHERE memory_audit_event_id = :audit")
                    .param("audit", row.auditId()).update();
        }
        return rows.size();
    }

    @Override
    @Transactional
    public int expireDueMemory(Instant now, int batchSize) {
        return transitionDueMemory(now, batchSize, false);
    }

    @Override
    @Transactional
    public int staleDuePreferences(Instant now, int batchSize) {
        return transitionDueMemory(now, batchSize, true);
    }

    private int transitionDueMemory(Instant now, int batchSize, boolean preferencesOnly) {
        if (now == null || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("retention batch");
        }
        var rows = jdbc.sql("""
                        SELECT head.memory_assertion_id, head.memory_version_id, head.learner_subject_id,
                               assertion.memory_type
                        FROM memory_head_projection head
                        JOIN memory_assertion assertion ON assertion.memory_assertion_id = head.memory_assertion_id
                            AND assertion.learner_subject_id = head.learner_subject_id
                        JOIN memory_version version ON version.memory_version_id = head.memory_version_id
                            AND version.memory_assertion_id = head.memory_assertion_id
                            AND version.learner_subject_id = head.learner_subject_id
                        JOIN learner_subject subject ON subject.learner_subject_id = head.learner_subject_id
                        WHERE subject.status = 'ACTIVE'
                          AND head.status = 'ACTIVE'
                          AND version.valid_until IS NOT NULL AND version.valid_until <= :now
                          AND ((:preferencesOnly = TRUE AND assertion.memory_type = 'PREFERENCE')
                               OR (:preferencesOnly = FALSE AND assertion.memory_type <> 'PREFERENCE'))
                        ORDER BY version.valid_until, head.memory_assertion_id
                        FOR UPDATE OF head SKIP LOCKED
                        LIMIT :batch
                        """).param("now", Timestamp.from(now)).param("preferencesOnly", preferencesOnly).param("batch", batchSize)
                .query((rs, ignored) -> new HeadRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4))).list();
        var epochs = new LinkedHashMap<UUID, Long>();
        for (var row : rows) {
            var state = "PREFERENCE".equals(row.memoryType()) ? "STALE" : "EXPIRED";
            var transition = "PREFERENCE".equals(row.memoryType()) ? "STALE" : "EXPIRE";
            jdbc.sql("""
                            INSERT INTO memory_transition (
                                memory_transition_id, memory_assertion_id, learner_subject_id,
                                from_memory_version_id, to_memory_version_id, transition_type, reason_code, transitioned_at)
                            VALUES (:id, :assertion, :learner, :version, :version, :transition, 'RETENTION_DUE', :at)
                            """).param("id", UUID.randomUUID()).param("assertion", row.assertionId())
                    .param("learner", row.learnerId()).param("version", row.versionId())
                    .param("transition", transition).param("at", Timestamp.from(now)).update();
            epochs.computeIfAbsent(row.learnerId(), this::incrementEpoch);
            row.state = state;
        }
        for (var row : rows) {
            var epoch = epochs.get(row.learnerId());
            jdbc.sql("""
                            UPDATE memory_head_projection
                            SET status = :status, memory_epoch = :epoch, updated_at = :at
                            WHERE memory_assertion_id = :assertion AND learner_subject_id = :learner
                            """).param("status", row.state).param("epoch", epoch).param("at", Timestamp.from(now))
                    .param("assertion", row.assertionId()).param("learner", row.learnerId()).update();
            var payload = "{\"learner_subject_id\":\"" + row.learnerId() + "\",\"memory_assertion_id\":\""
                    + row.assertionId() + "\",\"memory_epoch\":" + epoch + '}';
            jdbc.sql("""
                            INSERT INTO transactional_outbox (outbox_id, aggregate_id, event_type, payload, created_at)
                            VALUES (:id, :assertion, :type, CAST(:payload AS jsonb), :at)
                            """).param("id", UUID.randomUUID()).param("assertion", row.assertionId())
                    .param("type", PROJECTION_PURGE).param("payload", payload).param("at", Timestamp.from(now)).update();
        }
        return rows.size();
    }

    private long incrementEpoch(UUID learnerId) {
        return jdbc.sql("""
                        INSERT INTO learner_epoch (learner_subject_id, memory_epoch, consent_epoch, updated_at)
                        VALUES (:learner, 1, 0, CURRENT_TIMESTAMP)
                        ON CONFLICT (learner_subject_id) DO UPDATE
                        SET memory_epoch = learner_epoch.memory_epoch + 1, updated_at = CURRENT_TIMESTAMP
                        RETURNING memory_epoch
                        """).param("learner", learnerId).query(Long.class).single();
    }

    private static void require(Instant now, int batchSize, String policyVersion) {
        if (now == null || batchSize < 1 || batchSize > 500
                || policyVersion == null || !policyVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("retention batch");
        }
    }

    private record SourceRow(String subjectHash, UUID eventId, String schemaVersion, Instant occurredAt) { }
    private record AuditRow(UUID auditId, UUID learnerId, Instant createdAt) { }
    private static final class HeadRow {
        private final UUID assertionId;
        private final UUID versionId;
        private final UUID learnerId;
        private final String memoryType;
        private String state;
        private HeadRow(UUID assertionId, UUID versionId, UUID learnerId, String memoryType) {
            this.assertionId = assertionId; this.versionId = versionId; this.learnerId = learnerId; this.memoryType = memoryType;
        }
        UUID assertionId() { return assertionId; }
        UUID versionId() { return versionId; }
        UUID learnerId() { return learnerId; }
        String memoryType() { return memoryType; }
    }
}
