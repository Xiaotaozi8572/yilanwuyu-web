package com.yilan.memory.adapter.grpc;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.application.migration.AuthorityMode;
import com.yilan.memory.application.migration.AuthorityModeService;
import com.yilan.memory.contract.v1.GovernedMemoryContext;
import com.yilan.memory.contract.v1.MemoryApplicationStatus;
import com.yilan.memory.contract.v1.ResolveMemoryContextRequest;
import com.yilan.memory.contract.v1.ResolveMemoryContextResponse;
import com.yilan.memory.domain.identity.LearnerIdentity;
import com.yilan.memory.security.SubjectAuthenticationConverter;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FailureIsolationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T08:00:00Z"), ZoneOffset.UTC);
    private static final LearnerIdentity IDENTITY = new LearnerIdentity("subject-a", "session-1", 1);
    private static final SubjectAuthenticationConverter SUBJECT_CONVERTER =
            new SubjectAuthenticationConverter("test-only-subject-hmac-key-that-is-long-enough");

    @Test
    void unconfigured_public_constructor_gates_signed_resolution_before_the_use_case() {
        var useCase = mock(ResolveMemoryContextUseCase.class);
        var service = new MemoryContextGrpcService(useCase);
        var observer = new RecordingObserver();

        invokePublicThroughSignedInterceptor(service, validRequest(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getStatus()).isEqualTo(MemoryApplicationStatus.DEGRADED);
        verifyNoInteractions(useCase);
    }

    @Test
    void missingSignedIdentityRemainsUnauthenticated() {
        var service = new MemoryContextGrpcService(mock(ResolveMemoryContextUseCase.class),
                new GrpcContractMapper(CLOCK), SignedSessionInterceptor::requireIdentity);
        var observer = new RecordingObserver();

        service.resolveMemoryContext(validRequest(), observer);

        assertThat(observer.response).isNull();
        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) observer.error).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void authorityRuntimeFailureIsUnavailableWithoutLeakingItsText() {
        var useCase = mock(ResolveMemoryContextUseCase.class);
        when(useCase.resolve(any(), any())).thenThrow(new IllegalStateException(
                "SELECT payload, subject_hash FROM secret_table WHERE query='private'"));
        var service = new MemoryContextGrpcService(
                useCase, new GrpcContractMapper(CLOCK), context -> IDENTITY, authorityService(AuthorityMode.REMOTE));
        var observer = new RecordingObserver();

        service.resolveMemoryContext(validRequest(), observer);

        assertThat(observer.response).isNull();
        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
        var status = ((StatusRuntimeException) observer.error).getStatus();
        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription()).isEqualTo("memory authority unavailable");
        assertThat(status.toString()).doesNotContain("SELECT", "payload", "subject_hash", "private");
    }

    @Test
    void statusRuntimeExceptionIsPreservedVerbatim() {
        var useCase = mock(ResolveMemoryContextUseCase.class);
        var expected = Status.UNAUTHENTICATED.withDescription("bounded auth failure").asRuntimeException();
        when(useCase.resolve(any(), any())).thenThrow(expected);
        var service = new MemoryContextGrpcService(
                useCase, new GrpcContractMapper(CLOCK), context -> IDENTITY, authorityService(AuthorityMode.REMOTE));
        var observer = new RecordingObserver();

        service.resolveMemoryContext(validRequest(), observer);

        assertThat(observer.error).isSameAs(expected);
    }

    @Test
    void transportDefensivelyClearsContextFromEveryDegradedMapperResponse() {
        var useCase = mock(ResolveMemoryContextUseCase.class);
        var degraded = new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.DEGRADED, List.of(), List.of("vector"),
                "CHANNEL_FAILURE");
        when(useCase.resolve(any(), any())).thenReturn(degraded);
        var buggyMapper = new GrpcContractMapper(CLOCK) {
            @Override
            ResolveMemoryContextResponse toProto(
                    ResolveMemoryContextUseCase.MemoryResolution ignored,
                    String requestId,
                    String schemaVersion,
                    String identityBindingDigest,
                    String traceId) {
                return ResolveMemoryContextResponse.newBuilder()
                        .setRequestId(requestId).setSchemaVersion(schemaVersion)
                        .setIdentityBindingDigest(identityBindingDigest).setTraceId(traceId)
                        .setStatus(MemoryApplicationStatus.DEGRADED).setDiagnosticCode("CHANNEL_FAILURE")
                        .setContext(GovernedMemoryContext.newBuilder().setMemoryBoundary("unsafe"))
                        .build();
            }
        };
        var service = new MemoryContextGrpcService(
                useCase, buggyMapper, context -> IDENTITY, authorityService(AuthorityMode.REMOTE));
        var observer = new RecordingObserver();

        service.resolveMemoryContext(validRequest(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getStatus()).isEqualTo(MemoryApplicationStatus.DEGRADED);
        assertThat(observer.response.hasContext()).isFalse();
    }

    @Test
    void degradedDiagnosticAndOmittedMetadataAreFiniteAndNeverEchoAuthorityText() {
        var mapper = new GrpcContractMapper(CLOCK);
        var result = new ResolveMemoryContextUseCase.MemoryResolution(
                ResolveMemoryContextUseCase.ResolutionStatus.DEGRADED, List.of(),
                List.of("vector", "SELECT secret"), "SQL SELECT payload FROM learner");

        var response = mapper.toProto(result, "request-1", "v1", "0".repeat(64), "trace-1");

        assertThat(response.getDiagnosticCode()).isEqualTo("RESOLUTION_FAILURE");
        assertThat(response.getOmittedChannelsList()).containsExactly("vector");
        assertThat(response.toString()).doesNotContain("SELECT", "payload", "learner", "secret");
    }

    private static ResolveMemoryContextRequest validRequest() {
        return ResolveMemoryContextRequest.newBuilder()
                .setRequestId("request-1").setSchemaVersion("v1").setSessionId("session-1")
                .setQueryText("safe query").setTaskType("TEACHING").setTraceparent("trace-1")
                .setBudget(com.yilan.memory.contract.v1.ContextBudget.newBuilder()
                        .setMaxItems(8).setMaxBytes(4096).setMaxTokens(900)
                        .addAllowedMemoryTypes("PREFERENCE").addAllowedUseClasses("OPTIONAL"))
                .build();
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

    @SuppressWarnings("unchecked")
    private static void invokePublicThroughSignedInterceptor(
            MemoryContextGrpcService service,
            ResolveMemoryContextRequest request,
            StreamObserver<ResolveMemoryContextResponse> observer) {
        var interceptor = new SignedSessionInterceptor(token -> new SignedSessionInterceptor.VerifiedSession(
                "failure-isolation-learner", "session-1", 1, List.of("LEARNER")), SUBJECT_CONVERTER);
        ServerCall<ResolveMemoryContextRequest, ResolveMemoryContextResponse> call = mock(ServerCall.class);
        var headers = new Metadata();
        headers.put(SignedSessionInterceptor.AUTHORIZATION, "Bearer local-test-signed");
        ServerCallHandler<ResolveMemoryContextRequest, ResolveMemoryContextResponse> handler =
                new ServerCallHandler<>() {
                    @Override
                    public ServerCall.Listener<ResolveMemoryContextRequest> startCall(
                            ServerCall<ResolveMemoryContextRequest, ResolveMemoryContextResponse> ignored,
                            Metadata ignoredHeaders) {
                        service.resolveMemoryContext(request, observer);
                        return new ServerCall.Listener<>() { };
                    }
                };
        interceptor.interceptCall(call, headers, handler);
    }

    private static final class RecordingObserver implements StreamObserver<ResolveMemoryContextResponse> {
        private ResolveMemoryContextResponse response;
        private Throwable error;
        @Override public void onNext(ResolveMemoryContextResponse value) { response = value; }
        @Override public void onError(Throwable throwable) { error = throwable; }
        @Override public void onCompleted() { }
    }
}
