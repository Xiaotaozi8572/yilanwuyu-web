package com.yilan.memory.observability;

import java.util.Optional;
import java.util.regex.Pattern;

/** Strict W3C version-00 parser that never retains malformed carrier content. */
public final class TraceContextCodec {

    private static final Pattern TRACEPARENT = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-(00|01)$");

    private TraceContextCodec() {
    }

    public static Optional<TraceLink> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var match = TRACEPARENT.matcher(value);
        if (!match.matches() || "0".repeat(32).equals(match.group(1)) || "0".repeat(16).equals(match.group(2))) {
            return Optional.empty();
        }
        return Optional.of(new TraceLink(match.group(1), match.group(2), match.group(3)));
    }

    /**
     * Produces the only trace carrier value that may cross an asynchronous or
     * worker boundary. Legacy correlation identifiers remain accepted by
     * ingress, but are deliberately not relayed as tracing parents or links.
     */
    public static Optional<String> sanitizeForRelay(String value) {
        return parse(value).map(TraceLink::format);
    }

    public record TraceLink(String traceId, String spanId, String flags) {
        public String format() {
            return "00-" + traceId + "-" + spanId + "-" + flags;
        }
    }
}
