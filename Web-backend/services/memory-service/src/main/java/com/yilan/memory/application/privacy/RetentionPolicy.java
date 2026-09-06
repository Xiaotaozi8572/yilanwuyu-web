package com.yilan.memory.application.privacy;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Versioned, finite maximum-retention policy. Consent can only shorten it. */
@Component
public final class RetentionPolicy {

    public static final String VERSION = "retention-v1";
    private static final Duration MAXIMUM_CONSENT = Duration.ofDays(180);
    private static final Map<String, Duration> MAXIMA = Map.of(
            "RAW_INTERACTION", Duration.ofDays(30),
            "DLQ_PAYLOAD", Duration.ofDays(7),
            "EPISODIC", Duration.ofDays(180),
            "PREFERENCE", Duration.ofDays(180),
            "GOAL", Duration.ofDays(180),
            "MASTERY", Duration.ofDays(90),
            "MISCONCEPTION", Duration.ofDays(90),
            "REFLECTION", Duration.ofDays(90),
            "AUDIT", Duration.ofDays(365));

    public Duration maximumFor(String category) {
        var duration = MAXIMA.get(Objects.requireNonNull(category, "category"));
        if (duration == null) {
            throw new IllegalArgumentException("retention category");
        }
        return duration;
    }

    public Duration effectiveFor(String memoryType, Integer consentDays) {
        var maximum = maximumFor(Objects.requireNonNull(memoryType, "memoryType"));
        if (consentDays == null) {
            return maximum;
        }
        if (consentDays < 1 || Duration.ofDays(consentDays).compareTo(MAXIMUM_CONSENT) > 0) {
            throw new IllegalArgumentException("consent retentionDays");
        }
        return maximum.compareTo(Duration.ofDays(consentDays)) <= 0 ? maximum : Duration.ofDays(consentDays);
    }
}
