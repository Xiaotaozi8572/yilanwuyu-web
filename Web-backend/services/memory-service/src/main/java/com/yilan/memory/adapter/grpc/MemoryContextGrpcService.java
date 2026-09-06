package com.yilan.memory.adapter.grpc;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.application.migration.AuthorityMode;
import com.yilan.memory.application.migration.AuthorityModeService;
import com.yilan.memory.observability.MemoryMetrics;
import com.yilan.memory.contract.v1.CapabilityRequest;
import com.yilan.memory.contract.v1.CapabilityResponse;
import com.yilan.memory.contract.v1.MemoryApplicationStatus;
import com.yilan.memory.contract.v1.MemoryContextServiceGrpc;
import com.yilan.memory.contract.v1.ResolveMemoryContextRequest;
import com.yilan.memory.contract.v1.ResolveMemoryContextResponse;
import com.yilan.memory.domain.identity.LearnerIdentity;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** v1 resolve/capability transport; signed metadata is its sole identity source. */
public class MemoryContextGrpcService extends MemoryContextServiceGrpc.MemoryContextServiceImplBase {

    private final ResolveMemoryContextUseCase useCase;
    private final GrpcContractMapper mapper;
    private final IdentityResolver identityResolver;
    private final AuthorityModeService authorityModes;
    private final MemoryMetrics metrics;

    @Autowired
    public MemoryContextGrpcService(
            ResolveMemoryContextUseCase useCase,
            AuthorityModeService authorityModes,
            MemoryMetrics metrics) {
        this(useCase, new GrpcContractMapper(), SignedSessionInterceptor::requireIdentity, authorityModes, metrics);
    }

    /** Direct construction is fail-closed; Spring injects durable authority state above. */
    public MemoryContextGrpcService(ResolveMemoryContextUseCase useCase) {
        this(useCase, new GrpcContractMapper(), SignedSessionInterceptor::requireIdentity,
                AuthorityModeService.disabled(), MemoryMetrics.noop());
    }

    MemoryContextGrpcService(
            ResolveMemoryContextUseCase useCase,
            GrpcContractMapper mapper,
            IdentityResolver identityResolver) {
        this(useCase, mapper, identityResolver, AuthorityModeService.disabled(), MemoryMetrics.noop());
    }

    MemoryContextGrpcService(
            ResolveMemoryContextUseCase useCase,
            GrpcContractMapper mapper,
            IdentityResolver identityResolver,
            AuthorityModeService authorityModes) {
        this(useCase, mapper, identityResolver, authorityModes, MemoryMetrics.noop());
    }

    MemoryContextGrpcService(
            ResolveMemoryContextUseCase useCase,
            GrpcContractMapper mapper,
            IdentityResolver identityResolver,
            AuthorityModeService authorityModes,
            MemoryMetrics metrics) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.authorityModes = Objects.requireNonNull(authorityModes, "authorityModes");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void resolveMemoryContext(
            ResolveMemoryContextRequest request,
            StreamObserver<ResolveMemoryContextResponse> responseObserver) {
        try {
            var identity = Objects.requireNonNull(identityResolver.require(Context.current()), "identity");
            var digest = identityBindingDigest(identity, request.getRequestId());
            final ResolveMemoryContextUseCase.MemoryQuery query;
            try {
                mapper.validateResolveHeader(request);
                if (!request.getSessionId().equals(identity.sessionId())) {
                    emit(responseObserver, mapper.degradedResponse(
                            "IDENTITY_BINDING_MISMATCH", request.getRequestId(), digest, request.getTraceparent()));
                    return;
                }
                query = mapper.toMemoryQuery(request);
            } catch (GrpcContractMapper.ContractViolation violation) {
                emit(responseObserver, mapper.degradedResponse(
                        violation.diagnosticCode(), request.getRequestId(), digest, request.getTraceparent()));
                return;
            }
            if (authorityModes.state().mode() != AuthorityMode.REMOTE) {
                emit(responseObserver, mapper.degradedResponse(
                        "RESOLUTION_FAILURE", request.getRequestId(), digest, request.getTraceparent()));
                return;
            }

            var result = useCase.resolve(identity, query);
            var response = mapper.toProto(result, request.getRequestId(), GrpcContractMapper.SCHEMA_VERSION,
                    digest, request.getTraceparent());
            if (response.getStatus() == MemoryApplicationStatus.APPLIED
                    && response.getContext().getSerializedSize()
                    > Integer.toUnsignedLong(request.getBudget().getMaxBytes())) {
                response = mapper.degradedResponse(
                        "RESOLUTION_FAILURE", request.getRequestId(), digest, request.getTraceparent());
            }
            emit(responseObserver, response);
        } catch (RuntimeException error) {
            metrics.record("resolve", "unavailable", "java.grpc", "grpc", "unavailable", request.getTraceparent(), 0L);
            responseObserver.onError(GrpcExceptionMapper.toStatus(error));
        }
    }

    @Override
    public void getCapabilities(
            CapabilityRequest request,
            StreamObserver<CapabilityResponse> responseObserver) {
        try {
            identityResolver.require(Context.current());
            responseObserver.onNext(mapper.capabilities(request, authorityModes.capability()));
            responseObserver.onCompleted();
            metrics.record("capabilities", "accepted", "java.grpc", "grpc", "none", request.getTraceparent(), 0L);
        } catch (GrpcContractMapper.ContractViolation violation) {
            metrics.record("capabilities", "invalid", "java.grpc", "grpc", "validation_failure", request.getTraceparent(), 0L);
            responseObserver.onError(GrpcExceptionMapper.invalidCapabilityRequest(violation.diagnosticCode()));
        } catch (RuntimeException error) {
            metrics.record("capabilities", "unavailable", "java.grpc", "grpc", "unavailable", request.getTraceparent(), 0L);
            responseObserver.onError(GrpcExceptionMapper.toStatus(error));
        }
    }

    private void emit(
            StreamObserver<ResolveMemoryContextResponse> observer,
            ResolveMemoryContextResponse response) {
        if (response.getStatus() == MemoryApplicationStatus.DEGRADED) {
            response = response.toBuilder().clearContext().build();
        }
        observer.onNext(response);
        observer.onCompleted();
        metrics.record(
                "resolve",
                response.getStatus() == MemoryApplicationStatus.APPLIED
                        ? "accepted" : response.getStatus() == MemoryApplicationStatus.EMPTY ? "empty" : "degraded",
                "java.grpc", "grpc", "none", response.getTraceId(), 0L);
    }

    private static String identityBindingDigest(
            LearnerIdentity identity,
            String requestId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, identity.subjectHash());
            updateDigest(digest, identity.sessionId());
            updateDigest(digest, Objects.requireNonNullElse(requestId, ""));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    @FunctionalInterface
    interface IdentityResolver {
        LearnerIdentity require(Context context);
    }
}
