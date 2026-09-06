package com.yilan.memory.application.async;

import com.yilan.memory.adapter.worker.CandidateWorkerClient;
import com.yilan.memory.adapter.worker.WorkerProposalValidator;
import com.yilan.memory.application.governance.GovernCandidateUseCase;
import com.yilan.memory.domain.governance.GovernanceDecision;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTrustBoundaryTest {

    @Test
    void rejectsWorkerAttemptToCreateAviationFactOrForeignSource() {
        assertRejectedWithoutActiveMemory(aviationFactResponse());
        assertRejectedWithoutActiveMemory(foreignSourceResponse());
    }

    @Test
    void workerUnavailableCannotPromoteOrPersistAnActiveMemory() {
        var source = CandidateProcessingServiceTest.source();
        var transactions = new CandidateProcessingServiceTest.RecordingTransactions();
        var repository = new CandidateProcessingServiceTest.RecordingCandidateRepository(source);
        var history = new CandidateProcessingServiceTest.RecordingHistory();
        CandidateWorkerClient unavailable = (request, deadline) -> {
            throw new CandidateWorkerClient.WorkerUnavailableException("WORKER_UNAVAILABLE");
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var processor = new CandidateProcessingService(
                    new CandidateProcessingServiceTest.SequencedSourceReader(source), unavailable,
                    new WorkerProposalValidator(), governance(repository, history, transactions),
                    CandidateProcessingServiceTest.properties(), Clock.fixed(
                    java.time.Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC), executor);

            var result = processor.process(UUID.randomUUID(), source.eventId());

            assertThat(result.diagnosticCode()).isEqualTo("WORKER_UNAVAILABLE");
            assertThat(repository.decisions()).isEmpty();
            assertThat(history.acceptedCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameAuthorityMaterialWithDifferentWorkerIdsProducesOneCandidateId() {
        var source = CandidateProcessingServiceTest.source();
        var request = new CandidateWorkerClient.WorkerRequest(
                "request-id", "v1", source.eventId().toString(), source.eventId().toString(),
                source.sourceDigest(), source.authorizedSourceText(),
                java.util.Set.of(com.yilan.memory.domain.governance.MemoryCandidate.MemoryType.PREFERENCE),
                source.locale(), source.policyVersion(), source.traceparent(), source.privacyLevel());
        var validator = new WorkerProposalValidator();

        var first = validator.validate(responseWithWorkerId("worker-id-a", request, source), request,
                        CandidateProcessingServiceTest.properties().worker())
                .proposals().getFirst();
        var second = validator.validate(responseWithWorkerId("worker-id-b", request, source), request,
                        CandidateProcessingServiceTest.properties().worker())
                .proposals().getFirst();

        assertThat(second.candidateId()).isEqualTo(first.candidateId());
    }

    private static void assertRejectedWithoutActiveMemory(CandidateWorkerClient.WorkerResponse response) {
        var source = CandidateProcessingServiceTest.source();
        var transactions = new CandidateProcessingServiceTest.RecordingTransactions();
        var repository = new CandidateProcessingServiceTest.RecordingCandidateRepository(source);
        var history = new CandidateProcessingServiceTest.RecordingHistory();
        var executor = Executors.newSingleThreadExecutor();
        try {
            CandidateWorkerClient worker = (request, deadline) ->
                    CandidateWorkerClient.WorkerResult.success(responseFor(request, response));
            var processor = new CandidateProcessingService(
                    new CandidateProcessingServiceTest.SequencedSourceReader(source, source), worker,
                    new WorkerProposalValidator(), governance(repository, history, transactions),
                    CandidateProcessingServiceTest.properties(), Clock.fixed(
                    java.time.Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC), executor);

            var result = processor.process(UUID.randomUUID(), source.eventId());

            assertThat(result.diagnosticCode()).isEqualTo("WORKER_BOUNDARY_VIOLATION");
            assertThat(result.decisions()).singleElement().satisfies(decision -> {
                assertThat(decision.decision()).isEqualTo(GovernanceDecision.Decision.REJECTED);
                assertThat(decision.reasonCodes()).containsExactly("WORKER_BOUNDARY_VIOLATION");
            });
            assertThat(repository.decisions()).singleElement().satisfies(decision ->
                    assertThat(decision.reasonCodes()).containsExactly("WORKER_BOUNDARY_VIOLATION"));
            assertThat(history.acceptedCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private static CandidateWorkerClient.WorkerResponse aviationFactResponse() {
        return new CandidateWorkerClient.WorkerResponse("ignored", "v1", List.of(
                new CandidateWorkerClient.WorkerProposal(
                        "candidate-malicious-fact", "AVIATION_FACT", "{}", 0.99d, "STANDARD", List.of())), "");
    }

    private static CandidateWorkerClient.WorkerResponse foreignSourceResponse() {
        var source = CandidateProcessingServiceTest.source();
        return new CandidateWorkerClient.WorkerResponse("ignored", "v1", List.of(
                new CandidateWorkerClient.WorkerProposal(
                        "candidate-malicious-source", "PREFERENCE", "{\"answer_style\":\"concise\"}",
                        0.99d, "STANDARD", List.of(new CandidateWorkerClient.SourceSpan(
                                UUID.fromString("00000000-0000-0000-0000-000000000999").toString(), 0,
                                source.authorizedSourceText().getBytes(StandardCharsets.UTF_8).length,
                                source.sourceDigest())))), "");
    }

    private static CandidateWorkerClient.WorkerResponse responseWithWorkerId(
            String workerId,
            CandidateWorkerClient.WorkerRequest request,
            CandidateProcessingService.AuthorizedSource source) {
        return new CandidateWorkerClient.WorkerResponse(
                request.requestId(), request.schemaVersion(), List.of(
                new CandidateWorkerClient.WorkerProposal(
                        workerId, "PREFERENCE", "{ \"answer_style\" : \"concise\" }", 0.80d, "STANDARD",
                        List.of(new CandidateWorkerClient.SourceSpan(
                                source.eventId().toString(), 0,
                                source.authorizedSourceText().getBytes(StandardCharsets.UTF_8).length,
                                source.sourceDigest())))), "");
    }

    private static CandidateWorkerClient.WorkerResponse responseFor(
            CandidateWorkerClient.WorkerRequest request,
            CandidateWorkerClient.WorkerResponse original) {
        return new CandidateWorkerClient.WorkerResponse(
                request.requestId(), original.schemaVersion(), original.proposals(), original.diagnosticCode());
    }

    private static GovernCandidateUseCase governance(
            CandidateProcessingServiceTest.RecordingCandidateRepository repository,
            CandidateProcessingServiceTest.RecordingHistory history,
            CandidateProcessingServiceTest.RecordingTransactions transactions) {
        var properties = CandidateProcessingServiceTest.properties();
        return new GovernCandidateUseCase(
                new com.yilan.memory.domain.governance.PromotionRule(properties), repository, history,
                subject -> java.util.Optional.of(new com.yilan.memory.domain.consent.ConsentPolicy(
                        com.yilan.memory.domain.consent.ConsentPolicy.SubjectStatus.ACTIVE,
                        com.yilan.memory.domain.consent.ConsentPolicy.PolicyStatus.ACTIVE, 3,
                        java.util.Set.of(com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory.PREFERENCE),
                        java.time.Instant.EPOCH, java.time.Instant.parse("2026-07-21T12:00:00Z"))),
                transactions, Clock.fixed(java.time.Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC));
    }
}
