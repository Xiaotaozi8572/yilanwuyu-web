package com.yilan.memory.application.async;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL-backed idempotency and recovery state. The unique primary key is
 * deliberately {@code (event_id, event_schema_version, stage_name, stage_version)};
 * Redis delivery identifiers and payloads never become authority keys.
 */
public final class CheckpointService {

    private static final Set<String> ALLOWED_DIAGNOSTIC_CODES = Set.of(
            "PROCESSING_FAILURE",
            "SUBJECT_INACTIVE",
            "UNCLASSIFIED_FAILURE",
            "SOURCE_AUTHORITY_UNAVAILABLE",
            "SOURCE_KEY_OR_CIPHERTEXT_UNAVAILABLE",
            "SOURCE_KEY_UNAVAILABLE",
            "WORKER_DEADLINE_EXCEEDED",
            "WORKER_UNAVAILABLE",
            "MODEL_UNAVAILABLE_RETRYABLE",
            "WORKER_PROVIDER_FAILURE",
            "BACKGROUND_CAPACITY_EXHAUSTED",
            "ONLINE_ANSWER_PRESSURE",
            "MISSING_HEALTH_LEASE",
            "TEMPERATURE_THRESHOLD_EXCEEDED",
            "CUDA_OOM");
    private static final Duration DEFAULT_PROCESSING_LEASE = Duration.ofMinutes(5);

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final Duration processingLease;

    public CheckpointService(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this(jdbcClient, transactionManager, DEFAULT_PROCESSING_LEASE);
    }

    /**
     * The lease is injected by the local runtime/test boundary, never inferred
     * from Redis delivery timing. A worker crash can therefore be replayed
     * without retaining a transaction or advisory lock across the RPC.
     */
    public CheckpointService(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            Duration processingLease) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.processingLease = Objects.requireNonNull(processingLease, "processingLease");
        if (processingLease.isZero() || processingLease.isNegative()) {
            throw new IllegalArgumentException("processingLease");
        }
    }

    public TransactionTemplate transactions() {
        return transactionTemplate;
    }

    /** Must be invoked within the transaction that owns stage completion. */
    public Claim claim(
            UUID eventId,
            String eventSchemaVersion,
            StageName stageName,
            int stageVersion,
            UUID learnerSubjectId,
            UUID outboxId,
            Instant now) {
        requireStageVersion(stageVersion);
        Objects.requireNonNull(eventId, "eventId");
        requireEventSchemaVersion(eventSchemaVersion);
        Objects.requireNonNull(stageName, "stageName");
        Objects.requireNonNull(learnerSubjectId, "learnerSubjectId");
        Objects.requireNonNull(now, "now");

        jdbcClient.sql("""
                        INSERT INTO async_processing_checkpoint (
                            event_id, event_schema_version, stage_name, stage_version, learner_subject_id, outbox_id,
                            status, attempts, created_at, updated_at)
                        VALUES (
                            :eventId, :eventSchemaVersion, :stageName, :stageVersion, :learnerSubjectId, :outboxId,
                            'PENDING', 0, :now, :now)
                        ON CONFLICT (event_id, event_schema_version, stage_name, stage_version) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("eventSchemaVersion", eventSchemaVersion)
                .param("stageName", stageName.name())
                .param("stageVersion", stageVersion)
                .param("learnerSubjectId", learnerSubjectId)
                .param("outboxId", outboxId)
                .param("now", Timestamp.from(now))
                .update();

        var checkpoint = loadForUpdate(eventId, eventSchemaVersion, stageName, stageVersion);
        if (checkpoint.status() == Status.SUCCEEDED) {
            return new Claim(ClaimDisposition.ALREADY_SUCCEEDED, checkpoint);
        }
        if (checkpoint.status() == Status.DEAD_LETTER || checkpoint.status() == Status.OBSOLETE) {
            return new Claim(ClaimDisposition.DEAD_LETTER, checkpoint);
        }
        if (checkpoint.status() == Status.RUNNING) {
            if (checkpoint.startedAt() == null || !checkpoint.startedAt().plus(processingLease).isAfter(now)) {
                if (RetryPolicy.isTerminal(checkpoint.attempts())) {
                    updateTerminal(eventId, eventSchemaVersion, stageName, stageVersion, Status.DEAD_LETTER,
                            "PROCESSING_FAILURE", now, null);
                    return new Claim(ClaimDisposition.DEAD_LETTER,
                            loadForUpdate(eventId, eventSchemaVersion, stageName, stageVersion));
                }
                jdbcClient.sql("""
                                UPDATE async_processing_checkpoint
                                SET status = 'RETRYABLE', next_attempt_at = :now,
                                    last_diagnostic_code = 'PROCESSING_FAILURE', completed_at = NULL, updated_at = :now
                                WHERE event_id = :eventId AND event_schema_version = :eventSchemaVersion
                                  AND stage_name = :stageName AND stage_version = :stageVersion
                                """)
                        .param("now", Timestamp.from(now))
                        .param("eventId", eventId)
                        .param("eventSchemaVersion", eventSchemaVersion)
                        .param("stageName", stageName.name())
                        .param("stageVersion", stageVersion)
                        .update();
                checkpoint = loadForUpdate(eventId, eventSchemaVersion, stageName, stageVersion);
            } else {
                return new Claim(ClaimDisposition.NOT_DUE, checkpoint);
            }
        }
        if (checkpoint.status() == Status.RETRYABLE
                && checkpoint.nextAttemptAt() != null
                && checkpoint.nextAttemptAt().isAfter(now)) {
            return new Claim(ClaimDisposition.NOT_DUE, checkpoint);
        }

        int attempts = checkpoint.attempts() + 1;
        jdbcClient.sql("""
                        UPDATE async_processing_checkpoint
                        SET status = 'RUNNING', attempts = :attempts, next_attempt_at = NULL,
                            started_at = :now, updated_at = :now
                        WHERE event_id = :eventId AND event_schema_version = :eventSchemaVersion
                          AND stage_name = :stageName AND stage_version = :stageVersion
                        """)
                .param("attempts", attempts)
                .param("now", Timestamp.from(now))
                .param("eventId", eventId)
                .param("eventSchemaVersion", eventSchemaVersion)
                .param("stageName", stageName.name())
                .param("stageVersion", stageVersion)
                .update();
        return new Claim(ClaimDisposition.STARTED,
                loadForUpdate(eventId, eventSchemaVersion, stageName, stageVersion));
    }

    /** Must be invoked in the same transaction as the successful stage write. */
    public void succeed(UUID eventId, String eventSchemaVersion, StageName stageName, int stageVersion, Instant now) {
        requireEventSchemaVersion(eventSchemaVersion);
        updateTerminal(eventId, eventSchemaVersion, stageName, stageVersion, Status.SUCCEEDED, null, now, null);
    }

    /** An inactive subject is not processed or retried, but its durable history remains intact. */
    public void obsolete(UUID eventId, String eventSchemaVersion, StageName stageName, int stageVersion, Instant now) {
        requireEventSchemaVersion(eventSchemaVersion);
        updateTerminal(eventId, eventSchemaVersion, stageName, stageVersion, Status.OBSOLETE, "SUBJECT_INACTIVE", now, null);
    }

    /** Persists no exception message or stack trace; only an allow-listed code. */
    public Status fail(UUID eventId, String eventSchemaVersion, StageName stageName, int stageVersion, int attempts, String diagnosticCode, Instant now) {
        requireEventSchemaVersion(eventSchemaVersion);
        String safeCode = safeDiagnosticCode(diagnosticCode);
        if (RetryPolicy.isTerminal(attempts)) {
            updateTerminal(eventId, eventSchemaVersion, stageName, stageVersion, Status.DEAD_LETTER, safeCode, now, null);
            return Status.DEAD_LETTER;
        }
        var nextAttempt = now.plus(RetryPolicy.backoffForAttempt(attempts));
        jdbcClient.sql("""
                        UPDATE async_processing_checkpoint
                        SET status = 'RETRYABLE', next_attempt_at = :nextAttempt,
                            last_diagnostic_code = :diagnosticCode, completed_at = NULL, updated_at = :now
                        WHERE event_id = :eventId AND event_schema_version = :eventSchemaVersion
                          AND stage_name = :stageName AND stage_version = :stageVersion
                        """)
                .param("nextAttempt", Timestamp.from(nextAttempt))
                .param("diagnosticCode", safeCode)
                .param("now", Timestamp.from(now))
                .param("eventId", eventId)
                .param("eventSchemaVersion", eventSchemaVersion)
                .param("stageName", stageName.name())
                .param("stageVersion", stageVersion)
                .update();
        return Status.RETRYABLE;
    }

    public long count(UUID eventId, String eventSchemaVersion, StageName stageName, int stageVersion) {
        requireEventSchemaVersion(eventSchemaVersion);
        return jdbcClient.sql("""
                        SELECT count(*) FROM async_processing_checkpoint
                        WHERE event_id = :eventId AND event_schema_version = :eventSchemaVersion
                          AND stage_name = :stageName AND stage_version = :stageVersion
                        """)
                .param("eventId", eventId)
                .param("eventSchemaVersion", eventSchemaVersion)
                .param("stageName", stageName.name())
                .param("stageVersion", stageVersion)
                .query(Long.class)
                .single();
    }

    public Optional<Checkpoint> find(UUID eventId, String eventSchemaVersion, StageName stageName, int stageVersion) {
        requireEventSchemaVersion(eventSchemaVersion);
        return jdbcClient.sql("""
                        SELECT event_id, event_schema_version, stage_name, stage_version, learner_subject_id, outbox_id, status, attempts,
                               next_attempt_at, last_diagnostic_code, started_at, completed_at, created_at, updated_at
                        FROM async_processing_checkpoint
                        WHERE event_id = :eventId AND event_schema_version = :eventSchemaVersion
                          AND stage_name = :stageName AND stage_version = :stageVersion
                        """)
                .param("eventId", eventId)
                .param("eventSchemaVersion", eventSchemaVersion)
                .param("stageName", stageName.name())
                .param("stageVersion", stageVersion)
                .query(this::mapCheckpoint)
                .optional();
    }

    private Checkpoint loadForUpdate(UUID eventId, String eventSchemaVersion, StageName stageName, int stageVersion) {
        return jdbcClient.sql("""
                        SELECT event_id, event_schema_version, stage_name, stage_version, learner_subject_id, outbox_id, status, attempts,
                               next_attempt_at, last_diagnostic_code, started_at, completed_at, created_at, updated_at
                        FROM async_processing_checkpoint
                        WHERE event_id = :eventId AND event_schema_version = :eventSchemaVersion
                          AND stage_name = :stageName AND stage_version = :stageVersion
                        FOR UPDATE
                        """)
                .param("eventId", eventId)
                .param("eventSchemaVersion", eventSchemaVersion)
                .param("stageName", stageName.name())
                .param("stageVersion", stageVersion)
                .query(this::mapCheckpoint)
                .single();
    }

    private void updateTerminal(
            UUID eventId,
            String eventSchemaVersion,
            StageName stageName,
            int stageVersion,
            Status status,
            String diagnosticCode,
            Instant now,
            Instant nextAttempt) {
        jdbcClient.sql("""
                        UPDATE async_processing_checkpoint
                        SET status = :status, next_attempt_at = :nextAttempt,
                            last_diagnostic_code = :diagnosticCode, completed_at = :now, updated_at = :now
                        WHERE event_id = :eventId AND event_schema_version = :eventSchemaVersion
                          AND stage_name = :stageName AND stage_version = :stageVersion
                        """)
                .param("status", status.name())
                .param("nextAttempt", nextAttempt == null ? null : Timestamp.from(nextAttempt))
                .param("diagnosticCode", diagnosticCode)
                .param("now", Timestamp.from(now))
                .param("eventId", eventId)
                .param("eventSchemaVersion", eventSchemaVersion)
                .param("stageName", stageName.name())
                .param("stageVersion", stageVersion)
                .update();
    }

    private Checkpoint mapCheckpoint(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        var nextAttempt = resultSet.getTimestamp("next_attempt_at");
        return new Checkpoint(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("event_schema_version"),
                StageName.valueOf(resultSet.getString("stage_name")),
                resultSet.getInt("stage_version"),
                resultSet.getObject("learner_subject_id", UUID.class),
                resultSet.getObject("outbox_id", UUID.class),
                Status.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempts"),
                nextAttempt == null ? null : nextAttempt.toInstant(),
                resultSet.getString("last_diagnostic_code"),
                resultSet.getTimestamp("started_at") == null ? null : resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("completed_at") == null ? null : resultSet.getTimestamp("completed_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static String safeDiagnosticCode(String diagnosticCode) {
        if (diagnosticCode != null && ALLOWED_DIAGNOSTIC_CODES.contains(diagnosticCode)) {
            return diagnosticCode;
        }
        return "UNCLASSIFIED_FAILURE";
    }

    private static void requireStageVersion(int stageVersion) {
        if (stageVersion <= 0) {
            throw new IllegalArgumentException("stageVersion");
        }
    }

    private static void requireEventSchemaVersion(String eventSchemaVersion) {
        if (eventSchemaVersion == null
                || eventSchemaVersion.isBlank()
                || "__unresolved__".equals(eventSchemaVersion)
                || eventSchemaVersion.getBytes(StandardCharsets.UTF_8).length > 16) {
            throw new IllegalArgumentException("eventSchemaVersion");
        }
    }

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        RETRYABLE,
        DEAD_LETTER,
        OBSOLETE
    }

    public enum ClaimDisposition {
        STARTED,
        ALREADY_SUCCEEDED,
        NOT_DUE,
        DEAD_LETTER
    }

    public record Claim(ClaimDisposition disposition, Checkpoint checkpoint) {
    }

    public record Checkpoint(
            UUID eventId,
            String eventSchemaVersion,
            StageName stageName,
            int stageVersion,
            UUID learnerSubjectId,
            UUID outboxId,
            Status status,
            int attempts,
            Instant nextAttemptAt,
            String lastDiagnosticCode,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt) {
    }
}
