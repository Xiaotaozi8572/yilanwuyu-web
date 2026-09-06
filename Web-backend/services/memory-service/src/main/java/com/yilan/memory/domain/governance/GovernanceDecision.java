package com.yilan.memory.domain.governance;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, append-only decision evidence for one candidate. */
public record GovernanceDecision(
        Decision decision,
        String ruleSetVersion,
        List<String> reasonCodes,
        Instant decidedAt) {

    public GovernanceDecision {
        Objects.requireNonNull(decision, "decision");
        if (ruleSetVersion == null || !ruleSetVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("ruleSetVersion");
        }
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
        if (reasonCodes.isEmpty() || reasonCodes.stream().anyMatch(
                code -> code == null || !code.matches("[A-Z0-9_]{1,96}"))) {
            throw new IllegalArgumentException("reasonCodes");
        }
        Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public static GovernanceDecision accepted(String version, Instant at) {
        return new GovernanceDecision(Decision.ACCEPTED, version, List.of("RULES_SATISFIED"), at);
    }

    public static GovernanceDecision pending(String version, String reason, Instant at) {
        return new GovernanceDecision(Decision.PENDING, version, List.of(reason), at);
    }

    public static GovernanceDecision rejected(String version, String reason, Instant at) {
        return new GovernanceDecision(Decision.REJECTED, version, List.of(reason), at);
    }

    public enum Decision {
        ACCEPTED,
        REJECTED,
        PENDING
    }
}
