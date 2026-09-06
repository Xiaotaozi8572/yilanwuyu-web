package com.yilan.memory.adapter.redis;

import com.yilan.memory.observability.MemoryMetrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes only identifier-only envelopes. PostgreSQL remains the durable
 * authority: a Redis failure rolls back the claimed rows to pending state.
 */
@Component
public class RedisOutboxPublisher {

    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 500;

    private final JdbcClient jdbcClient;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PublisherSettings settings;
    private final MemoryMetrics metrics;

    public RedisOutboxPublisher(
            JdbcClient jdbcClient,
            StringRedisTemplate redisTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${memory.redis.stream-prefix}") String streamPrefix,
            @Value("${memory.redis.publisher-batch-size}") int publisherBatchSize,
            @Value("${memory.redis.claim-idle}") Duration claimIdle,
            @Value("${memory.redis.publisher-interval}") Duration publisherInterval,
            MemoryMetrics metrics) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        RedisStreamNames.requireSupportedPrefix(streamPrefix);
        this.settings = new PublisherSettings(publisherBatchSize, claimIdle, publisherInterval);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Claims at most {@code limit} unpublished rows with PostgreSQL row locks,
     * XADDs one immutable envelope for each, then marks it published. A crash
     * after XADD and before commit deliberately permits replay by outbox ID.
     */
    public int publishBatch(int limit) {
        validateBatchLimit(limit);
        try {
            Integer published = transactionTemplate.execute(ignored -> publishClaimedRows(limit));
            return published == null ? 0 : published;
        } catch (RedisPublishUnavailableException unavailable) {
            metrics.record("publish", "unavailable", "java.redis", "redis", "unavailable", null, 0L);
            return 0;
        }
    }

    public PublisherSettings settings() {
        return settings;
    }

    private int publishClaimedRows(int limit) {
        var claimed = jdbcClient.sql("""
                        SELECT outbox.outbox_id, event.event_id, subject.subject_hash, event.trace_id
                        FROM transactional_outbox outbox
                        JOIN interaction_event event
                          ON event.event_id = outbox.aggregate_id
                         AND event.schema_version = outbox.payload ->> 'event_schema_version'
                        JOIN learner_subject subject
                          ON subject.learner_subject_id = event.learner_subject_id
                        WHERE outbox.published_at IS NULL
                        ORDER BY outbox.created_at, outbox.outbox_id
                        LIMIT :limit
                        FOR UPDATE OF outbox SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ClaimedOutbox(
                        resultSet.getObject("outbox_id", UUID.class),
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("subject_hash"),
                        resultSet.getString("trace_id")))
                .list();

        for (var row : claimed) {
            var envelope = new StreamEnvelope(
                    StreamEnvelope.SCHEMA_VERSION,
                    row.outboxId(),
                    row.eventId(),
                    routingHash(row.subjectHash()),
                    row.traceparent());
            try {
                redisTemplate.opsForStream().add(
                        StreamRecords.mapBacked(envelope.redisFields()).withStreamKey(RedisStreamNames.events()));
            } catch (DataAccessException unavailable) {
                throw new RedisPublishUnavailableException(unavailable);
            }
            jdbcClient.sql("""
                            UPDATE transactional_outbox
                            SET published_at = CURRENT_TIMESTAMP,
                                attempts = attempts + 1
                            WHERE outbox_id = :outboxId
                              AND published_at IS NULL
                            """)
                    .param("outboxId", row.outboxId())
                    .update();
            metrics.record("publish", "accepted", "java.redis", "redis", "none", row.traceparent(), 0L);
        }
        return claimed.size();
    }

    private static void validateBatchLimit(int limit) {
        if (limit < MIN_BATCH_SIZE || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be within 1..500");
        }
    }

    private static String routingHash(String subjectHash) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("memory:v1:routing:".getBytes(StandardCharsets.UTF_8));
            digest.update(Objects.requireNonNull(subjectHash, "subjectHash").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the JDK", unavailable);
        }
    }

    private record ClaimedOutbox(UUID outboxId, UUID eventId, String subjectHash, String traceparent) {
    }

    private static final class RedisPublishUnavailableException extends RuntimeException {

        private RedisPublishUnavailableException(DataAccessException cause) {
            super(cause);
        }
    }

    public record PublisherSettings(int batchSize, Duration claimIdle, Duration publisherInterval) {

        public PublisherSettings {
            validateBatchLimit(batchSize);
            if (claimIdle == null || claimIdle.isNegative() || claimIdle.isZero()) {
                throw new IllegalArgumentException("claimIdle");
            }
            if (publisherInterval == null || publisherInterval.isNegative() || publisherInterval.isZero()) {
                throw new IllegalArgumentException("publisherInterval");
            }
        }
    }
}
