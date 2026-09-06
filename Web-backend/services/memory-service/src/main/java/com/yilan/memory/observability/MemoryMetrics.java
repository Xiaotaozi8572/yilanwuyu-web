package com.yilan.memory.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Best-effort bounded metrics. Trace values are parsed only as parent/link records, never as tags. */
@Component
public final class MemoryMetrics {

    private final MeterRegistry registry;
    private final boolean enabled;

    public MemoryMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.enabled = true;
    }

    private MemoryMetrics() {
        this.registry = null;
        this.enabled = false;
    }

    public static MemoryMetrics noop() {
        return new MemoryMetrics();
    }

    public void record(
            String operation,
            String status,
            String stage,
            String channel,
            String diagnosticCode,
            String traceparent,
            long durationMillis) {
        try {
            if (!enabled) {
                return;
            }
            var resolvedDiagnostic = traceparent != null && TraceContextCodec.parse(traceparent).isEmpty()
                    ? "invalid_traceparent" : diagnosticCode;
            var tags = MemoryObservationConvention.tags(operation, status, stage, channel, resolvedDiagnostic);
            Counter.builder("memory.operation.total").tags(tags).register(registry).increment();
            Timer.builder("memory.operation.duration").tags(tags).register(registry)
                    .record(Duration.ofMillis(Math.max(0L, durationMillis)));
        } catch (RuntimeException ignored) {
            // Observation failures are intentionally unable to affect memory behavior.
        }
    }

    public Optional<TraceContextCodec.TraceLink> link(String traceparent) {
        try {
            return TraceContextCodec.parse(traceparent);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
