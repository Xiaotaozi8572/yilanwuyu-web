package com.yilan.memory.config;

import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed, versioned governance and retrieval configuration.  This value is
 * assembled by an outer configuration adapter; domain rules receive this
 * value directly and never inspect Spring Environment or system properties.
 */
public record MemoryProperties(
        String ruleSetVersion,
        Map<MemoryType, TypeRule> typeRules,
        RetrievalWeights retrievalWeights,
        int rrfK,
        Budgets budgets,
        WorkerSettings worker,
        SourceMaterialSettings sourceMaterial) {

    public MemoryProperties(
            String ruleSetVersion,
            Map<MemoryType, TypeRule> typeRules,
            RetrievalWeights retrievalWeights,
            int rrfK,
            Budgets budgets) {
        this(ruleSetVersion, typeRules, retrievalWeights, rrfK, budgets, WorkerSettings.defaults(),
                SourceMaterialSettings.defaults());
    }

    public MemoryProperties(
            String ruleSetVersion,
            Map<MemoryType, TypeRule> typeRules,
            RetrievalWeights retrievalWeights,
            int rrfK,
            Budgets budgets,
            WorkerSettings worker) {
        this(ruleSetVersion, typeRules, retrievalWeights, rrfK, budgets, worker, SourceMaterialSettings.defaults());
    }

    public MemoryProperties {
        if (ruleSetVersion == null || !ruleSetVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("ruleSetVersion");
        }
        Objects.requireNonNull(typeRules, "typeRules");
        var copiedRules = new EnumMap<MemoryType, TypeRule>(MemoryType.class);
        copiedRules.putAll(typeRules);
        for (var type : MemoryType.values()) {
            if (!copiedRules.containsKey(type) || copiedRules.get(type) == null) {
                throw new IllegalArgumentException("typeRules must include " + type);
            }
        }
        typeRules = Map.copyOf(copiedRules);
        Objects.requireNonNull(retrievalWeights, "retrievalWeights");
        if (rrfK <= 0) {
            throw new IllegalArgumentException("rrfK must be positive");
        }
        Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(sourceMaterial, "sourceMaterial");
    }

    public TypeRule ruleFor(MemoryType type) {
        return typeRules.get(Objects.requireNonNull(type, "type"));
    }

    public record TypeRule(int minimumSources, BigDecimal minimumConfidence, Duration expiry) {

        public TypeRule {
            if (minimumSources < 1) {
                throw new IllegalArgumentException("minimumSources must be positive");
            }
            minimumConfidence = boundedUnit(minimumConfidence, "minimumConfidence");
            Objects.requireNonNull(expiry, "expiry");
            if (expiry.isZero() || expiry.isNegative()) {
                throw new IllegalArgumentException("expiry must be positive");
            }
        }
    }

    public record RetrievalWeights(
            BigDecimal structured,
            BigDecimal keyword,
            BigDecimal vector,
            BigDecimal recentEpisode) {

        public RetrievalWeights {
            structured = nonNegative(structured, "structured");
            keyword = nonNegative(keyword, "keyword");
            vector = nonNegative(vector, "vector");
            recentEpisode = nonNegative(recentEpisode, "recentEpisode");
        }
    }

    public record Budgets(int maxItems, int maxEstimatedTokens) {

        public Budgets {
            if (maxItems <= 0 || maxEstimatedTokens <= 0) {
                throw new IllegalArgumentException("budgets must be positive");
            }
        }
    }

    /** Typed, no-secret worker limits. Defaults are intentionally fail-closed. */
    public record WorkerSettings(
            String schemaVersion,
            Duration backgroundDeadline,
            int maxSourceBytes,
            int maxResponseBytes,
            int maxProposals,
            Set<MemoryType> allowedCandidateTypes) {

        public WorkerSettings(
                String schemaVersion,
                Duration backgroundDeadline,
                int maxSourceBytes,
                int maxProposals,
                Set<MemoryType> allowedCandidateTypes) {
            this(schemaVersion, backgroundDeadline, maxSourceBytes, 16_384, maxProposals, allowedCandidateTypes);
        }

        public WorkerSettings {
            if (schemaVersion == null || !schemaVersion.matches("[A-Za-z0-9._-]{1,16}")) {
                throw new IllegalArgumentException("schemaVersion");
            }
            Objects.requireNonNull(backgroundDeadline, "backgroundDeadline");
            if (backgroundDeadline.isZero() || backgroundDeadline.isNegative()) {
                throw new IllegalArgumentException("backgroundDeadline");
            }
            if (maxSourceBytes < 1 || maxSourceBytes > 4_096) {
                throw new IllegalArgumentException("maxSourceBytes must be within [1,4096]");
            }
            if (maxResponseBytes < 1 || maxResponseBytes > 16_384) {
                throw new IllegalArgumentException("maxResponseBytes must be within [1,16384]");
            }
            if (maxProposals < 1 || maxProposals > 6) {
                throw new IllegalArgumentException("maxProposals must be within [1,6]");
            }
            allowedCandidateTypes = Set.copyOf(Objects.requireNonNull(allowedCandidateTypes, "allowedCandidateTypes"));
            if (allowedCandidateTypes.isEmpty()
                    || allowedCandidateTypes.contains(MemoryType.AVIATION_FACT)
                    || allowedCandidateTypes.contains(MemoryType.OPTIMIZATION)) {
                throw new IllegalArgumentException("allowedCandidateTypes");
            }
        }

        public static WorkerSettings defaults() {
            return new WorkerSettings(
                    "v1", Duration.ofSeconds(1), 4_096, 16_384, 6, Set.of(MemoryType.PREFERENCE));
        }

    }

    /**
     * No-secret limits for the M3 local-demo/test source-material bridge.
     * The reader rejects data outside this window before exposing any text to
     * the background worker.
     */
    public record SourceMaterialSettings(Duration localDemoMaximumAge) {

        public SourceMaterialSettings {
            Objects.requireNonNull(localDemoMaximumAge, "localDemoMaximumAge");
            if (localDemoMaximumAge.isZero() || localDemoMaximumAge.isNegative()) {
                throw new IllegalArgumentException("localDemoMaximumAge must be positive");
            }
        }

        public static SourceMaterialSettings defaults() {
            return new SourceMaterialSettings(Duration.ofHours(24));
        }
    }

    private static BigDecimal boundedUnit(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be within [0,1]");
        }
        return value;
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }
}
