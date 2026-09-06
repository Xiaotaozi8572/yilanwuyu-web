package com.yilan.memory.adapter.redis;

import com.yilan.memory.application.async.StageName;
import com.yilan.memory.observability.TraceContextCodec;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reconstructs lost Redis work from PostgreSQL. It never recreates memory
 * content: only the same identifier-only envelope accepted by the relay.
 */
public final class OutboxReconciler {

    private static final StageName STAGE = StageName.CANDIDATE_EXTRACTION;
    private static final int STAGE_VERSION = 1;

    private final JdbcClient jdbcClient;
    private final StringRedisTemplate redisTemplate;

    public OutboxReconciler(JdbcClient jdbcClient, StringRedisTemplate redisTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    }

    public int requeueMissingCompletions(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be within 1..500");
        }
        var rows = jdbcClient.sql("""
                        SELECT outbox.outbox_id, event.event_id, event.learner_subject_id,
                               subject.subject_hash, event.trace_id
                        FROM transactional_outbox outbox
                        JOIN interaction_event event
                          ON event.event_id = outbox.aggregate_id
                         AND event.schema_version = outbox.payload ->> 'event_schema_version'
                        JOIN learner_subject subject ON subject.learner_subject_id = event.learner_subject_id
                        WHERE outbox.event_type = 'INTERACTION_EVENT_ACCEPTED'
                          AND NOT EXISTS (
                              SELECT 1 FROM async_processing_checkpoint checkpoint
                              WHERE checkpoint.event_id = event.event_id
                                AND checkpoint.event_schema_version = event.schema_version
                                AND checkpoint.stage_name = :stageName
                                AND checkpoint.stage_version = :stageVersion
                                AND checkpoint.status = 'SUCCEEDED'
                          )
                        ORDER BY outbox.created_at, outbox.outbox_id
                        LIMIT :limit
                        """)
                .param("stageName", STAGE.name())
                .param("stageVersion", STAGE_VERSION)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ReconcileRow(
                        resultSet.getObject("outbox_id", UUID.class),
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getObject("learner_subject_id", UUID.class),
                        resultSet.getString("subject_hash"),
                        resultSet.getString("trace_id")))
                .list();
        int requeued = 0;
        for (var row : rows) {
            var envelope = new StreamEnvelope(
                    StreamEnvelope.SCHEMA_VERSION,
                    row.outboxId(), row.eventId(), routingHash(row.subjectHash()),
                    TraceContextCodec.sanitizeForRelay(row.traceparent()).orElse(null));
            redisTemplate.opsForStream().add(
                    StreamRecords.mapBacked(envelope.redisFields()).withStreamKey(RedisStreamNames.events()));
            recordAudit(row, now);
            requeued++;
        }
        return requeued;
    }

    private void recordAudit(ReconcileRow row, Instant now) {
        jdbcClient.sql("""
                        INSERT INTO memory_audit_event (
                            memory_audit_event_id, learner_subject_id, audit_type, actor_type,
                            correlation_id, redacted_metadata, created_at)
                        VALUES (
                            :auditId, :learnerSubjectId, 'ASYNC_RECONCILIATION_REQUEUED', 'SYSTEM',
                            :correlationId, CAST(:metadata AS jsonb), :createdAt)
                        """)
                .param("auditId", UUID.randomUUID())
                .param("learnerSubjectId", row.learnerSubjectId())
                .param("correlationId", row.traceparent())
                .param("metadata", "{\"outbox_id\":\"" + row.outboxId()
                        + "\",\"stage_name\":\"" + STAGE.name()
                        + "\",\"stage_version\":" + STAGE_VERSION
                        + ",\"outcome\":\"REQUEUED\""
                        + ",\"diagnostic_code\":\"MISSING_SUCCESS_CHECKPOINT\"}")
                .param("createdAt", Timestamp.from(now))
                .update();
    }

    private static String routingHash(String subjectHash) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("memory:v1:routing:".getBytes(StandardCharsets.UTF_8));
            digest.update(subjectHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the JDK", unavailable);
        }
    }

    private record ReconcileRow(
            UUID outboxId,
            UUID eventId,
            UUID learnerSubjectId,
            String subjectHash,
            String traceparent) {
    }
}
