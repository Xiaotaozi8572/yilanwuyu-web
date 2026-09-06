package com.yilan.memory.adapter.redis;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Claims stale Redis consumer-group deliveries. PostgreSQL checkpoints decide
 * whether a reclaimed record can run; Redis pending state is never authority.
 */
public final class PendingEntryReclaimer {

    private final StringRedisTemplate redisTemplate;
    private final String group;
    private final String consumer;
    private final Duration claimIdle;
    private final int batchSize;

    public PendingEntryReclaimer(
            StringRedisTemplate redisTemplate,
            String group,
            String consumer,
            Duration claimIdle,
            int batchSize) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.group = requireNonBlank(group, "group");
        this.consumer = requireNonBlank(consumer, "consumer");
        if (claimIdle == null || claimIdle.isNegative()) {
            throw new IllegalArgumentException("claimIdle");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize");
        }
        this.claimIdle = claimIdle;
        this.batchSize = batchSize;
    }

    public List<MapRecord<String, Object, Object>> reclaimIdle() {
        PendingMessages pending = redisTemplate.opsForStream().pending(
                RedisStreamNames.events(), group, Range.unbounded(), batchSize);
        RecordId[] staleIds = pending.stream()
                .filter(message -> !message.getElapsedTimeSinceLastDelivery().minus(claimIdle).isNegative())
                .map(message -> message.getId())
                .toArray(RecordId[]::new);
        if (staleIds.length == 0) {
            return List.of();
        }
        return redisTemplate.opsForStream().claim(
                RedisStreamNames.events(), group, consumer, claimIdle, staleIds);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }
}
