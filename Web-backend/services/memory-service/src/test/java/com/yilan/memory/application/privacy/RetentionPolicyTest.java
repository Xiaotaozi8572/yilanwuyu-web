package com.yilan.memory.application.privacy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionPolicyTest {

    private final RetentionPolicy policy = new RetentionPolicy();

    @Test
    void hasFiniteNonExtendablePolicyMaximaAndOnlyPermitsShortening() {
        assertThat(policy.maximumFor("RAW_INTERACTION")).isEqualTo(Duration.ofDays(30));
        assertThat(policy.maximumFor("DLQ_PAYLOAD")).isEqualTo(Duration.ofDays(7));
        assertThat(policy.maximumFor("PREFERENCE")).isEqualTo(Duration.ofDays(180));
        assertThat(policy.maximumFor("MASTERY")).isEqualTo(Duration.ofDays(90));
        assertThat(policy.maximumFor("AUDIT")).isEqualTo(Duration.ofDays(365));
        assertThat(policy.effectiveFor("PREFERENCE", 30)).isEqualTo(Duration.ofDays(30));
        assertThatThrownBy(() -> policy.effectiveFor("PREFERENCE", 181))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
