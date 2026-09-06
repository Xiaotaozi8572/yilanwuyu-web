package com.yilan.memory.application.context;

import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.adapter.cache.ContextCache;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.identity.LearnerIdentity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContextResolutionTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final LearnerIdentity IDENTITY = new LearnerIdentity("learner-a", "session-a", 3);

    @Test
    void graphHintBindsBothEndpointsToStableAuthorityAssertionAndVersionIds() {
        assertThat(ResolveMemoryContextUseCase.GraphHint.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "relationEventId", "fromAssertionId", "fromVersionId",
                        "toAssertionId", "toVersionId", "effectiveFrom", "effectiveUntil");
    }

    @Test
    void deniedConsentReturnsEmptyWithoutAdmittingAnyChannel() {
        var channel = new RecordingChannel("structured", List.of(memory("required", MemoryType.PREFERENCE, 80)));
        var useCase = useCase(deniedPolicy(), List.of(channel));

        var resolution = useCase.resolve(IDENTITY, query(null));

        assertThat(resolution.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.EMPTY);
        assertThat(resolution.items()).isEmpty();
        assertThat(channel.calls()).isZero();
    }

    @Test
    void missingConsentReturnsEmptyBeforeAnyCacheOrRetrievalCanReenableMemory() {
        var channel = new RecordingChannel("structured", List.of(memory("required", MemoryType.PREFERENCE, 80)));
        var useCase = useCase(ConsentPolicy.missing(ConsentPolicy.SubjectStatus.ACTIVE), List.of(channel));

        var resolution = useCase.resolve(IDENTITY, query(null));

        assertThat(resolution.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.EMPTY);
        assertThat(resolution.items()).isEmpty();
        assertThat(channel.calls()).isZero();
    }

    @Test
    void hardFiltersRemoveExpiredConflictingHighPrivacyUnconfirmedAndUnclosedItems() {
        var usable = memory("usable", MemoryType.PREFERENCE, 90);
        var expired = usable.withValidity(NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)));
        var highPrivacy = usable.withPrivacy(PrivacyLevel.HIGH);
        var unconfirmed = usable.withConfirmationStatus("PENDING");
        var unclosed = usable.withSourceClosed(false);
        var conflictOne = usable.withMemoryId(UUID.randomUUID()).withConflictGroup("preference-conflict");
        var conflictTwo = usable.withMemoryId(UUID.randomUUID()).withConflictGroup("preference-conflict").withScore(new BigDecimal("0.01"));
        var channel = new RecordingChannel("structured", List.of(
                usable, expired, highPrivacy, unconfirmed, unclosed, conflictOne, conflictTwo));
        var useCase = useCase(activePolicy(), List.of(channel));

        var resolution = useCase.resolve(IDENTITY, query(null));

        assertThat(resolution.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.APPLIED);
        assertThat(resolution.items()).extracting(RetrievalChannel.RankedMemory::valueJson)
                .contains("{\"value\":\"usable\"}")
                .doesNotContain("{\"value\":\"expired\"}", "{\"value\":\"high\"}");
        assertThat(resolution.items()).filteredOn(item -> "preference-conflict".equals(item.conflictGroup())).hasSize(1);
        assertThat(resolution.items()).allSatisfy(item -> {
            assertThat(item.privacyLevel()).isNotEqualTo(PrivacyLevel.HIGH);
            assertThat(item.sourceClosed()).isTrue();
            assertThat(item.confirmationStatus()).isEqualTo("CONFIRMED");
            assertThat(item.validUntil()).isAfter(NOW);
        });
    }

    @Test
    void requiredItemsPrecedeOptionalAndBudgetNeverExceedsTypedLimits() {
        var required = memory("required", MemoryType.PREFERENCE, 600).withScore(new BigDecimal("0.01"));
        var optional = memory("optional", MemoryType.MASTERY, 500).withScore(new BigDecimal("0.99"));
        var anotherOptional = memory("another", MemoryType.MISCONCEPTION, 500).withScore(new BigDecimal("0.98"));
        var useCase = useCase(activePolicy(), List.of(new RecordingChannel("structured", List.of(required, optional, anotherOptional))));

        var resolution = useCase.resolve(IDENTITY, query(null));

        assertThat(resolution.items()).extracting(RetrievalChannel.RankedMemory::memoryType)
                .containsExactly(MemoryType.PREFERENCE);
        assertThat(resolution.items().getFirst().useClass()).isEqualTo(RetrievalChannel.UseClass.REQUIRED);
        assertThat(resolution.items()).hasSizeLessThanOrEqualTo(properties().budgets().maxItems());
        assertThat(resolution.items().stream().mapToInt(RetrievalChannel.RankedMemory::estimatedTokens).sum())
                .isLessThanOrEqualTo(properties().budgets().maxEstimatedTokens());
    }

    @Test
    void hardCapsItemsAndTokensWhenConfigurationAndRequestAreBothLarger() {
        var candidates = new ArrayList<RetrievalChannel.RankedMemory>();
        for (var index = 0; index < 10; index++) {
            candidates.add(memory("cap-" + index, MemoryType.PREFERENCE, 150));
        }
        var useCase = useCase(properties(20, 2_000), activePolicy(),
                List.of(new RecordingChannel("structured", candidates)));

        var resolution = useCase.resolve(IDENTITY, query(null, 20, 2_000));

        assertThat(resolution.items()).hasSizeLessThanOrEqualTo(8);
        assertThat(resolution.items().stream().mapToInt(RetrievalChannel.RankedMemory::estimatedTokens).sum())
                .isLessThanOrEqualTo(900);
    }

    @Test
    void admittedTechnicalFailureClearsEveryUsableItemAndReturnsDegraded() {
        var working = new RecordingChannel("structured", List.of(memory("usable", MemoryType.PREFERENCE, 80)));
        var broken = new RetrievalChannel() {
            @Override
            public String name() {
                return "keyword";
            }

            @Override
            public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
                throw new IllegalStateException("database failure must not leak partial context");
            }
        };
        var useCase = useCase(activePolicy(), List.of(working, broken));

        var resolution = useCase.resolve(IDENTITY, query(null));

        assertThat(resolution.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.DEGRADED);
        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.diagnosticCode()).isEqualTo("CHANNEL_FAILURE");
    }

    @Test
    void graphCircuitOpenOmitsOptionalHintsWithoutChangingPostgresBaseContext() {
        var structured = new RecordingChannel("structured", List.of(memory("usable", MemoryType.PREFERENCE, 80)));
        var graph = new ResolveMemoryContextUseCase.GraphHintReader() {
            @Override
            public ResolveMemoryContextUseCase.GraphReadResult read(
                    RetrievalChannel.AuthorizedMemoryQuery query,
                    java.time.Duration remainingBudget) {
                return ResolveMemoryContextUseCase.GraphReadResult.unavailable();
            }
        };
        var useCase = useCase(properties(), activePolicy(), List.of(structured), graph);

        var resolution = useCase.resolve(IDENTITY, query(null, 8, 900, false));

        assertThat(resolution.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.APPLIED);
        assertThat(resolution.items()).isNotEmpty();
        assertThat(resolution.omittedChannels()).contains("GRAPH");
    }

    @Test
    void graphCircuitOpenReturnsExistingDegradedEmptyContextWhenGraphIsRequired() {
        var structured = new RecordingChannel("structured", List.of(memory("usable", MemoryType.PREFERENCE, 80)));
        var graph = (ResolveMemoryContextUseCase.GraphHintReader) (query, remainingBudget) ->
                ResolveMemoryContextUseCase.GraphReadResult.unavailable();
        var useCase = useCase(properties(), activePolicy(), List.of(structured), graph);

        var resolution = useCase.resolve(IDENTITY, query(null, 8, 900, true));

        assertThat(resolution.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.DEGRADED);
        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.diagnosticCode()).isEqualTo("CHANNEL_FAILURE");
    }

    @Test
    void invalidEmbeddingOmitsOnlySemanticChannelAndKeepsOtherChannelsUsable() {
        var structured = new RecordingChannel("structured", List.of(memory("usable", MemoryType.PREFERENCE, 80)));
        var vector = new RecordingChannel("vector", List.of(memory("semantic", MemoryType.REFLECTION, 80)));
        var useCase = useCase(activePolicy(), List.of(structured, vector));
        var invalid = new ResolveMemoryContextUseCase.QueryEmbedding(
                List.of(1.0f, 0.0f), "bge-m3", "v1", 2, "L2");

        var resolution = useCase.resolve(IDENTITY, query(invalid));

        assertThat(resolution.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.APPLIED);
        assertThat(resolution.items()).extracting(RetrievalChannel.RankedMemory::valueJson)
                .containsExactly("{\"value\":\"usable\"}");
        assertThat(resolution.omittedChannels()).containsExactly("vector");
        assertThat(vector.calls()).isZero();
    }

    @Test
    void mismatchedEmbeddingProfileOmitsOnlySemanticChannelAndKeepsOtherChannelsUsable() {
        var structured = new RecordingChannel("structured", List.of(memory("usable", MemoryType.PREFERENCE, 80)));
        var vector = new RetrievalChannel() {
            @Override
            public String name() {
                return "vector";
            }

            @Override
            public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
                throw new RetrievalChannel.SemanticUnavailableException("EMBEDDING_PROFILE_MISMATCH");
            }
        };
        var useCase = useCase(activePolicy(), List.of(structured, vector));

        var resolution = useCase.resolve(IDENTITY, query(normalizedEmbedding()));

        assertThat(resolution.status()).isEqualTo(ResolveMemoryContextUseCase.ResolutionStatus.APPLIED);
        assertThat(resolution.items()).extracting(RetrievalChannel.RankedMemory::valueJson)
                .containsExactly("{\"value\":\"usable\"}");
        assertThat(resolution.omittedChannels()).containsExactly("vector");
    }

    @Test
    void unsafeCachedSensitiveResolutionIsDiscardedAndFreshSensitiveResultIsNotWrittenBack() {
        var sensitive = memory("sensitive", MemoryType.PREFERENCE, 80).withPrivacy(PrivacyLevel.SENSITIVE);
        var unsafeCached = new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.APPLIED, List.of(sensitive), List.of(), null);
        var cache = new RecordingContextCache(Optional.of(unsafeCached));
        var channel = new RecordingChannel("structured", List.of(sensitive));
        var useCase = useCase(properties(), activePolicy(), List.of(channel),
                ResolveMemoryContextUseCase.GraphHintReader.disabled(), cache);

        var resolution = useCase.resolve(IDENTITY, query(null));

        assertThat(resolution.items()).singleElement()
                .extracting(RetrievalChannel.RankedMemory::privacyLevel)
                .isEqualTo(PrivacyLevel.SENSITIVE);
        assertThat(channel.calls()).isEqualTo(1);
        assertThat(cache.putCalls()).isZero();
    }

    @Test
    void rrfUsesConfiguredWeightOverConfiguredKPlusOneBasedRank() {
        var item = memory("rrf", MemoryType.PREFERENCE, 80);
        var fused = new RrfFusion(properties()).fuse(List.of(
                new RrfFusion.ChannelResult("structured", List.of(item)),
                new RrfFusion.ChannelResult("keyword", List.of(item))));

        var expected = properties().retrievalWeights().structured()
                .divide(BigDecimal.valueOf(properties().rrfK() + 1), MathContext.DECIMAL128)
                .add(properties().retrievalWeights().keyword()
                        .divide(BigDecimal.valueOf(properties().rrfK() + 1), MathContext.DECIMAL128));
        assertThat(fused).singleElement()
                .satisfies(result -> assertThat(result.score()).isEqualByComparingTo(expected));
    }

    private ResolveMemoryContextUseCase useCase(
            ConsentPolicy policy,
            List<? extends RetrievalChannel> channels) {
        return useCase(properties(), policy, channels);
    }

    private ResolveMemoryContextUseCase useCase(
            MemoryProperties properties,
            ConsentPolicy policy,
            List<? extends RetrievalChannel> channels) {
        return useCase(properties, policy, channels, ResolveMemoryContextUseCase.GraphHintReader.disabled());
    }

    private ResolveMemoryContextUseCase useCase(
            MemoryProperties properties,
            ConsentPolicy policy,
            List<? extends RetrievalChannel> channels,
            ResolveMemoryContextUseCase.GraphHintReader graphHintReader) {
        return useCase(properties, policy, channels, graphHintReader, ContextCache.disabled());
    }

    private ResolveMemoryContextUseCase useCase(
            MemoryProperties properties,
            ConsentPolicy policy,
            List<? extends RetrievalChannel> channels,
            ResolveMemoryContextUseCase.GraphHintReader graphHintReader,
            ContextCache contextCache) {
        ConsentQuery.PolicyReader reader = subject -> "learner-a".equals(subject)
                ? java.util.Optional.of(policy)
                : java.util.Optional.empty();
        return new ResolveMemoryContextUseCase(
                properties, reader, Clock.fixed(NOW, ZoneOffset.UTC), List.copyOf(channels), graphHintReader, contextCache);
    }

    private ResolveMemoryContextUseCase.MemoryQuery query(ResolveMemoryContextUseCase.QueryEmbedding embedding) {
        return query(embedding, 8, 900);
    }

    private ResolveMemoryContextUseCase.MemoryQuery query(
            ResolveMemoryContextUseCase.QueryEmbedding embedding,
            int maxItems,
            int maxEstimatedTokens) {
        return query(embedding, maxItems, maxEstimatedTokens, false);
    }

    private ResolveMemoryContextUseCase.MemoryQuery query(
            ResolveMemoryContextUseCase.QueryEmbedding embedding,
            int maxItems,
            int maxEstimatedTokens,
            boolean graphRequired) {
        return new ResolveMemoryContextUseCase.MemoryQuery(
                "explanation style", "TEACHING", Set.of(
                MemoryType.PREFERENCE, MemoryType.MASTERY, MemoryType.MISCONCEPTION, MemoryType.REFLECTION),
                Set.of(MemoryType.PREFERENCE), maxItems, maxEstimatedTokens, embedding,
                java.time.Duration.ofMillis(20), graphRequired);
    }

    private static ConsentPolicy activePolicy() {
        return new ConsentPolicy(
                ConsentPolicy.SubjectStatus.ACTIVE,
                ConsentPolicy.PolicyStatus.ACTIVE,
                3,
                Set.of(MemoryCategory.PREFERENCE, MemoryCategory.MASTERY,
                        MemoryCategory.MISCONCEPTION, MemoryCategory.REFLECTION),
                Instant.EPOCH,
                NOW.plus(Duration.ofDays(1)));
    }

    private static ConsentPolicy deniedPolicy() {
        return new ConsentPolicy(
                ConsentPolicy.SubjectStatus.ACTIVE,
                ConsentPolicy.PolicyStatus.ACTIVE,
                3,
                Set.of(), Instant.EPOCH, NOW.plus(Duration.ofDays(1)));
    }

    private static MemoryProperties properties() {
        return properties(8, 900);
    }

    private static MemoryProperties properties(int maxItems, int maxEstimatedTokens) {
        var rules = new EnumMap<MemoryType, MemoryProperties.TypeRule>(MemoryType.class);
        for (var type : MemoryType.values()) {
            rules.put(type, new MemoryProperties.TypeRule(1, new BigDecimal("0.70"), Duration.ofDays(30)));
        }
        return new MemoryProperties(
                "rules-context-v1", rules,
                new MemoryProperties.RetrievalWeights(
                        new BigDecimal("0.30"), new BigDecimal("0.20"),
                        new BigDecimal("0.50"), new BigDecimal("0.10")),
                60, new MemoryProperties.Budgets(maxItems, maxEstimatedTokens));
    }

    private static RetrievalChannel.RankedMemory memory(String value, MemoryType type, int tokens) {
        return new RetrievalChannel.RankedMemory(
                UUID.randomUUID(), UUID.randomUUID(), type, "{\"value\":\"" + value + "\"}",
                new BigDecimal("0.90"), new BigDecimal("0.80"), NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(1)), NOW.minus(Duration.ofHours(1)), null, tokens, true,
                PrivacyLevel.STANDARD, "ACTIVE", "CONFIRMED", List.of(UUID.randomUUID().toString()), null,
                new BigDecimal("0.50"), RetrievalChannel.UseClass.OPTIONAL);
    }

    private static ResolveMemoryContextUseCase.QueryEmbedding normalizedEmbedding() {
        var values = new ArrayList<Float>(1024);
        values.add(1.0f);
        while (values.size() < 1024) {
            values.add(0.0f);
        }
        return new ResolveMemoryContextUseCase.QueryEmbedding(values, "bge-m3", "v1", 1024, "L2");
    }

    private static final class RecordingChannel implements RetrievalChannel {
        private final String name;
        private final List<RankedMemory> results;
        private int calls;

        private RecordingChannel(String name, List<RankedMemory> results) {
            this.name = name;
            this.results = List.copyOf(results);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
            calls++;
            return results;
        }

        int calls() {
            return calls;
        }
    }

    private static final class RecordingContextCache implements ContextCache {
        private final Optional<ResolveMemoryContextUseCase.MemoryResolution> cached;
        private int putCalls;

        private RecordingContextCache(Optional<ResolveMemoryContextUseCase.MemoryResolution> cached) {
            this.cached = cached;
        }

        @Override
        public Optional<ResolveMemoryContextUseCase.MemoryResolution> find(
                LearnerIdentity identity,
                ResolveMemoryContextUseCase.MemoryQuery query) {
            return cached;
        }

        @Override
        public void put(
                LearnerIdentity identity,
                ResolveMemoryContextUseCase.MemoryQuery query,
                ResolveMemoryContextUseCase.MemoryResolution resolution) {
            putCalls++;
        }

        int putCalls() {
            return putCalls;
        }
    }
}
