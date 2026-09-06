package com.yilan.memory.domain.governance;

import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.GovernanceDecision.Decision;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceRuleTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

    @ParameterizedTest
    @MethodSource("ruleMatrix")
    void appliesTheVersionedTypeRuleMatrix(
            String ignoredName,
            MemoryCandidate candidate,
            List<MemoryCandidate.ValidatedSource> sources,
            Decision expectedDecision,
            String expectedReason) {
        var decision = new PromotionRule(properties()).govern(candidate, sources, NOW);

        assertThat(decision.decision()).isEqualTo(expectedDecision);
        assertThat(decision.reasonCodes()).contains(expectedReason);
        assertThat(decision.ruleSetVersion()).isEqualTo("rules-2026-07-19");
    }

    @org.junit.jupiter.api.Test
    void exposesTypedRetrievalAndBudgetConfigurationWithoutDomainEnvironmentAccess() {
        var properties = properties();

        assertThat(properties.ruleSetVersion()).isEqualTo("rules-2026-07-19");
        assertThat(properties.ruleFor(MemoryType.MISCONCEPTION).minimumSources()).isEqualTo(2);
        assertThat(properties.retrievalWeights().vector()).isEqualByComparingTo("0.50");
        assertThat(properties.rrfK()).isEqualTo(60);
        assertThat(properties.budgets().maxItems()).isEqualTo(8);
        assertThat(properties.budgets().maxEstimatedTokens()).isEqualTo(900);
    }

    @ParameterizedTest
    @MethodSource("validSafeValueSchemas")
    void constructorAcceptsEachClosedSafeMemoryValueSchema(MemoryType type, String valueJson) {
        var candidate = candidate(type, PrivacyLevel.STANDARD, 0.90, List.of(source()), valueJson);

        assertThat(candidate.valueJson()).isEqualTo(valueJson);
    }

    @ParameterizedTest
    @MethodSource("invalidSafeValueSchemas")
    void constructorRejectsUnknownExtraMalformedAndUnsafeSafeMemoryValues(MemoryType type, String valueJson) {
        assertThatThrownBy(() -> candidate(type, PrivacyLevel.STANDARD, 0.90, List.of(source()), valueJson))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    void unsafeTypesAcceptOnlyTheEmptyGovernanceSentinelSoExistingDecisionBranchesRemainReachable() {
        for (var type : List.of(MemoryType.AVIATION_FACT, MemoryType.OPTIMIZATION)) {
            assertThat(candidate(type, PrivacyLevel.STANDARD, 0.90, List.of(source()), "{}").valueJson())
                    .isEqualTo("{}");
        }
    }

    @ParameterizedTest
    @MethodSource("effectivePrivacyMatrix")
    void recomputesTheStrictestPrivacyFromCandidateAndEveryValidatedSource(
            PrivacyLevel candidatePrivacy,
            List<PrivacyLevel> authorityPrivacy,
            PrivacyLevel expectedPrivacy,
            Decision expectedDecision) {
        var sources = authorityPrivacy.stream().map(ignored -> source()).toList();
        var validatedSources = java.util.stream.IntStream.range(0, sources.size())
                .mapToObj(index -> new MemoryCandidate.ValidatedSource(
                        sources.get(index), SourceKind.EXPLICIT_DECLARATION, NOW, authorityPrivacy.get(index)))
                .toList();
        var candidate = candidate(MemoryType.PREFERENCE, candidatePrivacy, 0.90, sources);

        assertThat(MemoryCandidate.effectivePrivacyLevel(candidate.privacyLevel(), validatedSources))
                .isEqualTo(expectedPrivacy);
        assertThat(new PromotionRule(properties()).govern(candidate, validatedSources, NOW).decision())
                .isEqualTo(expectedDecision);
    }

    @ParameterizedTest
    @MethodSource("nonEmptyUnsafeValueSchemas")
    void constructorRejectsUnsafeTypeKeysAndFreeText(MemoryType type, String valueJson) {
        assertThatThrownBy(() -> candidate(type, PrivacyLevel.STANDARD, 0.90, List.of(source()), valueJson))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> ruleMatrix() {
        return Stream.of(
                ruleCase("explicit preference", MemoryType.PREFERENCE, PrivacyLevel.STANDARD, 0.90,
                        List.of(SourceKind.EXPLICIT_DECLARATION), Decision.ACCEPTED, "RULES_SATISFIED"),
                ruleCase("mastery needs scored source", MemoryType.MASTERY, PrivacyLevel.STANDARD, 0.90,
                        List.of(SourceKind.GENERAL), Decision.PENDING, "MASTERY_REQUIRES_SCORED_SOURCE"),
                ruleCase("mastery accepts a scored source", MemoryType.MASTERY, PrivacyLevel.STANDARD, 0.90,
                        List.of(SourceKind.SCORED_ASSESSMENT), Decision.ACCEPTED, "RULES_SATISFIED"),
                ruleCase("one inferred misconception remains pending", MemoryType.MISCONCEPTION, PrivacyLevel.STANDARD, 0.90,
                        List.of(SourceKind.GENERAL), Decision.PENDING, "INSUFFICIENT_INDEPENDENT_SOURCES"),
                ruleCase("repeated independent misconception evidence activates", MemoryType.MISCONCEPTION, PrivacyLevel.STANDARD, 0.90,
                        List.of(SourceKind.GENERAL, SourceKind.GENERAL), Decision.ACCEPTED, "RULES_SATISFIED"),
                ruleCase("reflection needs two episodes", MemoryType.REFLECTION, PrivacyLevel.STANDARD, 0.90,
                        List.of(SourceKind.EPISODE, SourceKind.GENERAL), Decision.PENDING, "REFLECTION_REQUIRES_TWO_EPISODES"),
                ruleCase("reflection with two episodes activates", MemoryType.REFLECTION, PrivacyLevel.STANDARD, 0.90,
                        List.of(SourceKind.EPISODE, SourceKind.EPISODE), Decision.ACCEPTED, "RULES_SATISFIED"),
                ruleCase("high privacy is never automatic", MemoryType.PREFERENCE, PrivacyLevel.HIGH, 0.90,
                        List.of(SourceKind.EXPLICIT_DECLARATION), Decision.PENDING, "HIGH_PRIVACY_REQUIRES_HUMAN_REVIEW"),
                ruleCase("aviation facts are forbidden", MemoryType.AVIATION_FACT, PrivacyLevel.STANDARD, 1.00,
                        List.of(SourceKind.GENERAL), Decision.REJECTED, "AVIATION_FACT_FORBIDDEN"),
                ruleCase("optimization always waits for human review", MemoryType.OPTIMIZATION, PrivacyLevel.STANDARD, 1.00,
                        List.of(SourceKind.GENERAL), Decision.PENDING, "OPTIMIZATION_REQUIRES_HUMAN_REVIEW"));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> validSafeValueSchemas() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"answer_style\":\"concise\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.MASTERY, "{\"mastery_level\":\"developing\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.MISCONCEPTION, "{\"misconception_code\":\"lift_drag_confusion\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.REFLECTION, "{\"reflection_code\":\"reviewed_basics\"}"));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidSafeValueSchemas() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"unknown\":\"concise\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"answer_style\":\"concise\",\"extra\":\"x\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "[]"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "\"concise\""),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"answer_style\":\"verbose\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"answer_style\":\"C919 thrust 120kN\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.MASTERY, "{\"mastery_level\":\"expert\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.MISCONCEPTION, "{\"misconception_code\":\"Bad-Code\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.REFLECTION, "{\"reflection_code\":\"bad code\"}"));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> nonEmptyUnsafeValueSchemas() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.AVIATION_FACT, "{\"aircraft\":\"C919\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.AVIATION_FACT, "{\"answer_style\":\"concise\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.AVIATION_FACT, "\"C919 thrust 120kN\""),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.OPTIMIZATION, "{\"parameter\":\"thrust\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.OPTIMIZATION, "{\"answer_style\":\"concise\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.OPTIMIZATION, "\"optimize climb profile\""));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> effectivePrivacyMatrix() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        PrivacyLevel.STANDARD, List.of(PrivacyLevel.STANDARD),
                        PrivacyLevel.STANDARD, Decision.ACCEPTED),
                org.junit.jupiter.params.provider.Arguments.of(
                        PrivacyLevel.STANDARD, List.of(PrivacyLevel.SENSITIVE),
                        PrivacyLevel.SENSITIVE, Decision.ACCEPTED),
                org.junit.jupiter.params.provider.Arguments.of(
                        PrivacyLevel.SENSITIVE, List.of(PrivacyLevel.STANDARD),
                        PrivacyLevel.SENSITIVE, Decision.ACCEPTED),
                org.junit.jupiter.params.provider.Arguments.of(
                        PrivacyLevel.STANDARD, List.of(PrivacyLevel.SENSITIVE, PrivacyLevel.HIGH),
                        PrivacyLevel.HIGH, Decision.PENDING),
                org.junit.jupiter.params.provider.Arguments.of(
                        PrivacyLevel.HIGH, List.of(PrivacyLevel.STANDARD, PrivacyLevel.SENSITIVE),
                        PrivacyLevel.HIGH, Decision.PENDING));
    }

    private static MemoryProperties properties() {
        var rules = new EnumMap<MemoryType, MemoryProperties.TypeRule>(MemoryType.class);
        for (var type : MemoryType.values()) {
            rules.put(type, new MemoryProperties.TypeRule(1, new BigDecimal("0.70"), Duration.ofDays(30)));
        }
        rules.put(MemoryType.MISCONCEPTION, new MemoryProperties.TypeRule(2, new BigDecimal("0.70"), Duration.ofDays(14)));
        rules.put(MemoryType.REFLECTION, new MemoryProperties.TypeRule(2, new BigDecimal("0.70"), Duration.ofDays(7)));
        return new MemoryProperties(
                "rules-2026-07-19",
                rules,
                new MemoryProperties.RetrievalWeights(
                        new BigDecimal("0.30"), new BigDecimal("0.20"),
                        new BigDecimal("0.50"), new BigDecimal("0.10")),
                60,
                new MemoryProperties.Budgets(8, 900));
    }

    private static org.junit.jupiter.params.provider.Arguments ruleCase(
            String name,
            MemoryType type,
            PrivacyLevel privacyLevel,
            double confidence,
            List<SourceKind> sourceKinds,
            Decision expectedDecision,
            String expectedReason) {
        var sources = sourceKinds.stream().map(ignored -> source()).toList();
        var validatedSources = java.util.stream.IntStream.range(0, sources.size())
                .mapToObj(index -> new MemoryCandidate.ValidatedSource(
                        sources.get(index), sourceKinds.get(index), NOW, PrivacyLevel.STANDARD))
                .toList();
        return org.junit.jupiter.params.provider.Arguments.of(
                name, candidate(type, privacyLevel, confidence, sources), validatedSources,
                expectedDecision, expectedReason);
    }

    private static MemoryCandidate candidate(
            MemoryType type,
            PrivacyLevel privacyLevel,
            double confidence,
            List<MemoryCandidate.SourceReference> sources) {
        return candidate(type, privacyLevel, confidence, sources, valueJson(type));
    }

    private static MemoryCandidate candidate(
            MemoryType type,
            PrivacyLevel privacyLevel,
            double confidence,
            List<MemoryCandidate.SourceReference> sources,
            String valueJson) {
        return new MemoryCandidate(
                UUID.randomUUID(),
                "subject-hash",
                3,
                type,
                "assertion-" + UUID.randomUUID(),
                valueJson,
                new BigDecimal(String.format(java.util.Locale.ROOT, "%.2f", confidence)),
                new BigDecimal("0.80"),
                privacyLevel,
                NOW,
                sources,
                "opaque-candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String valueJson(MemoryType type) {
        return switch (type) {
            case PREFERENCE -> "{\"answer_style\":\"concise\"}";
            case MASTERY -> "{\"mastery_level\":\"developing\"}";
            case MISCONCEPTION -> "{\"misconception_code\":\"lift_drag_confusion\"}";
            case REFLECTION -> "{\"reflection_code\":\"reviewed_basics\"}";
            case AVIATION_FACT, OPTIMIZATION -> "{}";
        };
    }

    private static MemoryCandidate.SourceReference source() {
        return new MemoryCandidate.SourceReference(UUID.randomUUID(), "v1");
    }
}
