package com.yilan.memory.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityRedactionTest {

    private static final String TRACEPARENT = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
    private static final String SENTINEL = "sensitive-memory-content-should-never-be-exported";

    @Test
    void acceptsOnlyVersionZeroNonZeroBoundedTraceparents() {
        var parsed = TraceContextCodec.parse(TRACEPARENT);

        assertTrue(parsed.isPresent());
        assertEquals(TRACEPARENT, parsed.orElseThrow().format());
        assertTrue(TraceContextCodec.parse(SENTINEL).isEmpty());
        assertTrue(TraceContextCodec.parse("01-0123456789abcdef0123456789abcdef-0123456789abcdef-01").isEmpty());
        assertTrue(TraceContextCodec.parse("00-00000000000000000000000000000000-0123456789abcdef-01").isEmpty());
        assertTrue(TraceContextCodec.parse("00-0123456789abcdef0123456789abcdef-0000000000000000-01").isEmpty());
        assertTrue(TraceContextCodec.parse(TRACEPARENT + "-oversized").isEmpty());
    }

    @Test
    void relaySanitizerForwardsOnlyCanonicalW3cTraceparents() {
        assertEquals(TRACEPARENT, TraceContextCodec.sanitizeForRelay(TRACEPARENT).orElseThrow());
        assertTrue(TraceContextCodec.sanitizeForRelay("legacy-trace-id").isEmpty());
        assertTrue(TraceContextCodec.sanitizeForRelay(" ").isEmpty());
        assertTrue(TraceContextCodec.sanitizeForRelay(TRACEPARENT + "-oversized").isEmpty());
    }

    @Test
    void metricsHaveOnlyFiniteContentFreeTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MemoryMetrics(registry);

        metrics.record("resolve", "accepted", "java.grpc", "grpc", SENTINEL, TRACEPARENT, 1L);

        var tags = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .toList();
        assertTrue(tags.stream().allMatch(tag -> MemoryObservationConvention.allowedTagKeys().contains(tag.getKey())));
        assertTrue(tags.stream().allMatch(tag -> MemoryObservationConvention.allowedTagValues(tag.getKey()).contains(tag.getValue())));
        assertFalse(tags.toString().contains(SENTINEL));
        assertFalse(tags.toString().contains(TRACEPARENT));
    }

    @Test
    void absentTraceIsNotMislabelledAsMalformed() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MemoryMetrics(registry);

        metrics.record("project", "accepted", "java.neo4j", "neo4j", "none", null, 0L);

        assertTrue(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> "diagnostic_code".equals(tag.getKey()))
                .allMatch(tag -> "none".equals(tag.getValue())));
    }

    @Test
    void malformedCarrierUsesFiniteInvalidTraceparentDiagnosticWithoutExportingIt() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MemoryMetrics(registry);

        metrics.record("publish", "accepted", "java.redis", "redis", "none", "legacy-trace-id", 0L);

        var tags = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .toList();
        assertTrue(tags.stream().filter(tag -> "diagnostic_code".equals(tag.getKey()))
                .allMatch(tag -> "invalid_traceparent".equals(tag.getValue())));
        assertFalse(tags.toString().contains("legacy-trace-id"));
    }
}
