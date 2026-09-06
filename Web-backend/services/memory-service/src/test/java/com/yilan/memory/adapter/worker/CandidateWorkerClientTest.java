package com.yilan.memory.adapter.worker;

import com.yilan.memory.contract.v1.ProposalResponse;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateWorkerClientTest {

    @Test
    void classifiesAllowedProviderDiagnosticAsRetryableWithoutMappingProposals() {
        var response = ProposalResponse.newBuilder()
                .setRequestId("request-1")
                .setSchemaVersion("v1")
                .setDiagnosticCode("MODEL_UNAVAILABLE_RETRYABLE")
                .build();

        var result = CandidateWorkerClient.fromGrpcResponse(response, 16_384);

        assertThat(result).isInstanceOf(CandidateWorkerClient.WorkerResult.Retryable.class);
        assertThat(((CandidateWorkerClient.WorkerResult.Retryable) result).diagnosticCode())
                .isEqualTo("MODEL_UNAVAILABLE_RETRYABLE");
    }

    @Test
    void rejectsAnOversizedGeneratedProtobufResponseBeforeProposalMapping() {
        var response = ProposalResponse.newBuilder()
                .setRequestId("request-1")
                .setSchemaVersion("v1")
                .setDiagnosticCode("x".repeat(128))
                .build();

        var result = CandidateWorkerClient.fromGrpcResponse(response, 8);

        assertThat(response.getSerializedSize()).isGreaterThan(8);
        assertThat(result).isInstanceOf(CandidateWorkerClient.WorkerResult.BoundaryViolation.class);
        assertThat(((CandidateWorkerClient.WorkerResult.BoundaryViolation) result).diagnosticCode())
                .isEqualTo("WORKER_BOUNDARY_VIOLATION");
    }

    @Test
    void rejectsUnknownNonEmptyWorkerDiagnostic() {
        var response = ProposalResponse.newBuilder()
                .setRequestId("request-1")
                .setSchemaVersion("v1")
                .setDiagnosticCode("INVALID_CONSTRAINED_PROVIDER_OUTPUT")
                .build();

        var result = CandidateWorkerClient.fromGrpcResponse(response, 16_384);

        assertThat(result).isInstanceOf(CandidateWorkerClient.WorkerResult.BoundaryViolation.class);
    }

    @Test
    void classifiesAnEmptySuccessfulProposalResponseAsNoop() {
        var response = ProposalResponse.newBuilder()
                .setRequestId("request-1")
                .setSchemaVersion("v1")
                .build();

        var result = CandidateWorkerClient.fromGrpcResponse(response, 16_384);

        assertThat(result).isInstanceOf(CandidateWorkerClient.WorkerResult.Noop.class);
    }

    @Test
    void keepsOnlyDeadlineAndUnavailableTransportFailuresRetryable() {
        assertThat(CandidateWorkerClient.fromTransportFailure(Status.DEADLINE_EXCEEDED.asRuntimeException()))
                .isInstanceOf(CandidateWorkerClient.WorkerResult.Retryable.class);
        assertThat(CandidateWorkerClient.fromTransportFailure(Status.UNAVAILABLE.asRuntimeException()))
                .isInstanceOf(CandidateWorkerClient.WorkerResult.Retryable.class);
        assertThat(CandidateWorkerClient.fromTransportFailure(Status.INVALID_ARGUMENT.asRuntimeException()))
                .isInstanceOf(CandidateWorkerClient.WorkerResult.BoundaryViolation.class);
        assertThat(CandidateWorkerClient.fromTransportFailure(Status.DATA_LOSS.asRuntimeException()))
                .isInstanceOf(CandidateWorkerClient.WorkerResult.BoundaryViolation.class);
    }

    @Test
    void classifiesOnlyApprovedProviderDiagnosticsAsRetryable() {
        for (var diagnosticCode : List.of(
                "MODEL_UNAVAILABLE_RETRYABLE", "WORKER_PROVIDER_FAILURE",
                "BACKGROUND_CAPACITY_EXHAUSTED", "ONLINE_ANSWER_PRESSURE",
                "MISSING_HEALTH_LEASE", "TEMPERATURE_THRESHOLD_EXCEEDED", "CUDA_OOM")) {
            var response = ProposalResponse.newBuilder()
                    .setRequestId("request-1")
                    .setSchemaVersion("v1")
                    .setDiagnosticCode(diagnosticCode)
                    .build();

            assertThat(CandidateWorkerClient.fromGrpcResponse(response, 16_384))
                    .isInstanceOf(CandidateWorkerClient.WorkerResult.Retryable.class);
        }
    }

    @Test
    void sourceOneUtf8ByteOverTheProtoLimitCannotCrossTheWorkerRequestBoundary() {
        assertThatThrownBy(() -> new CandidateWorkerClient.WorkerRequest(
                "request-1", "v1", "event-1", "source-1", "a".repeat(64), "a".repeat(4_097),
                java.util.Set.of(com.yilan.memory.domain.governance.MemoryCandidate.MemoryType.PREFERENCE),
                "und", "policy-1", "trace-1",
                com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel.STANDARD))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
