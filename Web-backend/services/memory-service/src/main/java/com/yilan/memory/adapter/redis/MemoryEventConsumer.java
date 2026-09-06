package com.yilan.memory.adapter.redis;

import com.yilan.memory.application.async.AsyncStageProcessor;
import com.yilan.memory.application.async.AsyncStageProcessor.EventContext;
import com.yilan.memory.application.async.AsyncStageProcessor.PreparedAttempt;
import com.yilan.memory.application.async.CandidateProcessingService;
import com.yilan.memory.application.async.CheckpointService;
import com.yilan.memory.application.async.StageName;
import com.yilan.memory.observability.MemoryMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes identifier-only Redis records under a durable PostgreSQL checkpoint.
 * Redis acknowledgement always happens after the relevant checkpoint
 * transaction commits; retryable work deliberately stays pending.
 */
public final class MemoryEventConsumer {

    private static final StageName DEFAULT_STAGE = StageName.CANDIDATE_EXTRACTION;
    private static final int DEFAULT_STAGE_VERSION = 1;

    private final JdbcClient jdbcClient;
    private final StringRedisTemplate redisTemplate;
    private final CheckpointService checkpoints;
    private final AsyncStageProcessor processor;
    private final String group;
    private final String consumer;
    private final int batchSize;
    private final Clock clock;
    private final PendingEntryReclaimer reclaimer;
    private final MemoryMetrics metrics;

    public MemoryEventConsumer(
            JdbcClient jdbcClient,
            StringRedisTemplate redisTemplate,
            CheckpointService checkpoints,
            AsyncStageProcessor processor,
            String group,
            String consumer,
            int batchSize,
            Duration claimIdle,
            Clock clock) {
        this(jdbcClient, redisTemplate, checkpoints, processor, group, consumer, batchSize, claimIdle, clock,
                MemoryMetrics.noop());
    }

    public MemoryEventConsumer(
            JdbcClient jdbcClient,
            StringRedisTemplate redisTemplate,
            CheckpointService checkpoints,
            AsyncStageProcessor processor,
            String group,
            String consumer,
            int batchSize,
            Duration claimIdle,
            Clock clock,
            MemoryMetrics metrics) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.group = requireNonBlank(group, "group");
        this.consumer = requireNonBlank(consumer, "consumer");
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize");
        }
        this.batchSize = batchSize;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reclaimer = new PendingEntryReclaimer(redisTemplate, group, consumer, claimIdle, batchSize);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Drains new and reclaimable records once; no scheduler is started here. */
    public int drainOnce() {
        ensureConsumerGroup();
        var records = new ArrayList<MapRecord<String, Object, Object>>();
        records.addAll(reclaimer.reclaimIdle());
        var newRecords = redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(RedisStreamNames.events(), ReadOffset.lastConsumed()));
        if (newRecords != null) {
            records.addAll(newRecords);
        }
        int handled = 0;
        for (var record : records) {
            if (handle(record)) {
                handled++;
            }
        }
        return handled;
    }

    private boolean handle(MapRecord<String, Object, Object> record) {
        final StreamEnvelope envelope;
        try {
            envelope = parseEnvelope(record.getValue());
        } catch (IllegalArgumentException invalidEnvelope) {
            // The upstream relay is trusted. Do not copy unknown fields to DLQ.
            acknowledge(record);
            metrics.record("consume", "invalid", "java.redis", "redis", "validation_failure", null, 0L);
            return true;
        }

        final ClaimResult claimResult;
        try {
            claimResult = checkpoints.transactions().execute(
                    ignored -> claimInTransaction(envelope, Instant.now(clock)));
        } catch (RuntimeException unavailable) {
            metrics.record("consume", "unavailable", "java.redis", "redis", "unavailable", envelope.traceparent(), 0L);
            return false;
        }
        if (claimResult == null || claimResult.result() == ProcessingResult.RETRY_LATER) {
            metrics.record("consume", "unavailable", "java.redis", "redis", "unavailable", envelope.traceparent(), 0L);
            return false;
        }
        var result = claimResult.result();
        if (claimResult.work() != null) {
            result = processClaimedWork(claimResult.work(), Instant.now(clock));
        }
        if (result == null || result == ProcessingResult.RETRY_LATER) {
            metrics.record("consume", "unavailable", "java.redis", "redis", "unavailable", envelope.traceparent(), 0L);
            return false;
        }
        if (result == ProcessingResult.DEAD_LETTER) {
            try {
                redisTemplate.opsForStream().add(
                        org.springframework.data.redis.connection.stream.StreamRecords
                                .mapBacked(envelope.redisFields())
                                .withStreamKey(RedisStreamNames.deadLetter()));
            } catch (DataAccessException unavailable) {
                metrics.record("consume", "unavailable", "java.redis", "redis", "unavailable", envelope.traceparent(), 0L);
                return false;
            }
        }
        acknowledge(record);
        metrics.record("consume", result == ProcessingResult.DEAD_LETTER ? "rejected" : "accepted",
                "java.redis", "redis", result == ProcessingResult.DEAD_LETTER ? "validation_failure" : "none",
                envelope.traceparent(), 0L);
        return true;
    }

    /**
     * Transaction A: resolve only trusted identifier metadata and persist the
     * checkpoint claim. It intentionally contains no source text read, worker
     * RPC, proposal validation, or learner advisory lock.
     */
    private ClaimResult claimInTransaction(StreamEnvelope envelope, Instant now) {
        Optional<EventMetadata> metadata = eventMetadata(envelope.outboxId(), envelope.eventId());
        if (metadata.isEmpty()) {
            return ClaimResult.completed(ProcessingResult.ACK);
        }
        var event = metadata.orElseThrow();
        var claim = checkpoints.claim(
                envelope.eventId(), event.eventSchemaVersion(), DEFAULT_STAGE, DEFAULT_STAGE_VERSION,
                event.learnerSubjectId(), envelope.outboxId(), now);
        if (claim.disposition() == CheckpointService.ClaimDisposition.ALREADY_SUCCEEDED) {
            return ClaimResult.completed(ProcessingResult.ACK);
        }
        if (claim.disposition() == CheckpointService.ClaimDisposition.NOT_DUE) {
            return ClaimResult.completed(ProcessingResult.RETRY_LATER);
        }
        if (claim.disposition() == CheckpointService.ClaimDisposition.DEAD_LETTER) {
            if (claim.checkpoint().status() == CheckpointService.Status.OBSOLETE) {
                return ClaimResult.completed(ProcessingResult.ACK);
            }
            return ClaimResult.completed(ProcessingResult.DEAD_LETTER);
        }
        if (!event.active()) {
            checkpoints.obsolete(envelope.eventId(), event.eventSchemaVersion(), DEFAULT_STAGE, DEFAULT_STAGE_VERSION, now);
            return ClaimResult.completed(ProcessingResult.ACK);
        }
        return ClaimResult.claimed(new ClaimedWork(
                new EventContext(envelope.outboxId(), envelope.eventId(), event.learnerSubjectId(), DEFAULT_STAGE,
                        DEFAULT_STAGE_VERSION, envelope.traceparent()),
                event.eventSchemaVersion(),
                claim.checkpoint().attempts()));
    }

    /**
     * Executes the worker boundary only after transaction A committed. Any
     * preparation exception is retryable and never becomes an ACK.
     */
    private ProcessingResult processClaimedWork(ClaimedWork work, Instant now) {
        try {
            var prepared = processor.prepareOutsideTransaction(work.context());
            if (prepared == null) {
                return finishRetryable(work, "PROCESSING_FAILURE", now);
            }
            var finalized = checkpoints.transactions().execute(
                    ignored -> finalizeInTransaction(work, prepared, now));
            return finalized == null ? ProcessingResult.RETRY_LATER : finalized;
        } catch (Exception failure) {
            return finishRetryable(work, "PROCESSING_FAILURE", now);
        }
    }

    /**
     * Transaction B: re-loads trusted outbox/event metadata, serializes the
     * learner only now, lets the processor re-read authority source, and
     * commits its terminal checkpoint in the same transaction as governance.
     */
    private ProcessingResult finalizeInTransaction(ClaimedWork work, PreparedAttempt prepared, Instant now) {
        try {
            var metadata = eventMetadata(work.context().outboxId(), work.context().eventId());
            if (metadata.isEmpty() || !metadata.orElseThrow().learnerSubjectId().equals(work.context().learnerSubjectId())
                    || !metadata.orElseThrow().eventSchemaVersion().equals(work.eventSchemaVersion())
                    || !metadata.orElseThrow().active()) {
                checkpoints.obsolete(work.context().eventId(), work.eventSchemaVersion(), work.context().stageName(),
                        work.context().stageVersion(), now);
                return ProcessingResult.ACK;
            }
            if (work.context().stageName().requiresLearnerAdvisoryLock()) {
                acquireLearnerAdvisoryLock(work.context().learnerSubjectId());
            }
            var outcome = Objects.requireNonNull(
                    processor.finalizeInTransaction(work.context(), prepared), "stage outcome");
            if (outcome.kind() == CandidateProcessingService.StageOutcome.Kind.RETRYABLE) {
                return retryWithinTransaction(work, outcome.diagnosticCode(), now);
            }
            checkpoints.succeed(work.context().eventId(), work.eventSchemaVersion(),
                    work.context().stageName(), work.context().stageVersion(), now);
            return ProcessingResult.ACK;
        } catch (Exception failure) {
            return retryWithinTransaction(work, "PROCESSING_FAILURE", now);
        }
    }

    private ProcessingResult finishRetryable(ClaimedWork work, String diagnosticCode, Instant now) {
        try {
            var result = checkpoints.transactions().execute(
                    ignored -> retryWithinTransaction(work, diagnosticCode, now));
            return result == null ? ProcessingResult.RETRY_LATER : result;
        } catch (RuntimeException unavailable) {
            return ProcessingResult.RETRY_LATER;
        }
    }

    private ProcessingResult retryWithinTransaction(ClaimedWork work, String diagnosticCode, Instant now) {
        var status = checkpoints.fail(
                work.context().eventId(), work.eventSchemaVersion(), work.context().stageName(), work.context().stageVersion(),
                work.attempts(), diagnosticCode, now);
        return status == CheckpointService.Status.DEAD_LETTER
                ? ProcessingResult.DEAD_LETTER
                : ProcessingResult.RETRY_LATER;
    }

    private Optional<EventMetadata> eventMetadata(UUID outboxId, UUID eventId) {
        return jdbcClient.sql("""
                        SELECT event.learner_subject_id, outbox.payload ->> 'event_schema_version' AS event_schema_version,
                               subject.status
                        FROM transactional_outbox outbox
                        JOIN interaction_event event
                          ON event.event_id = outbox.aggregate_id
                         AND event.schema_version = outbox.payload ->> 'event_schema_version'
                        JOIN learner_subject subject ON subject.learner_subject_id = event.learner_subject_id
                        WHERE outbox.outbox_id = :outboxId
                          AND outbox.aggregate_id = :eventId
                          AND outbox.event_type = 'INTERACTION_EVENT_ACCEPTED'
                          AND outbox.payload ->> 'event_id' = CAST(:eventId AS text)
                        """)
                .param("outboxId", outboxId)
                .param("eventId", eventId)
                .query((resultSet, rowNumber) -> new EventMetadata(
                        resultSet.getObject("learner_subject_id", UUID.class),
                        resultSet.getString("event_schema_version"),
                        "ACTIVE".equals(resultSet.getString("status"))))
                .optional();
    }

    private void acquireLearnerAdvisoryLock(UUID learnerSubjectId) {
        jdbcClient.sql("""
                        SELECT pg_advisory_xact_lock(hashtextextended(CAST(:learnerSubjectId AS text), 0))
                        """)
                .param("learnerSubjectId", learnerSubjectId)
                .query(Object.class)
                .single();
    }

    private void ensureConsumerGroup() {
        try {
            redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    bytes("CREATE"), bytes(RedisStreamNames.events()), bytes(group), bytes("0"), bytes("MKSTREAM")));
        } catch (DataAccessException alreadyExists) {
            if (!hasBusyGroupCause(alreadyExists)) {
                throw alreadyExists;
            }
        }
    }

    private static boolean hasBusyGroupCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(RedisStreamNames.events(), group, record.getId());
    }

    private static StreamEnvelope parseEnvelope(Map<Object, Object> fields) {
        if (!fields.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                .equals(java.util.Set.of("schema_version", "outbox_id", "event_id", "routing_hash", "traceparent"))) {
            throw new IllegalArgumentException("invalid stream envelope fields");
        }
        return new StreamEnvelope(
                String.valueOf(fields.get("schema_version")),
                UUID.fromString(String.valueOf(fields.get("outbox_id"))),
                UUID.fromString(String.valueOf(fields.get("event_id"))),
                String.valueOf(fields.get("routing_hash")),
                String.valueOf(fields.get("traceparent")));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    private record EventMetadata(UUID learnerSubjectId, String eventSchemaVersion, boolean active) {
    }

    private record ClaimedWork(EventContext context, String eventSchemaVersion, int attempts) {
    }

    private record ClaimResult(ProcessingResult result, ClaimedWork work) {
        static ClaimResult completed(ProcessingResult result) {
            return new ClaimResult(result, null);
        }

        static ClaimResult claimed(ClaimedWork work) {
            return new ClaimResult(null, work);
        }
    }

    private enum ProcessingResult {
        ACK,
        RETRY_LATER,
        DEAD_LETTER
    }
}
