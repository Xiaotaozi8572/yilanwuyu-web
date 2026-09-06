package com.yilan.memory.application.context;

import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.adapter.cache.ContextCache;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.identity.LearnerIdentity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL-only long-term context resolver. Technical failure of any
 * admitted channel fails the whole resolution closed; an invalid embedding is
 * a non-technical semantic-channel omission by contract.
 */
public final class ResolveMemoryContextUseCase {

    private static final int HARD_MAX_CONTEXT_ITEMS = 8;
    private static final int HARD_MAX_CONTEXT_ESTIMATED_TOKENS = 900;

    private final MemoryProperties properties;
    private final ConsentQuery consentQuery;
    private final Clock clock;
    private final List<RetrievalChannel> channels;
    private final RrfFusion fusion;
    private final RuleReranker reranker;
    private final ContextBudgeter budgeter;
    private final GraphHintReader graphHintReader;
    private final ContextCache contextCache;

    public ResolveMemoryContextUseCase(
            MemoryProperties properties,
            ConsentQuery.PolicyReader policyReader,
            Clock clock,
            List<? extends RetrievalChannel> channels) {
        this(properties, policyReader, clock, channels, GraphHintReader.disabled(), ContextCache.disabled());
    }

    public ResolveMemoryContextUseCase(
            MemoryProperties properties,
            ConsentQuery.PolicyReader policyReader,
            Clock clock,
            List<? extends RetrievalChannel> channels,
            GraphHintReader graphHintReader) {
        this(properties, policyReader, clock, channels, graphHintReader, ContextCache.disabled());
    }

    public ResolveMemoryContextUseCase(
            MemoryProperties properties,
            ConsentQuery.PolicyReader policyReader,
            Clock clock,
            List<? extends RetrievalChannel> channels,
            GraphHintReader graphHintReader,
            ContextCache contextCache) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.consentQuery = new ConsentQuery(Objects.requireNonNull(policyReader, "policyReader"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        var names = new HashSet<String>();
        for (var channel : this.channels) {
            if (!names.add(Objects.requireNonNull(channel, "channel").name())) {
                throw new IllegalArgumentException("retrieval channel names must be unique");
            }
        }
        this.fusion = new RrfFusion(properties);
        this.reranker = new RuleReranker();
        this.budgeter = new ContextBudgeter();
        this.graphHintReader = Objects.requireNonNull(graphHintReader, "graphHintReader");
        this.contextCache = Objects.requireNonNull(contextCache, "contextCache");
    }

    public MemoryResolution resolve(LearnerIdentity identity, MemoryQuery query) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(query, "query");
        var asOf = Instant.now(clock);
        // This authority read deliberately precedes every cache touch, so a
        // revoked or missing policy cannot be re-enabled by either cache tier.
        var allowedTypes = consentAllowedTypes(identity, query.allowedTypes(), asOf);
        if (allowedTypes.isEmpty()) {
            return MemoryResolution.empty();
        }
        var cached = cachedResolution(identity, query);
        if (cached.isPresent()) {
            return cached.orElseThrow();
        }
        var requiredTypes = EnumSet.noneOf(MemoryType.class);
        requiredTypes.addAll(query.requiredTypes());
        requiredTypes.retainAll(allowedTypes);
        var effectiveBudget = effectiveBudget(query);
        var embedding = validEmbedding(query.queryEmbedding()) ? query.queryEmbedding() : null;
        var authorized = new RetrievalChannel.AuthorizedMemoryQuery(
                identity.subjectHash(), identity.sessionId(), asOf, allowedTypes, requiredTypes,
                query.queryText(), query.taskType(), embedding, effectiveBudget.maxItems());
        var omitted = new ArrayList<String>();
        var graphRead = readGraphHints(authorized, query.remainingJavaBudget());
        if (!graphRead.available()) {
            if (query.graphRequired()) {
                return MemoryResolution.degraded("CHANNEL_FAILURE", omitted);
            }
            omitted.add("GRAPH");
        }
        var results = new ArrayList<RrfFusion.ChannelResult>();
        for (var channel : channels) {
            if ("vector".equals(channel.name()) && embedding == null) {
                omitted.add("vector");
                continue;
            }
            try {
                results.add(new RrfFusion.ChannelResult(channel.name(), channel.retrieve(authorized)));
            } catch (RetrievalChannel.SemanticUnavailableException error) {
                if (!"vector".equals(channel.name())) {
                    return MemoryResolution.degraded("CHANNEL_FAILURE", omitted);
                }
                omitted.add("vector");
            } catch (RuntimeException error) {
                return MemoryResolution.degraded("CHANNEL_FAILURE", omitted);
            }
        }
        try {
            var fused = fusion.fuse(results);
            var reranked = reranker.rerank(fused, requiredTypes, asOf);
            var items = budgeter.apply(reranked, effectiveBudget);
            var resolution = items.isEmpty()
                    ? MemoryResolution.empty(omitted)
                    : MemoryResolution.applied(items, omitted, graphRead.hints());
            if (graphRead.available()) {
                cacheResolution(identity, query, resolution);
            }
            return resolution;
        } catch (RuntimeException error) {
            return MemoryResolution.degraded("RESOLUTION_FAILURE", omitted);
        }
    }

    private Optional<MemoryResolution> cachedResolution(LearnerIdentity identity, MemoryQuery query) {
        try {
            return contextCache.find(identity, query).filter(ResolveMemoryContextUseCase::isCacheSafe);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void cacheResolution(LearnerIdentity identity, MemoryQuery query, MemoryResolution resolution) {
        if (!isCacheSafe(resolution)) {
            return;
        }
        try {
            contextCache.put(identity, query, resolution);
        } catch (RuntimeException ignored) {
            // Caches are optional and cannot change PostgreSQL authority resolution.
        }
    }

    private static boolean isCacheSafe(MemoryResolution resolution) {
        return resolution.items().stream().noneMatch(item -> item.privacyLevel() == com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel.SENSITIVE
                || item.privacyLevel() == com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel.HIGH);
    }

    private Set<MemoryType> consentAllowedTypes(
            LearnerIdentity identity,
            Set<MemoryType> requestedTypes,
            Instant asOf) {
        var allowed = EnumSet.noneOf(MemoryType.class);
        for (var type : requestedTypes) {
            var category = categoryFor(type);
            if (category.isPresent() && consentQuery.evaluate(identity, category.orElseThrow(), asOf).allowed()) {
                allowed.add(type);
            }
        }
        return Set.copyOf(allowed);
    }

    private MemoryProperties.Budgets effectiveBudget(MemoryQuery query) {
        var configured = properties.budgets();
        return new MemoryProperties.Budgets(
                Math.min(HARD_MAX_CONTEXT_ITEMS, Math.min(configured.maxItems(), query.maxItems())),
                Math.min(HARD_MAX_CONTEXT_ESTIMATED_TOKENS,
                        Math.min(configured.maxEstimatedTokens(), query.maxEstimatedTokens())));
    }

    private static Optional<MemoryCategory> categoryFor(MemoryType type) {
        return switch (type) {
            case PREFERENCE -> Optional.of(MemoryCategory.PREFERENCE);
            case MASTERY -> Optional.of(MemoryCategory.MASTERY);
            case MISCONCEPTION -> Optional.of(MemoryCategory.MISCONCEPTION);
            case REFLECTION -> Optional.of(MemoryCategory.REFLECTION);
            case AVIATION_FACT, OPTIMIZATION -> Optional.empty();
        };
    }

    private static boolean validEmbedding(QueryEmbedding embedding) {
        if (embedding == null || embedding.dimension() != 1024 || embedding.values().size() != 1024
                || !"L2".equals(embedding.normalization())
                || !identifier(embedding.modelId()) || !identifier(embedding.modelVersion())) {
            return false;
        }
        double squaredNorm = 0;
        for (var value : embedding.values()) {
            if (value == null || !Float.isFinite(value)) {
                return false;
            }
            squaredNorm += (double) value * value;
        }
        return squaredNorm > 0.99d && squaredNorm < 1.01d;
    }

    private GraphReadResult readGraphHints(
            RetrievalChannel.AuthorizedMemoryQuery authorized,
            Duration remainingJavaBudget) {
        try {
            return graphHintReader.read(authorized, remainingJavaBudget);
        } catch (RuntimeException ignored) {
            return GraphReadResult.unavailable();
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,96}");
    }

    public record MemoryQuery(
            String queryText,
            String taskType,
            Set<MemoryType> allowedTypes,
            Set<MemoryType> requiredTypes,
            int maxItems,
            int maxEstimatedTokens,
            QueryEmbedding queryEmbedding,
            Duration remainingJavaBudget,
            boolean graphRequired,
            CacheScope cacheScope) {

        public MemoryQuery(
                String queryText,
                String taskType,
                Set<MemoryType> allowedTypes,
                Set<MemoryType> requiredTypes,
                int maxItems,
                int maxEstimatedTokens,
                QueryEmbedding queryEmbedding,
                Duration remainingJavaBudget,
                boolean graphRequired) {
            this(queryText, taskType, allowedTypes, requiredTypes, maxItems, maxEstimatedTokens, queryEmbedding,
                    remainingJavaBudget, graphRequired, CacheScope.empty());
        }

        public MemoryQuery(
                String queryText,
                String taskType,
                Set<MemoryType> allowedTypes,
                Set<MemoryType> requiredTypes,
                int maxItems,
                int maxEstimatedTokens,
                QueryEmbedding queryEmbedding) {
            this(queryText, taskType, allowedTypes, requiredTypes, maxItems, maxEstimatedTokens, queryEmbedding,
                    Duration.ZERO, false, CacheScope.empty());
        }

        public MemoryQuery {
            queryText = Objects.requireNonNullElse(queryText, "");
            taskType = Objects.requireNonNullElse(taskType, "");
            allowedTypes = Set.copyOf(Objects.requireNonNull(allowedTypes, "allowedTypes"));
            requiredTypes = Set.copyOf(Objects.requireNonNull(requiredTypes, "requiredTypes"));
            if (!allowedTypes.containsAll(requiredTypes)) {
                throw new IllegalArgumentException("requiredTypes must be included in allowedTypes");
            }
            if (maxItems <= 0 || maxEstimatedTokens <= 0) {
                throw new IllegalArgumentException("query budget must be positive");
            }
            remainingJavaBudget = Objects.requireNonNull(remainingJavaBudget, "remainingJavaBudget");
            if (remainingJavaBudget.isNegative()) {
                throw new IllegalArgumentException("remainingJavaBudget");
            }
            cacheScope = Objects.requireNonNull(cacheScope, "cacheScope");
        }
    }

    /** Existing validated scene/schema binding used only to namespace internal cache entries. */
    public record CacheScope(
            boolean scenePresent,
            String sceneType,
            String sceneId,
            String locale,
            List<String> objectIds,
            String schemaVersion) {
        public CacheScope {
            sceneType = Objects.requireNonNullElse(sceneType, "");
            sceneId = Objects.requireNonNullElse(sceneId, "");
            locale = Objects.requireNonNullElse(locale, "");
            objectIds = List.copyOf(Objects.requireNonNull(objectIds, "objectIds"));
            schemaVersion = Objects.requireNonNullElse(schemaVersion, "");
        }

        public static CacheScope empty() {
            return new CacheScope(false, "", "", "", List.of(), "v1");
        }
    }

    /** Untrusted query embedding metadata; invalid values omit vector retrieval. */
    public record QueryEmbedding(
            List<Float> values,
            String modelId,
            String modelVersion,
            int dimension,
            String normalization) {

        public QueryEmbedding {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    public enum ResolutionStatus {
        APPLIED,
        EMPTY,
        DEGRADED
    }

    public record MemoryResolution(
            ResolutionStatus status,
            List<RetrievalChannel.RankedMemory> items,
            List<String> omittedChannels,
            String diagnosticCode,
            List<GraphHint> graphHints) {

        public MemoryResolution(
                ResolutionStatus status,
                List<RetrievalChannel.RankedMemory> items,
                List<String> omittedChannels,
                String diagnosticCode) {
            this(status, items, omittedChannels, diagnosticCode, List.of());
        }

        public MemoryResolution {
            Objects.requireNonNull(status, "status");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            omittedChannels = List.copyOf(Objects.requireNonNull(omittedChannels, "omittedChannels"));
            graphHints = List.copyOf(Objects.requireNonNull(graphHints, "graphHints"));
            if ((status == ResolutionStatus.EMPTY || status == ResolutionStatus.DEGRADED) && !items.isEmpty()) {
                throw new IllegalArgumentException("non-applied resolution cannot carry memory items");
            }
            if (status != ResolutionStatus.APPLIED && !graphHints.isEmpty()) {
                throw new IllegalArgumentException("non-applied resolution cannot carry graph hints");
            }
            if (status == ResolutionStatus.DEGRADED && (diagnosticCode == null || diagnosticCode.isBlank())) {
                throw new IllegalArgumentException("degraded resolution requires diagnostic code");
            }
        }

        private static MemoryResolution applied(
                List<RetrievalChannel.RankedMemory> items,
                List<String> omitted,
                List<GraphHint> graphHints) {
            return new MemoryResolution(ResolutionStatus.APPLIED, items, omitted, null, graphHints);
        }

        private static MemoryResolution empty() {
            return empty(List.of());
        }

        private static MemoryResolution empty(List<String> omitted) {
            return new MemoryResolution(ResolutionStatus.EMPTY, List.of(), omitted, null);
        }

        private static MemoryResolution degraded(String diagnosticCode, List<String> omitted) {
            return new MemoryResolution(ResolutionStatus.DEGRADED, List.of(), omitted, diagnosticCode);
        }
    }

    /** Optional, identifier-only graph read port. It never supplies memory values or facts. */
    @FunctionalInterface
    public interface GraphHintReader {
        GraphReadResult read(RetrievalChannel.AuthorizedMemoryQuery query, Duration remainingJavaBudget);

        static GraphHintReader disabled() {
            return (query, remainingJavaBudget) -> GraphReadResult.available(List.of());
        }
    }

    public record GraphReadResult(boolean available, List<GraphHint> hints) {
        public GraphReadResult {
            hints = List.copyOf(Objects.requireNonNull(hints, "hints"));
            if (!available && !hints.isEmpty()) {
                throw new IllegalArgumentException("unavailable graph cannot expose hints");
            }
        }

        public static GraphReadResult available(List<GraphHint> hints) {
            return new GraphReadResult(true, hints);
        }

        public static GraphReadResult unavailable() {
            return new GraphReadResult(false, List.of());
        }
    }

    /** A graph hint contains only stable authority identifiers and temporal relation metadata. */
    public record GraphHint(
            java.util.UUID relationEventId,
            java.util.UUID fromAssertionId,
            java.util.UUID fromVersionId,
            java.util.UUID toAssertionId,
            java.util.UUID toVersionId,
            Instant effectiveFrom,
            Instant effectiveUntil) {

        public GraphHint {
            Objects.requireNonNull(relationEventId, "relationEventId");
            Objects.requireNonNull(fromAssertionId, "fromAssertionId");
            Objects.requireNonNull(fromVersionId, "fromVersionId");
            Objects.requireNonNull(toAssertionId, "toAssertionId");
            Objects.requireNonNull(toVersionId, "toVersionId");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        }
    }
}
