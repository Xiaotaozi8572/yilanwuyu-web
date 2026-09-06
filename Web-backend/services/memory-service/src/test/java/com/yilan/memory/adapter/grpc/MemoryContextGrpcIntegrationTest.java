package com.yilan.memory.adapter.grpc;

import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.application.context.RetrievalChannel;
import com.yilan.memory.application.migration.AuthorityMode;
import com.yilan.memory.application.migration.AuthorityModeService;
import com.yilan.memory.application.event.SubmitMemoryEventsUseCase;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.contract.v1.CapabilityRequest;
import com.yilan.memory.contract.v1.ContextBudget;
import com.yilan.memory.contract.v1.EventReceipt;
import com.yilan.memory.contract.v1.MemoryApplicationStatus;
import com.yilan.memory.contract.v1.MemoryContextServiceGrpc;
import com.yilan.memory.contract.v1.MemoryEventEnvelope;
import com.yilan.memory.contract.v1.MemoryEventServiceGrpc;
import com.yilan.memory.contract.v1.QueryEmbedding;
import com.yilan.memory.contract.v1.ResolveMemoryContextRequest;
import com.yilan.memory.contract.v1.ResolveMemoryContextResponse;
import com.yilan.memory.contract.v1.SceneBinding;
import com.yilan.memory.contract.v1.SubmitMemoryEventsRequest;
import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.identity.LearnerIdentity;
import com.yilan.memory.security.SubjectAuthenticationConverter;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class MemoryContextGrpcIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SUBJECT_HMAC_KEY = "test-only-subject-hmac-key-that-is-long-enough";
    private static final String IMMUTABLE_SUBJECT = "grpc-learner-a";
    private static final SubjectAuthenticationConverter SUBJECT_CONVERTER =
            new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY);
    private static final LearnerIdentity IDENTITY = new LearnerIdentity(
            SUBJECT_CONVERTER.fromVerifiedClaims(IMMUTABLE_SUBJECT, List.of("LEARNER")).subjectHash(),
            "session-1", 4);

    @Test
    void mapperForwardsValidatedSceneAndSchemaOnlyIntoInternalCacheScope() {
        var mapper = new GrpcContractMapper(CLOCK);
        var request = request().toBuilder().setScene(SceneBinding.newBuilder()
                .setSceneType("lesson").setSceneId("lesson-1").setLocale("zh-CN")
                .addObjectIds("object-b").addObjectIds("object-a")).build();

        var query = mapper.toMemoryQuery(request);

        assertThat(query.cacheScope()).isEqualTo(new ResolveMemoryContextUseCase.CacheScope(
                true, "lesson", "lesson-1", "zh-CN", List.of("object-b", "object-a"), "v1"));
    }

    @Test
    void appliedAndEmptyResultsMapToBoundedV1Responses() {
        var service = service(useCase(List.of(new NamedChannel("structured", List.of(
                memory(MemoryType.PREFERENCE, RetrievalChannel.UseClass.OPTIONAL))))));
        var applied = invoke(service, request());

        assertThat(applied.error).isNull();
        assertThat(applied.response.getStatus()).isEqualTo(MemoryApplicationStatus.APPLIED);
        assertThat(applied.response.getContext().getItemsList()).singleElement().satisfies(item -> {
            assertThat(item.getMemoryType()).isEqualTo("PREFERENCE");
            assertThat(item.getUseClass()).isEqualTo("OPTIONAL");
            assertThat(item.getValidFrom()).isEqualTo("2026-07-20T07:00:00Z");
            assertThat(item.getValidTo()).isEqualTo("2026-07-21T08:00:00Z");
        });
        assertThat(applied.response.getContext().getMemoryBoundary()).isEqualTo("personalization-only");
        assertThat(applied.response.getContext().getGeneratedAt()).isEqualTo(NOW.toString());
        assertThat(applied.response.getContext().getPolicyEpoch()).isZero();
        assertThat(applied.response.getContext().getMemoryEpoch()).isZero();

        var emptyService = service(useCase(List.of(new NamedChannel("structured", List.of()))));
        var empty = invoke(emptyService, request());

        assertThat(empty.error).isNull();
        assertThat(empty.response.getStatus()).isEqualTo(MemoryApplicationStatus.EMPTY);
        assertThat(empty.response.hasContext()).isFalse();
    }

    @Test
    void incompatibleSchemaAndSessionMismatchDegradeWithoutCallingAuthority() {
        var useCase = mock(ResolveMemoryContextUseCase.class);
        var service = service(useCase);

        var schema = invoke(service, request().toBuilder().setSchemaVersion("v2").build());
        var session = invoke(service, request().toBuilder().setSessionId("session-body-other").build());

        assertDegraded(schema, "SCHEMA_INCOMPATIBLE");
        assertDegraded(session, "IDENTITY_BINDING_MISMATCH");
        verifyNoInteractions(useCase);
    }

    @Test
    void durable_non_remote_mode_gates_real_resolution_but_capabilities_still_carry_signed_state() {
        var useCase = mock(ResolveMemoryContextUseCase.class);
        var service = new MemoryContextGrpcService(useCase, new GrpcContractMapper(CLOCK), context -> IDENTITY,
                authorityService(AuthorityMode.LOCAL));

        var result = invoke(service, request());
        var capabilities = new CapabilityObserver();
        service.getCapabilities(CapabilityRequest.newBuilder().setRequestId("capability-cutover")
                .setSchemaVersion("v1").setTraceparent("trace-cutover").build(), capabilities);

        assertDegraded(result, "RESOLUTION_FAILURE");
        verifyNoInteractions(useCase);
        assertThat(capabilities.error).isNull();
        assertThat(capabilities.response.getRequiredCapabilitiesList())
                .anyMatch(value -> value.startsWith("authority-capability/v1;schema=v1;mode=LOCAL;"));
    }

    @Test
    void malformedBudgetsTypesUseClassesIdentifiersAndTraceDegradeWithFiniteDiagnostics() {
        var useCase = mock(ResolveMemoryContextUseCase.class);
        var service = service(useCase);
        var invalidRequests = List.of(
                request().toBuilder().setRequestId("bad request\nSELECT secret").build(),
                request().toBuilder().setTraceparent("trace\nunsafe").build(),
                request().toBuilder().setBudget(budget().toBuilder().setMaxItems(0)).build(),
                request().toBuilder().setBudget(budget().toBuilder().setMaxTokens(0)).build(),
                request().toBuilder().setBudget(budget().toBuilder().setMaxBytes(0)).build(),
                request().toBuilder().setBudget(budget().toBuilder()
                        .clearAllowedMemoryTypes().addAllowedMemoryTypes("UNKNOWN")).build(),
                request().toBuilder().setBudget(budget().toBuilder()
                        .clearAllowedMemoryTypes().addAllowedMemoryTypes("AVIATION_FACT")).build(),
                request().toBuilder().setBudget(budget().toBuilder()
                        .clearAllowedMemoryTypes().addAllowedMemoryTypes("OPTIMIZATION")).build(),
                request().toBuilder().setBudget(budget().toBuilder()
                        .clearAllowedUseClasses().addAllowedUseClasses("PROHIBITED")).build(),
                request().toBuilder().setBudget(budget().toBuilder()
                        .clearAllowedUseClasses().addAllowedUseClasses("UNKNOWN")).build());

        for (var invalid : invalidRequests) {
            assertDegraded(invoke(service, invalid), "INVALID_REQUEST");
        }
        verifyNoInteractions(useCase);
    }

    @Test
    void digestIsStableAndChangesWithSignedSubjectSessionOrRequest() {
        var useCase = useCase(List.of(new NamedChannel("structured", List.of())));
        var first = invoke(service(useCase, IDENTITY), request()).response.getIdentityBindingDigest();
        var repeat = invoke(service(useCase, IDENTITY), request()).response.getIdentityBindingDigest();
        var changedRequest = invoke(service(useCase, IDENTITY),
                request().toBuilder().setRequestId("request-2").build()).response.getIdentityBindingDigest();
        var changedSubject = invoke(service(useCase,
                new LearnerIdentity("subject-signed-b", "session-1", 4)), request()).response.getIdentityBindingDigest();
        var changedSessionIdentity = new LearnerIdentity("subject-signed-a", "session-2", 4);
        var changedSession = invoke(service(useCase, changedSessionIdentity),
                request().toBuilder().setSessionId("session-2").build()).response.getIdentityBindingDigest();

        assertThat(first).matches("[0-9a-f]{64}").isEqualTo(repeat);
        assertThat(Set.of(first, changedRequest, changedSubject, changedSession)).hasSize(4);
    }

    @Test
    void invalidEmbeddingOmitsOnlyVectorAndPreservesOtherContext() {
        var structured = new NamedChannel("structured", List.of(memory(MemoryType.PREFERENCE,
                RetrievalChannel.UseClass.OPTIONAL)));
        var vector = new NamedChannel("vector", List.of(memory(MemoryType.REFLECTION,
                RetrievalChannel.UseClass.OPTIONAL)));
        var invalidEmbedding = QueryEmbedding.newBuilder()
                .addValues(1.0f).addValues(0.0f)
                .setModelId("bge-m3").setModelVersion("v1").setDimension(2).setNormalization("L2")
                .build();

        var result = invoke(service(useCase(List.of(structured, vector))),
                request().toBuilder().setQueryEmbedding(invalidEmbedding).build());

        assertThat(result.response.getStatus()).isEqualTo(MemoryApplicationStatus.APPLIED);
        assertThat(result.response.getOmittedChannelsList()).containsExactly("vector");
        assertThat(result.response.getContext().getItemsList()).singleElement()
                .extracting(item -> item.getMemoryType()).isEqualTo("PREFERENCE");
        assertThat(vector.calls).isZero();
    }

    @Test
    void unadvertisedEmbeddingProfileOmitsOnlyVectorEvenWhenVectorShapeIsValid() {
        var structured = new NamedChannel("structured", List.of(memory(MemoryType.PREFERENCE,
                RetrievalChannel.UseClass.OPTIONAL)));
        var vector = new NamedChannel("vector", List.of(memory(MemoryType.REFLECTION,
                RetrievalChannel.UseClass.OPTIONAL)));
        var values = new ArrayList<Float>();
        values.add(1.0f);
        for (int index = 1; index < 1024; index++) values.add(0.0f);
        var unadvertised = QueryEmbedding.newBuilder().addAllValues(values)
                .setModelId("unadvertised-model").setModelVersion("unknown")
                .setDimension(1024).setNormalization("L2").build();

        var result = invoke(service(useCase(List.of(structured, vector))),
                request().toBuilder().setQueryEmbedding(unadvertised).build());

        assertThat(result.response.getStatus()).isEqualTo(MemoryApplicationStatus.APPLIED);
        assertThat(result.response.getOmittedChannelsList()).containsExactly("vector");
        assertThat(result.response.getContext().getItemsList()).singleElement()
                .extracting(item -> item.getMemoryType()).isEqualTo("PREFERENCE");
        assertThat(vector.calls).isZero();
    }

    @Test
    void validEmbeddingFieldsAndHardBudgetsReachTheUseCaseWithoutMaxBytesBypass() {
        var captured = new ArrayList<RetrievalChannel.AuthorizedMemoryQuery>();
        var vector = new RetrievalChannel() {
            @Override public String name() { return "vector"; }
            @Override public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
                captured.add(query);
                return List.of();
            }
        };
        var values = new ArrayList<Float>();
        values.add(1.0f);
        for (int index = 1; index < 1024; index++) values.add(0.0f);
        var embedding = QueryEmbedding.newBuilder().addAllValues(values)
                .setModelId("bge-m3").setModelVersion("v1").setDimension(1024).setNormalization("L2").build();
        var oversizedBytes = request().toBuilder().setQueryEmbedding(embedding)
                .setBudget(budget().toBuilder().setMaxItems(100).setMaxTokens(10_000).setMaxBytes(Integer.MAX_VALUE))
                .build();

        var result = invoke(service(useCase(List.of(vector))), oversizedBytes);

        assertThat(result.response.getStatus()).isEqualTo(MemoryApplicationStatus.EMPTY);
        assertThat(captured).singleElement().satisfies(query -> {
            assertThat(query.candidateLimit()).isEqualTo(8);
            assertThat(query.queryEmbedding().modelId()).isEqualTo("bge-m3");
            assertThat(query.queryEmbedding().modelVersion()).isEqualTo("v1");
            assertThat(query.queryEmbedding().dimension()).isEqualTo(1024);
            assertThat(query.queryEmbedding().normalization()).isEqualTo("L2");
            assertThat(query.queryEmbedding().values()).hasSize(1024);
        });
    }

    @Test
    void capabilitiesAdvertiseOnlyV1AndTheExactEmbeddingProfile() {
        var service = service(useCase(List.of()));
        var observer = new CapabilityObserver();

        service.getCapabilities(CapabilityRequest.newBuilder()
                .setRequestId("capability-1").setSchemaVersion("v1").setTraceparent("trace-capability-1")
                .build(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getSupportedSchemaVersionsList()).containsExactly("v1");
        assertThat(observer.response.getRequiredCapabilitiesList()).contains(
                "signed-session-identity", "identity-binding-digest-sha256",
                "governed-memory-context", "degraded-empty-context");
        assertThat(observer.response.getEmbeddingProfilesList()).singleElement().satisfies(profile -> {
            assertThat(profile.getModelId()).isEqualTo("bge-m3");
            assertThat(profile.getModelVersion()).isEqualTo("v1");
            assertThat(profile.getDimension()).isEqualTo(1024);
            assertThat(profile.getNormalization()).isEqualTo("L2");
        });
        assertThat(observer.response.getTraceId()).isEqualTo("trace-capability-1");
    }

    @Test
    void capabilitiesRejectIncompatibleSchemaAndUnsafeTraceAtTheTransportBoundary() {
        var service = service(useCase(List.of()));
        for (var invalid : List.of(
                CapabilityRequest.newBuilder().setRequestId("capability-1")
                        .setSchemaVersion("v2").setTraceparent("trace-capability-1").build(),
                CapabilityRequest.newBuilder().setRequestId("capability-1")
                        .setSchemaVersion("v1").setTraceparent("trace\nSELECT secret").build())) {
            var observer = new CapabilityObserver();

            service.getCapabilities(invalid, observer);

            assertThat(observer.response).isNull();
            assertThat(observer.error).isInstanceOf(io.grpc.StatusRuntimeException.class);
            assertThat(((io.grpc.StatusRuntimeException) observer.error).getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT);
            assertThat(observer.error.toString()).doesNotContain("SELECT", "secret");
        }
    }

    @Test
    void mapperNeverLetsAviationOptimizationOrProhibitedMemoryBecomeContext() {
        var mapper = new GrpcContractMapper(CLOCK);
        for (var unsafe : List.of(
                memory(MemoryType.AVIATION_FACT, RetrievalChannel.UseClass.OPTIONAL),
                memory(MemoryType.OPTIMIZATION, RetrievalChannel.UseClass.OPTIONAL),
                memory(MemoryType.PREFERENCE, RetrievalChannel.UseClass.PROHIBITED))) {
            var result = new ResolveMemoryContextUseCase.MemoryResolution(
                    ResolveMemoryContextUseCase.ResolutionStatus.APPLIED, List.of(unsafe), List.of(), null);

            var response = mapper.toProto(result, "request-1", "v1", "0".repeat(64), "trace-1");

            assertThat(response.getStatus()).isEqualTo(MemoryApplicationStatus.DEGRADED);
            assertThat(response.hasContext()).isFalse();
            assertThat(response.getDiagnosticCode()).isEqualTo("RESOLUTION_FAILURE");
        }
    }

    @Test
    void mapperRejectsTheEntireAppliedResultWhenOneMixedItemIsUnsafe() {
        var mapper = new GrpcContractMapper(CLOCK);
        for (var unsafe : List.of(
                memory(MemoryType.AVIATION_FACT, RetrievalChannel.UseClass.OPTIONAL),
                memory(MemoryType.PREFERENCE, RetrievalChannel.UseClass.PROHIBITED))) {
            var result = new ResolveMemoryContextUseCase.MemoryResolution(
                    ResolveMemoryContextUseCase.ResolutionStatus.APPLIED,
                    List.of(memory(MemoryType.PREFERENCE, RetrievalChannel.UseClass.OPTIONAL), unsafe),
                    List.of(), null);

            var response = mapper.toProto(result, "request-1", "v1", "0".repeat(64), "trace-1");

            assertThat(response.getStatus()).isEqualTo(MemoryApplicationStatus.DEGRADED);
            assertThat(response.hasContext()).isFalse();
            assertThat(response.getDiagnosticCode()).isEqualTo("RESOLUTION_FAILURE");
        }
    }

    @Test
    void mapperRejectsAppliedItemsWithMissingOrInvalidSourceClosure() {
        var mapper = new GrpcContractMapper(CLOCK);
        for (var sources : List.of(List.<String>of(), List.of("event-1", "SELECT secret"))) {
            var result = new ResolveMemoryContextUseCase.MemoryResolution(
                    ResolveMemoryContextUseCase.ResolutionStatus.APPLIED,
                    List.of(memoryWithSources(sources)), List.of(), null);

            var response = mapper.toProto(result, "request-1", "v1", "0".repeat(64), "trace-1");

            assertThat(response.getStatus()).isEqualTo(MemoryApplicationStatus.DEGRADED);
            assertThat(response.hasContext()).isFalse();
            assertThat(response.getDiagnosticCode()).isEqualTo("RESOLUTION_FAILURE");
            assertThat(response.toString()).doesNotContain("SELECT", "secret");
        }
    }

    @ParameterizedTest
    @MethodSource("closedSafeMemoryValues")
    void mapperRetainsEachValidClosedSafeMemoryValueSchema(MemoryType type, String valueJson) {
        var mapper = new GrpcContractMapper(CLOCK);
        var result = new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.APPLIED,
                List.of(memory(type, RetrievalChannel.UseClass.OPTIONAL, valueJson)), List.of(), null);

        var response = mapper.toProto(result, "request-1", "v1", "0".repeat(64), "trace-1");

        assertThat(response.getStatus()).isEqualTo(MemoryApplicationStatus.APPLIED);
        assertThat(response.getContext().getItemsList()).singleElement()
                .extracting(item -> item.getValueJson()).isEqualTo(valueJson);
    }

    @ParameterizedTest
    @MethodSource("unsafeOrInvalidSafeMemoryValues")
    void mapperDegradesTheEntireAppliedResultWhenARankedMemoryBypassesRepositoryValueValidation(
            MemoryType type,
            String valueJson) {
        var mapper = new GrpcContractMapper(CLOCK);
        var result = new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.APPLIED,
                List.of(memory(MemoryType.PREFERENCE, RetrievalChannel.UseClass.OPTIONAL),
                        memory(type, RetrievalChannel.UseClass.OPTIONAL, valueJson)), List.of(), null);

        var response = mapper.toProto(result, "request-1", "v1", "0".repeat(64), "trace-1");

        assertThat(response.getStatus()).isEqualTo(MemoryApplicationStatus.DEGRADED);
        assertThat(response.hasContext()).isFalse();
        assertThat(response.getDiagnosticCode()).isEqualTo("RESOLUTION_FAILURE");
    }

    @Test
    void maxBytesIsAnAllOrNothingSerializedContextLimit() {
        var service = service(useCase(List.of(new NamedChannel("structured", List.of(
                memory(MemoryType.PREFERENCE, RetrievalChannel.UseClass.OPTIONAL))))));

        var result = invoke(service, request().toBuilder()
                .setBudget(budget().toBuilder().setMaxBytes(1)).build());

        assertDegraded(result, "RESOLUTION_FAILURE");
    }

    @Test
    void realNettyRouteAuthenticatesAndServesResolveCapabilitiesAndSubmit() throws Exception {
        var routedChannel = new NamedChannel("structured", List.of(
                memory(MemoryType.PREFERENCE, RetrievalChannel.UseClass.OPTIONAL)));
        var contextService = new MemoryContextGrpcService(
                useCase(List.of(routedChannel)),
                new GrpcContractMapper(CLOCK), SignedSessionInterceptor::requireIdentity,
                authorityService(AuthorityMode.REMOTE));
        var eventUseCase = mock(SubmitMemoryEventsUseCase.class);
        when(eventUseCase.submit(any(), any())).thenReturn(List.of(
                SubmitMemoryEventsUseCase.EventReceipt.accepted(
                        UUID.fromString("33333333-3333-3333-3333-333333333333"))));
        var eventService = new MemoryEventGrpcService(
                eventUseCase, SignedSessionInterceptor::requireIdentity, authorityService(AuthorityMode.REMOTE));
        var signedInterceptor = new SignedSessionInterceptor(token -> switch (token) {
            case "Bearer local-test-signed" -> new SignedSessionInterceptor.VerifiedSession(
                    IMMUTABLE_SUBJECT, IDENTITY.sessionId(), IDENTITY.consentRevision(), List.of("LEARNER"));
            case "Bearer local-test-admin" -> new SignedSessionInterceptor.VerifiedSession(
                    "grpc-admin-a", "session-admin", 4, List.of("ADMIN"));
            case "Bearer local-test-auditor" -> new SignedSessionInterceptor.VerifiedSession(
                    "grpc-auditor-a", "session-auditor", 4, List.of("AUDITOR"));
            default -> throw new SignedSessionInterceptor.InvalidSessionException();
        }, SUBJECT_CONVERTER);
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(contextService, signedInterceptor))
                .addService(ServerInterceptors.intercept(eventService, signedInterceptor))
                .build().start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .usePlaintext().build();
        try {
            var unsignedContext = MemoryContextServiceGrpc.newBlockingStub(channel);
            assertThatThrownBy(() -> unsignedContext.resolveMemoryContext(request()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED);

            var headers = new Metadata();
            headers.put(SignedSessionInterceptor.AUTHORIZATION, "Bearer local-test-signed");
            var contextStub = MemoryContextServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            var eventStub = MemoryEventServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

            var resolved = contextStub.resolveMemoryContext(request());
            var capabilities = contextStub.getCapabilities(CapabilityRequest.newBuilder()
                    .setRequestId("capability-netty-1").setSchemaVersion("v1")
                    .setTraceparent("trace-capability-netty-1").build());
            var submitted = eventStub.submitMemoryEvents(eventRequest());

            assertThat(resolved.getStatus()).isEqualTo(MemoryApplicationStatus.APPLIED);
            assertThat(routedChannel.lastQuery.subjectHash()).isEqualTo(IDENTITY.subjectHash());
            assertThat(routedChannel.lastQuery.sessionId()).isEqualTo(IDENTITY.sessionId());
            assertThat(capabilities.getSupportedSchemaVersionsList()).containsExactly("v1");
            assertThat(submitted.getReceiptsList()).singleElement()
                    .extracting(EventReceipt::getResult).isEqualTo(EventReceipt.Result.ACCEPTED);
            verify(eventUseCase).submit(eq(IDENTITY), any());

            for (var role : List.of("admin", "auditor")) {
                var nonLearnerHeaders = new Metadata();
                nonLearnerHeaders.put(SignedSessionInterceptor.AUTHORIZATION, "Bearer local-test-" + role);
                var nonLearnerContext = MemoryContextServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(nonLearnerHeaders));
                var nonLearnerEvents = MemoryEventServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(nonLearnerHeaders));

                assertThatThrownBy(() -> nonLearnerContext.resolveMemoryContext(request()))
                        .isInstanceOf(StatusRuntimeException.class)
                        .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED);
                assertThatThrownBy(() -> nonLearnerEvents.submitMemoryEvents(eventRequest()))
                        .isInstanceOf(StatusRuntimeException.class)
                        .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED);
            }
            assertThat(routedChannel.calls).isEqualTo(1);
            verify(eventUseCase).submit(eq(IDENTITY), any());
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
            assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertDegraded(Result result, String code) {
        assertThat(result.error).isNull();
        assertThat(result.response.getStatus()).isEqualTo(MemoryApplicationStatus.DEGRADED);
        assertThat(result.response.hasContext()).isFalse();
        assertThat(result.response.getDiagnosticCode()).isEqualTo(code);
        assertThat(result.response.getIdentityBindingDigest()).matches("[0-9a-f]{64}");
    }

    private static MemoryContextGrpcService service(ResolveMemoryContextUseCase useCase) {
        return service(useCase, IDENTITY);
    }

    private static MemoryContextGrpcService service(ResolveMemoryContextUseCase useCase, LearnerIdentity identity) {
        return new MemoryContextGrpcService(
                useCase, new GrpcContractMapper(CLOCK), context -> identity, authorityService(AuthorityMode.REMOTE));
    }

    private static AuthorityModeService authorityService(AuthorityMode mode) {
        return new AuthorityModeService(new AuthorityModeService.AuthorityModeRepository() {
            private AuthorityModeService.AuthorityState state = new AuthorityModeService.AuthorityState(mode, 0, 0);
            @Override public AuthorityModeService.AuthorityState read() { return state; }
            @Override public AuthorityModeService.AuthorityState compareAndSet(
                    AuthorityModeService.AuthorityState expected, AuthorityModeService.AuthorityState next) {
                if (!state.equals(expected)) throw new IllegalStateException("changed");
                state = next;
                return state;
            }
        }, "test-only-authority-hmac-key");
    }

    private static Result invoke(MemoryContextGrpcService service, ResolveMemoryContextRequest request) {
        var observer = new ResponseObserver();
        service.resolveMemoryContext(request, observer);
        return new Result(observer.response, observer.error);
    }

    private static ResolveMemoryContextRequest request() {
        return ResolveMemoryContextRequest.newBuilder()
                .setRequestId("request-1").setSchemaVersion("v1").setSessionId("session-1")
                .setQueryText("explain clearly").setTaskType("TEACHING")
                .setBudget(budget()).setTraceparent("trace-1").build();
    }

    private static ContextBudget budget() {
        return ContextBudget.newBuilder().setMaxItems(8).setMaxBytes(4096).setMaxTokens(900)
                .addAllowedMemoryTypes("PREFERENCE").addAllowedUseClasses("OPTIONAL").build();
    }

    private static ResolveMemoryContextUseCase useCase(List<? extends RetrievalChannel> channels) {
        ConsentQuery.PolicyReader reader = subject -> OptionalPolicies.active(subject);
        return new ResolveMemoryContextUseCase(properties(), reader, CLOCK, channels);
    }

    private static MemoryProperties properties() {
        var rules = new EnumMap<MemoryType, MemoryProperties.TypeRule>(MemoryType.class);
        for (var type : MemoryType.values()) {
            rules.put(type, new MemoryProperties.TypeRule(1, new BigDecimal("0.70"), Duration.ofDays(30)));
        }
        return new MemoryProperties("rules-grpc-v1", rules,
                new MemoryProperties.RetrievalWeights(new BigDecimal("0.30"), new BigDecimal("0.20"),
                        new BigDecimal("0.50"), new BigDecimal("0.10")),
                60, new MemoryProperties.Budgets(8, 900));
    }

    private static RetrievalChannel.RankedMemory memory(MemoryType type, RetrievalChannel.UseClass useClass) {
        return memory(type, useClass, valueJson(type));
    }

    private static RetrievalChannel.RankedMemory memory(
            MemoryType type,
            RetrievalChannel.UseClass useClass,
            String valueJson) {
        return new RetrievalChannel.RankedMemory(
                UUID.randomUUID(), UUID.randomUUID(), type, valueJson,
                new BigDecimal("0.90"), new BigDecimal("0.80"), NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(1)), NOW.minus(Duration.ofHours(1)), null, 80, true,
                PrivacyLevel.STANDARD, "ACTIVE", "CONFIRMED", List.of("event-1"), null,
                new BigDecimal("0.50"), useClass);
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> closedSafeMemoryValues() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"answer_style\":\"step_by_step\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.MASTERY, "{\"mastery_level\":\"proficient\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.MISCONCEPTION, "{\"misconception_code\":\"angle_of_attack\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.REFLECTION, "{\"reflection_code\":\"practice_complete\"}"));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> unsafeOrInvalidSafeMemoryValues() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"unknown\":\"concise\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"answer_style\":\"concise\",\"extra\":\"x\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "[]"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"answer_style\":\"verbose\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.PREFERENCE, "{\"answer_style\":\"C919 thrust 120kN\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.MASTERY, "{\"mastery_level\":\"expert\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.MISCONCEPTION, "{\"misconception_code\":\"Bad-Code\"}"),
                org.junit.jupiter.params.provider.Arguments.of(MemoryType.REFLECTION, "{\"reflection_code\":\"bad code\"}"));
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

    private static RetrievalChannel.RankedMemory memoryWithSources(List<String> sourceIds) {
        var item = memory(MemoryType.PREFERENCE, RetrievalChannel.UseClass.OPTIONAL);
        return new RetrievalChannel.RankedMemory(
                item.memoryId(), item.versionId(), item.memoryType(), item.valueJson(), item.confidence(),
                item.stabilityScore(), item.validFrom(), item.validUntil(), item.recordedAt(), item.recordedUntil(),
                item.estimatedTokens(), item.sourceClosed(), item.privacyLevel(), item.status(),
                item.confirmationStatus(), sourceIds, item.conflictGroup(), item.score(), item.useClass());
    }

    private static SubmitMemoryEventsRequest eventRequest() {
        return SubmitMemoryEventsRequest.newBuilder()
                .setRequestId("request-event-netty-1").setSchemaVersion("v1")
                .setTraceparent("trace-event-netty-1")
                .addEvents(MemoryEventEnvelope.newBuilder()
                        .setSpecversion("1.0")
                        .setId("33333333-3333-3333-3333-333333333333")
                        .setSource("urn:yilan:memory:test")
                        .setType("PREFERENCE")
                        .setSubject(IDENTITY.subjectHash())
                        .setTime(NOW.toString())
                        .setDataschema("urn:yilan:memory:v1")
                        .setDatacontenttype("application/octet-stream")
                        .setData(com.google.protobuf.ByteString.copyFromUtf8("opaque-event-body"))
                        .setEventSchemaVersion("v1").setConsentRevision(IDENTITY.consentRevision())
                        .setPrivacyLevel(com.yilan.memory.contract.v1.PrivacyLevel.STANDARD))
                .build();
    }

    private record Result(ResolveMemoryContextResponse response, Throwable error) { }

    private static final class NamedChannel implements RetrievalChannel {
        private final String name;
        private final List<RankedMemory> memories;
        private int calls;
        private AuthorizedMemoryQuery lastQuery;
        private NamedChannel(String name, List<RankedMemory> memories) { this.name = name; this.memories = memories; }
        @Override public String name() { return name; }
        @Override public List<RankedMemory> retrieve(AuthorizedMemoryQuery query) {
            calls++;
            lastQuery = query;
            return memories;
        }
    }

    private static final class OptionalPolicies {
        private static java.util.Optional<ConsentPolicy> active(String subject) {
            return java.util.Optional.of(new ConsentPolicy(
                    ConsentPolicy.SubjectStatus.ACTIVE, ConsentPolicy.PolicyStatus.ACTIVE, 4,
                    Set.of(MemoryCategory.PREFERENCE, MemoryCategory.MASTERY,
                            MemoryCategory.MISCONCEPTION, MemoryCategory.REFLECTION),
                    Instant.EPOCH, NOW.plus(Duration.ofDays(1))));
        }
    }

    private static final class ResponseObserver implements StreamObserver<ResolveMemoryContextResponse> {
        private ResolveMemoryContextResponse response;
        private Throwable error;
        @Override public void onNext(ResolveMemoryContextResponse value) { response = value; }
        @Override public void onError(Throwable throwable) { error = throwable; }
        @Override public void onCompleted() { }
    }

    private static final class CapabilityObserver implements StreamObserver<com.yilan.memory.contract.v1.CapabilityResponse> {
        private com.yilan.memory.contract.v1.CapabilityResponse response;
        private Throwable error;
        @Override public void onNext(com.yilan.memory.contract.v1.CapabilityResponse value) { response = value; }
        @Override public void onError(Throwable throwable) { error = throwable; }
        @Override public void onCompleted() { }
    }
}
