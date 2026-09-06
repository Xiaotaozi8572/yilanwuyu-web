package com.yilan.memory.adapter.worker;

import com.yilan.memory.observability.MemoryMetrics;
import com.yilan.memory.observability.TraceContextCodec;

import com.yilan.memory.contract.v1.CandidateProposalServiceGrpc;
import com.yilan.memory.contract.v1.ProposalRequest;
import com.yilan.memory.contract.v1.ProposalResponse;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The only Java-to-worker transport boundary. It returns a small closed result
 * set so replay handling can keep transient worker failures pending without
 * turning them into governed boundary-rejection candidates.
 */
@FunctionalInterface
public interface CandidateWorkerClient {

    WorkerResult propose(WorkerRequest request, Duration deadline);

    static CandidateWorkerClient grpc(CandidateProposalServiceGrpc.CandidateProposalServiceBlockingStub stub) {
        return grpc(stub, 16_384, MemoryMetrics.noop());
    }

    static CandidateWorkerClient grpc(
            CandidateProposalServiceGrpc.CandidateProposalServiceBlockingStub stub,
            int maxResponseBytes) {
        return grpc(stub, maxResponseBytes, MemoryMetrics.noop());
    }

    static CandidateWorkerClient grpc(
            CandidateProposalServiceGrpc.CandidateProposalServiceBlockingStub stub,
            int maxResponseBytes,
            MemoryMetrics metrics) {
        Objects.requireNonNull(stub, "stub");
        requireMaxResponseBytes(maxResponseBytes);
        Objects.requireNonNull(metrics, "metrics");
        return (request, deadline) -> {
            requireDeadline(deadline);
            var relayTraceparent = TraceContextCodec.sanitizeForRelay(request.traceparent()).orElse("");
            try {
                var response = stub.withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS)
                        .proposeCandidates(ProposalRequest.newBuilder()
                                .setRequestId(request.requestId())
                                .setSchemaVersion(request.schemaVersion())
                                .setEventId(request.eventId())
                                .setSourceId(request.sourceId())
                                .setAuthorizedSourceText(request.authorizedSourceText())
                                .addAllAllowedCandidateTypes(request.allowedCandidateTypes().stream()
                                        .map(MemoryType::name).sorted().toList())
                                .setLocale(request.locale())
                                .setPolicyVersion(request.policyVersion())
                                .setTraceparent(relayTraceparent)
                                .build());
                var result = fromGrpcResponse(response, Math.min(maxResponseBytes, request.maxResponseBytes()));
                record(metrics, result, request.traceparent());
                return result;
            } catch (StatusRuntimeException failure) {
                var result = fromTransportFailure(failure);
                record(metrics, result, request.traceparent());
                return result;
            } catch (RuntimeException failure) {
                var result = WorkerResult.retryable("WORKER_UNAVAILABLE");
                record(metrics, result, request.traceparent());
                return result;
            }
        };
    }

    /**
     * Package-visible for transport-boundary tests. The size check intentionally
     * occurs while the generated protobuf is still available, before any raw
     * proposal fields are copied into the Java-side worker response.
     */
    static WorkerResult fromGrpcResponse(ProposalResponse response, int maxResponseBytes) {
        Objects.requireNonNull(response, "response");
        requireMaxResponseBytes(maxResponseBytes);
        if (response.getSerializedSize() > maxResponseBytes) {
            return WorkerResult.boundaryViolation();
        }
        if (!response.getDiagnosticCode().isBlank()) {
            if (response.getProposalsCount() == 0 && isAllowedRetryableProviderDiagnostic(response.getDiagnosticCode())) {
                return WorkerResult.retryable(response.getDiagnosticCode());
            }
            return WorkerResult.boundaryViolation();
        }
        if (response.getProposalsCount() == 0) {
            return WorkerResult.noop(response.getRequestId(), response.getSchemaVersion());
        }
        return WorkerResult.success(new WorkerResponse(
                response.getRequestId(), response.getSchemaVersion(),
                response.getProposalsList().stream().map(proposal -> new WorkerProposal(
                        proposal.getCandidateId(), proposal.getMemoryType(), proposal.getNormalizedValueJson(),
                        proposal.getConfidence(), proposal.getPrivacyClass(),
                        proposal.getSourceSpansList().stream().map(span -> new SourceSpan(
                                span.getSourceId(), span.getStartOffset(), span.getEndOffset(),
                                span.getSourceDigest())).toList())).toList(), ""));
    }

    private static void requireDeadline(Duration deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
    }

    private static void requireMaxResponseBytes(int maxResponseBytes) {
        if (maxResponseBytes < 1 || maxResponseBytes > 16_384) {
            throw new IllegalArgumentException("maxResponseBytes");
        }
    }

    private static boolean isAllowedRetryableProviderDiagnostic(String diagnosticCode) {
        return switch (diagnosticCode) {
            case "MODEL_UNAVAILABLE_RETRYABLE", "WORKER_PROVIDER_FAILURE",
                    "BACKGROUND_CAPACITY_EXHAUSTED", "ONLINE_ANSWER_PRESSURE",
                    "MISSING_HEALTH_LEASE", "TEMPERATURE_THRESHOLD_EXCEEDED", "CUDA_OOM" -> true;
            default -> false;
        };
    }

    static WorkerResult fromTransportFailure(StatusRuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        return switch (failure.getStatus().getCode()) {
            case DEADLINE_EXCEEDED -> WorkerResult.retryable("WORKER_DEADLINE_EXCEEDED");
            case UNAVAILABLE -> WorkerResult.retryable("WORKER_UNAVAILABLE");
            default -> WorkerResult.boundaryViolation();
        };
    }

    private static void record(MemoryMetrics metrics, WorkerResult result, String traceparent) {
        var status = switch (result) {
            case WorkerResult.Success ignored -> "accepted";
            case WorkerResult.Noop ignored -> "empty";
            case WorkerResult.Retryable ignored -> "unavailable";
            case WorkerResult.BoundaryViolation ignored -> "rejected";
        };
        var diagnostic = result instanceof WorkerResult.Retryable ? "unavailable"
                : result instanceof WorkerResult.BoundaryViolation ? "validation_failure" : "none";
        metrics.record("propose", status, "java.worker", "worker", diagnostic, traceparent, 0L);
    }

    sealed interface WorkerResult permits WorkerResult.Success, WorkerResult.Noop,
            WorkerResult.Retryable, WorkerResult.BoundaryViolation {

        static Success success(WorkerResponse response) {
            return new Success(response);
        }

        static Retryable retryable(String diagnosticCode) {
            return new Retryable(diagnosticCode);
        }

        static Noop noop(String requestId, String schemaVersion) {
            return new Noop(requestId, schemaVersion);
        }

        static BoundaryViolation boundaryViolation() {
            return new BoundaryViolation();
        }

        record Success(WorkerResponse response) implements WorkerResult {
            public Success {
                Objects.requireNonNull(response, "response");
            }
        }

        record Noop(String requestId, String schemaVersion) implements WorkerResult {
            public Noop {
                requireNonBlank(requestId, "requestId");
                requireNonBlank(schemaVersion, "schemaVersion");
            }
        }

        record Retryable(String diagnosticCode) implements WorkerResult {
            public Retryable {
                if (diagnosticCode == null || !isAllowedRetryableDiagnostic(diagnosticCode)) {
                    throw new IllegalArgumentException("diagnosticCode");
                }
            }
        }

        record BoundaryViolation() implements WorkerResult {
            public String diagnosticCode() {
                return "WORKER_BOUNDARY_VIOLATION";
            }
        }
    }

    private static boolean isAllowedRetryableDiagnostic(String diagnosticCode) {
        return "WORKER_DEADLINE_EXCEEDED".equals(diagnosticCode)
                || "WORKER_UNAVAILABLE".equals(diagnosticCode)
                || isAllowedRetryableProviderDiagnostic(diagnosticCode);
    }

    record WorkerRequest(
            String requestId,
            String schemaVersion,
            String eventId,
            String sourceId,
            String sourceDigest,
            String authorizedSourceText,
            Set<MemoryType> allowedCandidateTypes,
            String locale,
            String policyVersion,
            String traceparent,
            PrivacyLevel sourcePrivacyLevel,
            int maxResponseBytes) {

        public WorkerRequest(
                String requestId,
                String schemaVersion,
                String eventId,
                String sourceId,
                String sourceDigest,
                String authorizedSourceText,
                Set<MemoryType> allowedCandidateTypes,
                String locale,
                String policyVersion,
                String traceparent,
                PrivacyLevel sourcePrivacyLevel) {
            this(requestId, schemaVersion, eventId, sourceId, sourceDigest, authorizedSourceText,
                    allowedCandidateTypes, locale, policyVersion, traceparent, sourcePrivacyLevel, 16_384);
        }

        public WorkerRequest {
            requireNonBlank(requestId, "requestId");
            requireNonBlank(schemaVersion, "schemaVersion");
            requireNonBlank(eventId, "eventId");
            requireNonBlank(sourceId, "sourceId");
            requireDigest(sourceDigest);
            requireNonBlank(authorizedSourceText, "authorizedSourceText");
            if (authorizedSourceText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4_096) {
                throw new IllegalArgumentException("authorizedSourceText");
            }
            allowedCandidateTypes = Set.copyOf(Objects.requireNonNull(allowedCandidateTypes, "allowedCandidateTypes"));
            if (allowedCandidateTypes.isEmpty()) {
                throw new IllegalArgumentException("allowedCandidateTypes");
            }
            requireNonBlank(locale, "locale");
            requireNonBlank(policyVersion, "policyVersion");
            requireNonBlank(traceparent, "traceparent");
            Objects.requireNonNull(sourcePrivacyLevel, "sourcePrivacyLevel");
            requireMaxResponseBytes(maxResponseBytes);
        }
    }

    record WorkerResponse(String requestId, String schemaVersion, List<WorkerProposal> proposals, String diagnosticCode) {
        public WorkerResponse {
            proposals = List.copyOf(Objects.requireNonNull(proposals, "proposals"));
            diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
        }
    }

    record WorkerProposal(
            String workerCandidateId,
            String memoryType,
            String normalizedValueJson,
            double confidence,
            String privacyClass,
            List<SourceSpan> sourceSpans) {

        public WorkerProposal {
            sourceSpans = List.copyOf(Objects.requireNonNull(sourceSpans, "sourceSpans"));
        }
    }

    record SourceSpan(String sourceId, int startOffset, int endOffset, String sourceDigest) {
    }

    final class WorkerUnavailableException extends RuntimeException {
        private final String diagnosticCode;

        public WorkerUnavailableException(String diagnosticCode) {
            super(safeDiagnosticCode(diagnosticCode));
            this.diagnosticCode = safeDiagnosticCode(diagnosticCode);
        }

        public String diagnosticCode() {
            return diagnosticCode;
        }

        private static String safeDiagnosticCode(String diagnosticCode) {
            return "WORKER_DEADLINE_EXCEEDED".equals(diagnosticCode)
                    ? "WORKER_DEADLINE_EXCEEDED" : "WORKER_UNAVAILABLE";
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name);
        }
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceDigest");
        }
    }
}
