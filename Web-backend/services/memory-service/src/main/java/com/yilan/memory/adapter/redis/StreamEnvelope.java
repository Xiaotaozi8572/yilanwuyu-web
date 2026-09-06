package com.yilan.memory.adapter.redis;

import com.yilan.memory.observability.TraceContextCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The complete Redis payload contract. It intentionally has no event body,
 * learner identity, query, answer, source text, candidate or profile fields.
 */
public record StreamEnvelope(
        String schemaVersion,
        UUID outboxId,
        UUID eventId,
        String routingHash,
        String traceparent) {

    public static final String SCHEMA_VERSION = "v1";

    public StreamEnvelope {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion");
        }
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(eventId, "eventId");
        if (routingHash == null || !routingHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("routingHash");
        }
        // Retain the fixed Redis field set while making absent tracing explicit
        // as an empty transport value. Raw legacy correlation IDs must never
        // become async trace parents.
        traceparent = TraceContextCodec.sanitizeForRelay(traceparent).orElse(null);
    }

    public Map<String, String> redisFields() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("schema_version", schemaVersion);
        fields.put("outbox_id", outboxId.toString());
        fields.put("event_id", eventId.toString());
        fields.put("routing_hash", routingHash);
        fields.put("traceparent", traceparent == null ? "" : traceparent);
        return Map.copyOf(fields);
    }
}
