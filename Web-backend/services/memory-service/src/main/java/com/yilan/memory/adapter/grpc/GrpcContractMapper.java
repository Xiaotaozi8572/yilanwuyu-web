package com.yilan.memory.adapter.grpc;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.contract.v1.CapabilityRequest;
import com.yilan.memory.contract.v1.CapabilityResponse;
import com.yilan.memory.contract.v1.EmbeddingProfile;
import com.yilan.memory.contract.v1.GovernedMemoryContext;
import com.yilan.memory.contract.v1.MemoryApplicationStatus;
import com.yilan.memory.contract.v1.MemoryItem;
import com.yilan.memory.contract.v1.ResolveMemoryContextRequest;
import com.yilan.memory.contract.v1.ResolveMemoryContextResponse;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict v1 contract validation and domain/protobuf mapping. */
public class GrpcContractMapper {

    static final String SCHEMA_VERSION = "v1";
    static final String MEMORY_BOUNDARY = "personalization-only";
    private static final Pattern BOUNDED_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern TASK_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> FINITE_DIAGNOSTICS = Set.of(
            "CHANNEL_FAILURE", "RESOLUTION_FAILURE", "INVALID_REQUEST",
            "SCHEMA_INCOMPATIBLE", "IDENTITY_BINDING_MISMATCH");
    private static final Set<String> FINITE_CHANNELS = Set.of(
            "structured", "keyword", "vector", "recentEpisode");
    private static final Set<MemoryType> SAFE_MEMORY_TYPES = Set.of(
            MemoryType.PREFERENCE, MemoryType.MASTERY, MemoryType.MISCONCEPTION, MemoryType.REFLECTION);
    private static final List<String> REQUIRED_CAPABILITIES = List.of(
            "signed-session-identity",
            "identity-binding-digest-sha256",
            "governed-memory-context",
            "degraded-empty-context",
            "embedding-profile-negotiation");
    private static final EmbeddingProfile ADVERTISED_EMBEDDING_PROFILE = EmbeddingProfile.newBuilder()
            .setModelId("bge-m3")
            .setModelVersion("v1")
            .setDimension(1024)
            .setNormalization("L2")
            .build();

    private final Clock clock;

    public GrpcContractMapper() {
        this(Clock.systemUTC());
    }

    GrpcContractMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void validateResolveHeader(ResolveMemoryContextRequest request) {
        Objects.requireNonNull(request, "request");
        requireIdentifier(request.getRequestId());
        if (!SCHEMA_VERSION.equals(request.getSchemaVersion())) {
            throw new ContractViolation("SCHEMA_INCOMPATIBLE");
        }
        requireIdentifier(request.getSessionId());
        requireIdentifier(request.getTraceparent());
    }

    ResolveMemoryContextUseCase.MemoryQuery toMemoryQuery(ResolveMemoryContextRequest request) {
        validateResolveHeader(request);
        requireBoundedText(request.getQueryText(), 4096);
        if (!TASK_IDENTIFIER.matcher(request.getTaskType()).matches()) {
            throw new ContractViolation("INVALID_REQUEST");
        }
        validateScene(request);
        if (!request.hasBudget()) {
            throw new ContractViolation("INVALID_REQUEST");
        }
        var budget = request.getBudget();
        var requestedItems = positiveUnsignedInt(budget.getMaxItems());
        positiveUnsignedInt(budget.getMaxBytes());
        var requestedTokens = positiveUnsignedInt(budget.getMaxTokens());
        var allowedTypes = allowedTypes(budget.getAllowedMemoryTypesList());
        var allowedUseClasses = allowedUseClasses(budget.getAllowedUseClassesList());
        var requiredTypes = allowedUseClasses.equals(Set.of(RetrievalChannel.UseClass.REQUIRED))
                ? allowedTypes
                : Set.<MemoryType>of();
        ResolveMemoryContextUseCase.QueryEmbedding embedding = null;
        if (request.hasQueryEmbedding() && hasAdvertisedEmbeddingProfile(request.getQueryEmbedding())) {
            var proto = request.getQueryEmbedding();
            embedding = new ResolveMemoryContextUseCase.QueryEmbedding(
                    proto.getValuesList(), proto.getModelId(), proto.getModelVersion(),
                    unsignedIntOrInvalid(proto.getDimension()), proto.getNormalization());
        }
        var scene = request.getScene();
        var cacheScope = new ResolveMemoryContextUseCase.CacheScope(
                request.hasScene(), scene.getSceneType(), scene.getSceneId(), scene.getLocale(),
                scene.getObjectIdsList(), request.getSchemaVersion());
        return new ResolveMemoryContextUseCase.MemoryQuery(
                request.getQueryText(), request.getTaskType(), allowedTypes, requiredTypes,
                requestedItems, requestedTokens, embedding, Duration.ZERO, false, cacheScope);
    }

    ResolveMemoryContextResponse toProto(
            ResolveMemoryContextUseCase.MemoryResolution result,
            String requestId,
            String schemaVersion,
            String identityBindingDigest,
            String traceId) {
        Objects.requireNonNull(result, "result");
        var status = switch (result.status()) {
            case APPLIED -> result.items().isEmpty() || !result.items().stream().allMatch(GrpcContractMapper::isSafeMemory)
                    ? MemoryApplicationStatus.DEGRADED
                    : MemoryApplicationStatus.APPLIED;
            case EMPTY -> MemoryApplicationStatus.EMPTY;
            case DEGRADED -> MemoryApplicationStatus.DEGRADED;
        };
        var builder = ResolveMemoryContextResponse.newBuilder()
                .setRequestId(safeIdentifierOrFallback(requestId, "request-invalid"))
                .setSchemaVersion(SCHEMA_VERSION)
                .setStatus(status)
                .setIdentityBindingDigest(requireDigest(identityBindingDigest))
                .addAllOmittedChannels(finiteChannels(result.omittedChannels()))
                .setTraceId(safeIdentifierOrFallback(traceId, "trace-unavailable"));
        if (status == MemoryApplicationStatus.APPLIED) {
            var context = GovernedMemoryContext.newBuilder()
                    .setMemoryBoundary(MEMORY_BOUNDARY)
                    // M1 has no epoch query adapter. Zero is the explicit protobuf-safe unknown value.
                    .setPolicyEpoch(0)
                    .setMemoryEpoch(0)
                    .setGeneratedAt(Instant.now(clock).toString());
            result.items().forEach(item -> context.addItems(toProtoItem(item)));
            builder.setContext(context);
        } else if (status == MemoryApplicationStatus.DEGRADED) {
            builder.setDiagnosticCode(result.status() == ResolveMemoryContextUseCase.ResolutionStatus.APPLIED
                    ? "RESOLUTION_FAILURE"
                    : finiteDiagnostic(result.diagnosticCode()));
        }
        return builder.build();
    }

    ResolveMemoryContextResponse degradedResponse(
            String diagnosticCode,
            String requestId,
            String identityBindingDigest,
            String traceId) {
        return ResolveMemoryContextResponse.newBuilder()
                .setRequestId(safeIdentifierOrFallback(requestId, "request-invalid"))
                .setSchemaVersion(SCHEMA_VERSION)
                .setStatus(MemoryApplicationStatus.DEGRADED)
                .setIdentityBindingDigest(requireDigest(identityBindingDigest))
                .setTraceId(safeIdentifierOrFallback(traceId, "trace-unavailable"))
                .setDiagnosticCode(finiteDiagnostic(diagnosticCode))
                .build();
    }

    CapabilityResponse capabilities(CapabilityRequest request) {
        return capabilities(request, Optional.empty());
    }

    CapabilityResponse capabilities(CapabilityRequest request, Optional<String> authorityCapability) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authorityCapability, "authorityCapability");
        requireIdentifier(request.getRequestId());
        if (!SCHEMA_VERSION.equals(request.getSchemaVersion())) {
            throw new ContractViolation("SCHEMA_INCOMPATIBLE");
        }
        requireIdentifier(request.getTraceparent());
        var builder = CapabilityResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setSchemaVersion(SCHEMA_VERSION)
                .addSupportedSchemaVersions(SCHEMA_VERSION)
                .addAllRequiredCapabilities(REQUIRED_CAPABILITIES)
                .addEmbeddingProfiles(ADVERTISED_EMBEDDING_PROFILE)
                .setTraceId(request.getTraceparent());
        authorityCapability.ifPresent(builder::addRequiredCapabilities);
        return builder.build();
    }

    static String safeIdentifierOrFallback(String value, String fallback) {
        return value != null && BOUNDED_IDENTIFIER.matcher(value).matches() ? value : fallback;
    }

    private static MemoryItem toProtoItem(RetrievalChannel.RankedMemory item) {
        var builder = MemoryItem.newBuilder()
                .setMemoryId(item.memoryId().toString())
                .setVersionId(item.versionId().toString())
                .setMemoryType(item.memoryType().name())
                .setUseClass(item.useClass().name())
                .setValueJson(item.valueJson())
                .setConfidence(item.confidence().doubleValue())
                .setValidFrom(item.validFrom().toString())
                .setConfirmationStatus(item.confirmationStatus())
                .addAllSourceIds(item.sourceIds());
        if (item.validUntil() != null) {
            builder.setValidTo(item.validUntil().toString());
        }
        return builder.build();
    }

    private static boolean isSafeMemory(RetrievalChannel.RankedMemory item) {
        return item != null
                && SAFE_MEMORY_TYPES.contains(item.memoryType())
                && (item.useClass() == RetrievalChannel.UseClass.REQUIRED
                        || item.useClass() == RetrievalChannel.UseClass.OPTIONAL)
                && MemoryCandidate.hasValidValueSchema(item.memoryType(), item.valueJson())
                && item.validFrom() != null
                && item.confirmationStatus() != null
                && "CONFIRMED".equals(item.confirmationStatus())
                && item.sourceClosed()
                && !item.sourceIds().isEmpty()
                && item.sourceIds().stream().allMatch(source ->
                        source != null && BOUNDED_IDENTIFIER.matcher(source).matches());
    }

    private static boolean hasAdvertisedEmbeddingProfile(com.yilan.memory.contract.v1.QueryEmbedding embedding) {
        return ADVERTISED_EMBEDDING_PROFILE.getModelId().equals(embedding.getModelId())
                && ADVERTISED_EMBEDDING_PROFILE.getModelVersion().equals(embedding.getModelVersion())
                && ADVERTISED_EMBEDDING_PROFILE.getDimension() == embedding.getDimension()
                && ADVERTISED_EMBEDDING_PROFILE.getNormalization().equals(embedding.getNormalization());
    }

    private static Set<MemoryType> allowedTypes(List<String> values) {
        if (values.isEmpty()) {
            throw new ContractViolation("INVALID_REQUEST");
        }
        var result = EnumSet.noneOf(MemoryType.class);
        for (var value : values) {
            final MemoryType type;
            try {
                type = MemoryType.valueOf(value);
            } catch (RuntimeException error) {
                throw new ContractViolation("INVALID_REQUEST");
            }
            if (!SAFE_MEMORY_TYPES.contains(type)) {
                throw new ContractViolation("INVALID_REQUEST");
            }
            result.add(type);
        }
        return Set.copyOf(result);
    }

    private static Set<RetrievalChannel.UseClass> allowedUseClasses(List<String> values) {
        if (values.isEmpty()) {
            throw new ContractViolation("INVALID_REQUEST");
        }
        var result = EnumSet.noneOf(RetrievalChannel.UseClass.class);
        for (var value : values) {
            final RetrievalChannel.UseClass useClass;
            try {
                useClass = RetrievalChannel.UseClass.valueOf(value);
            } catch (RuntimeException error) {
                throw new ContractViolation("INVALID_REQUEST");
            }
            if (useClass == RetrievalChannel.UseClass.PROHIBITED) {
                throw new ContractViolation("INVALID_REQUEST");
            }
            result.add(useClass);
        }
        return Set.copyOf(result);
    }

    private static void validateScene(ResolveMemoryContextRequest request) {
        if (!request.hasScene()) {
            return;
        }
        var scene = request.getScene();
        if (!scene.getSceneType().isEmpty()) requireIdentifier(scene.getSceneType());
        if (!scene.getSceneId().isEmpty()) requireIdentifier(scene.getSceneId());
        if (!scene.getLocale().isEmpty() && !TASK_IDENTIFIER.matcher(scene.getLocale()).matches()) {
            throw new ContractViolation("INVALID_REQUEST");
        }
        for (var objectId : scene.getObjectIdsList()) requireIdentifier(objectId);
    }

    private static void requireIdentifier(String value) {
        if (value == null || !BOUNDED_IDENTIFIER.matcher(value).matches()) {
            throw new ContractViolation("INVALID_REQUEST");
        }
    }

    private static void requireBoundedText(String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new ContractViolation("INVALID_REQUEST");
        }
    }

    private static int positiveUnsignedInt(int value) {
        var unsigned = Integer.toUnsignedLong(value);
        if (unsigned == 0 || unsigned > Integer.MAX_VALUE) {
            throw new ContractViolation("INVALID_REQUEST");
        }
        return (int) unsigned;
    }

    private static int unsignedIntOrInvalid(int value) {
        var unsigned = Integer.toUnsignedLong(value);
        return unsigned <= Integer.MAX_VALUE ? (int) unsigned : -1;
    }

    private static String finiteDiagnostic(String value) {
        return FINITE_DIAGNOSTICS.contains(value) ? value : "RESOLUTION_FAILURE";
    }

    private static List<String> finiteChannels(List<String> channels) {
        var bounded = new ArrayList<String>();
        for (var channel : channels) {
            if (FINITE_CHANNELS.contains(channel) && !bounded.contains(channel)) bounded.add(channel);
        }
        return List.copyOf(bounded);
    }

    private static String requireDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("identity binding digest");
        }
        return digest;
    }

    static final class ContractViolation extends IllegalArgumentException {
        private final String diagnosticCode;
        ContractViolation(String diagnosticCode) {
            super("contract violation");
            this.diagnosticCode = finiteDiagnostic(diagnosticCode);
        }
        String diagnosticCode() { return diagnosticCode; }
    }
}
