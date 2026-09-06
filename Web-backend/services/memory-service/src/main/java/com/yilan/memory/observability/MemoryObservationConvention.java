package com.yilan.memory.observability;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.util.Map;
import java.util.Set;

/** Maps all observations to a small finite tag vocabulary. */
public final class MemoryObservationConvention {

    private static final Set<String> TAG_KEYS = Set.of(
            "operation", "status", "stage", "channel", "diagnostic_code");
    private static final Map<String, Set<String>> TAG_VALUES = Map.of(
            "operation", Set.of("resolve", "capabilities", "submit", "publish", "consume", "propose", "project", "unknown"),
            "status", Set.of("accepted", "empty", "degraded", "rejected", "unavailable", "invalid", "unknown"),
            "stage", Set.of("java.grpc", "java.redis", "java.worker", "java.neo4j", "unknown"),
            "channel", Set.of("grpc", "redis", "worker", "neo4j", "unknown"),
            "diagnostic_code", Set.of("none", "invalid_traceparent", "unavailable", "validation_failure", "unknown"));

    private MemoryObservationConvention() {
    }

    public static Tags tags(
            String operation,
            String status,
            String stage,
            String channel,
            String diagnosticCode) {
        return Tags.of(
                Tag.of("operation", finite("operation", operation)),
                Tag.of("status", finite("status", status)),
                Tag.of("stage", finite("stage", stage)),
                Tag.of("channel", finite("channel", channel)),
                Tag.of("diagnostic_code", finite("diagnostic_code", diagnosticCode)));
    }

    public static Set<String> allowedTagKeys() {
        return TAG_KEYS;
    }

    public static Set<String> allowedTagValues(String key) {
        return TAG_VALUES.getOrDefault(key, Set.of("unknown"));
    }

    private static String finite(String key, String value) {
        return value != null && TAG_VALUES.get(key).contains(value) ? value : "unknown";
    }
}
