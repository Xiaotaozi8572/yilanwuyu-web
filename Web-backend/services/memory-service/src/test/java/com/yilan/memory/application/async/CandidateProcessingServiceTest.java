package com.yilan.memory.application.async;

import com.yilan.memory.adapter.worker.CandidateWorkerClient;
import com.yilan.memory.adapter.worker.WorkerProposalValidator;
import com.yilan.memory.application.async.CandidateProcessingService.SourceReadOutcome;
import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.application.governance.GovernCandidateUseCase;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.GovernanceDecision;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.domain.governance.PromotionRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateProcessingServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDownWorkerExecutor() {
        workerExecutor.shutdownNow();
    }

    @Test
    void refetchesThenGovernsAValidPreferenceWithoutHoldingTransactionAcrossWorkerCall() {
        var source = source();
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        var workerCalledInsideTransaction = new AtomicBoolean();
        var worker = (CandidateWorkerClient) (request, deadline) -> {
            workerCalledInsideTransaction.set(transactions.inTransaction());
            assertThat(request.authorizedSourceText()).isEqualTo(source.authorizedSourceText());
            assertThat(request.allowedCandidateTypes()).containsExactly(MemoryType.PREFERENCE);
            assertThat(deadline).isEqualTo(Duration.ofMillis(40));
            return CandidateWorkerClient.WorkerResult.success(validResponse(request, source));
        };
        var reader = new SequencedSourceReader(source, source);
        var processor = processor(reader, worker, repository, history, transactions);

        var result = processor.process(UUID.randomUUID(), source.eventId());

        assertThat(workerCalledInsideTransaction).isFalse();
        assertThat(reader.calls()).isEqualTo(2);
        assertThat(result.diagnosticCode()).isEmpty();
        assertThat(result.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.decision()).isEqualTo(GovernanceDecision.Decision.ACCEPTED));
        assertThat(repository.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.reasonCodes()).containsExactly("RULES_SATISFIED"));
        assertThat(history.acceptedCount()).isOne();
    }

    @Test
    void workerDeadlineLeavesNoCandidateOrActiveMemory() {
        var source = source();
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient slowWorker = (request, deadline) -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CandidateWorkerClient.WorkerUnavailableException("WORKER_DEADLINE_EXCEEDED");
            }
            return CandidateWorkerClient.WorkerResult.success(validResponse(request, source));
        };
        var processor = processor(new SequencedSourceReader(source), slowWorker, repository, history, transactions);

        var result = processor.process(UUID.randomUUID(), source.eventId());

        assertThat(result.diagnosticCode()).isEqualTo("WORKER_DEADLINE_EXCEEDED");
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void preparedAttemptContainsNoSourceTextAndDefersGovernanceUntilFinalize() {
        var source = source();
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient worker = (request, deadline) ->
                CandidateWorkerClient.WorkerResult.success(validResponse(request, source));
        var processor = processor(new SequencedSourceReader(source, source), worker, repository, history, transactions);
        var context = new AsyncStageProcessor.EventContext(
                UUID.randomUUID(), source.eventId(), UUID.randomUUID(), StageName.CANDIDATE_EXTRACTION, 1,
                "trace-prepared-attempt");

        var prepared = processor.prepareOutsideTransaction(context);

        assertThat(prepared.toString()).doesNotContain(source.authorizedSourceText());
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();

        assertThat(processor.finalizeInTransaction(context, prepared).kind())
                .isEqualTo(CandidateProcessingService.StageOutcome.Kind.SUCCESS);
        assertThat(history.acceptedCount()).isOne();
    }

    @Test
    void rejectedWorkerEchoIsCollapsedBeforeThePreparedAttemptCrossesTransactions() {
        var source = source();
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient worker = (request, deadline) -> CandidateWorkerClient.WorkerResult.success(
                new CandidateWorkerClient.WorkerResponse(request.requestId(), request.schemaVersion(), List.of(
                new CandidateWorkerClient.WorkerProposal(
                        "untrusted-worker-id", "PREFERENCE",
                        "{\"answer_style\":\"" + source.authorizedSourceText() + "\"}", 0.8d, "STANDARD",
                        List.of(new CandidateWorkerClient.SourceSpan(request.sourceId(), 0,
                                source.authorizedSourceText().getBytes(StandardCharsets.UTF_8).length,
                                request.sourceDigest())))), ""));
        var processor = processor(new SequencedSourceReader(source, source), worker, repository, history, transactions);
        var context = new AsyncStageProcessor.EventContext(
                UUID.randomUUID(), source.eventId(), UUID.randomUUID(), StageName.CANDIDATE_EXTRACTION, 1,
                "trace-rejected-attempt");

        var prepared = processor.prepareOutsideTransaction(context);

        assertThat(prepared.toString()).doesNotContain(source.authorizedSourceText());
        assertThat(processor.finalizeInTransaction(context, prepared).kind())
                .isEqualTo(CandidateProcessingService.StageOutcome.Kind.BOUNDARY_REJECTED);
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void repeatedWorkerProposalUsesTheSameCandidateDecisionWithoutSecondActiveAppend() {
        var source = source();
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient worker = (request, deadline) ->
                CandidateWorkerClient.WorkerResult.success(validResponse(request, source));
        var processor = processor(new SequencedSourceReader(source), worker, repository, history, transactions);

        var first = processor.process(UUID.randomUUID(), source.eventId());
        var second = processor.process(UUID.randomUUID(), source.eventId());

        assertThat(first.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.decision()).isEqualTo(GovernanceDecision.Decision.ACCEPTED));
        assertThat(second.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.decision()).isEqualTo(GovernanceDecision.Decision.ACCEPTED));
        assertThat(history.acceptedCount()).isOne();
        assertThat(repository.decisions()).hasSize(1);
    }

    @Test
    void retryableSourceOutcomePreservesBoundedDiagnosticWithoutCallingWorker() {
        var source = source();
        var reader = (CandidateProcessingService.AuthoritySourceReader) (outboxId, eventId) ->
                SourceReadOutcome.retryable("SOURCE_KEY_UNAVAILABLE");
        var invocations = new AtomicInteger();
        CandidateWorkerClient worker = (request, deadline) -> {
            invocations.incrementAndGet();
            return CandidateWorkerClient.WorkerResult.success(validResponse(request, source));
        };
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        var result = processor(reader, worker, repository, history, transactions)
                .process(UUID.randomUUID(), source.eventId());

        assertThat(result.diagnosticCode()).isEqualTo("SOURCE_KEY_UNAVAILABLE");
        assertThat(invocations).hasValue(0);
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void retryableWorkerDiagnosticDoesNotCreateBoundaryRejectionOrAuthorityWrites() {
        var source = source();
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient worker = (request, deadline) ->
                CandidateWorkerClient.WorkerResult.retryable("MODEL_UNAVAILABLE_RETRYABLE");

        var result = processor(new SequencedSourceReader(source), worker, repository, history, transactions)
                .process(UUID.randomUUID(), source.eventId());

        assertThat(result.kind()).isEqualTo(CandidateProcessingService.StageOutcome.Kind.RETRYABLE);
        assertThat(result.diagnosticCode()).isEqualTo("MODEL_UNAVAILABLE_RETRYABLE");
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void terminalSourceOutcomeCompletesAsNoopWithoutCallingWorker() {
        var source = source();
        var invocations = new AtomicInteger();
        CandidateWorkerClient worker = (request, deadline) -> {
            invocations.incrementAndGet();
            return CandidateWorkerClient.WorkerResult.success(validResponse(request, source));
        };
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();

        var result = processor((outboxId, eventId) -> SourceReadOutcome.terminalNoop(), worker,
                repository, history, transactions).process(UUID.randomUUID(), source.eventId());

        assertThat(result.kind()).isEqualTo(CandidateProcessingService.StageOutcome.Kind.NOOP_SUCCESS);
        assertThat(invocations).hasValue(0);
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void workerNoCandidateOutcomeCompletesAsNoopWithoutAuthorityWrites() {
        var source = source();
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient worker = (request, deadline) ->
                CandidateWorkerClient.WorkerResult.noop(request.requestId(), request.schemaVersion());

        var reader = new SequencedSourceReader(source, source);
        var result = processor(reader, worker, repository, history, transactions)
                .process(UUID.randomUUID(), source.eventId());

        assertThat(result.kind()).isEqualTo(CandidateProcessingService.StageOutcome.Kind.NOOP_SUCCESS);
        assertThat(result.diagnosticCode()).isEqualTo("WORKER_NO_CANDIDATE");
        assertThat(reader.calls()).isEqualTo(2);
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void workerNoCandidateOutcomeWithRetryableSecondSourceReadRemainsRetryableWithoutAuthorityWrites() {
        var source = source();
        var reads = new AtomicInteger();
        CandidateProcessingService.AuthoritySourceReader reader = (outboxId, eventId) ->
                reads.getAndIncrement() == 0 ? SourceReadOutcome.authorized(source)
                        : SourceReadOutcome.retryable("SOURCE_KEY_UNAVAILABLE");
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient worker = (request, deadline) ->
                CandidateWorkerClient.WorkerResult.noop(request.requestId(), request.schemaVersion());

        var result = processor(reader, worker, repository, history, transactions)
                .process(UUID.randomUUID(), source.eventId());

        assertThat(result.kind()).isEqualTo(CandidateProcessingService.StageOutcome.Kind.RETRYABLE);
        assertThat(result.diagnosticCode()).isEqualTo("SOURCE_KEY_UNAVAILABLE");
        assertThat(reads).hasValue(2);
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void noCandidateOutcomeWithMismatchedRequestIdentityIsBoundaryRejectedAfterStableRevalidation() {
        var source = source();
        var reader = new SequencedSourceReader(source, source);
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient worker = (request, deadline) ->
                CandidateWorkerClient.WorkerResult.noop("wrong-request-id", request.schemaVersion());

        var result = processor(reader, worker, repository, history, transactions)
                .process(UUID.randomUUID(), source.eventId());

        assertThat(reader.calls()).isEqualTo(2);
        assertThat(result.kind()).isEqualTo(CandidateProcessingService.StageOutcome.Kind.BOUNDARY_REJECTED);
        assertThat(result.diagnosticCode()).isEqualTo("WORKER_BOUNDARY_VIOLATION");
        assertThat(result.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.decision()).isEqualTo(GovernanceDecision.Decision.REJECTED));
        assertThat(repository.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.reasonCodes()).containsExactly("WORKER_BOUNDARY_VIOLATION"));
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void boundaryWorkerResultAfterSourceRevocationCompletesAsNoopWithoutAuthorityWrites() {
        var source = source();
        var reads = new AtomicInteger();
        CandidateProcessingService.AuthoritySourceReader reader = (outboxId, eventId) ->
                reads.getAndIncrement() == 0 ? SourceReadOutcome.authorized(source) : SourceReadOutcome.terminalNoop();
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        CandidateWorkerClient worker = (request, deadline) -> CandidateWorkerClient.WorkerResult.boundaryViolation();

        var result = processor(reader, worker, repository, history, transactions)
                .process(UUID.randomUUID(), source.eventId());

        assertThat(result.kind()).isEqualTo(CandidateProcessingService.StageOutcome.Kind.NOOP_SUCCESS);
        assertThat(reads).hasValue(2);
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();
    }

    @Test
    void workerSettingsRejectResponseAndProposalLimitsOutsideTheProtoContract() {
        assertThatThrownBy(() -> new MemoryProperties.WorkerSettings(
                "v1", Duration.ofMillis(40), 4_096, 16_385, 6, Set.of(MemoryType.PREFERENCE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryProperties.WorkerSettings(
                "v1", Duration.ofMillis(40), 4_096, 0, 6, Set.of(MemoryType.PREFERENCE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryProperties.WorkerSettings(
                "v1", Duration.ofMillis(40), 4_096, 16_384, 7, Set.of(MemoryType.PREFERENCE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryProperties.WorkerSettings(
                "v1", Duration.ofMillis(40), 4_096, 16_384, 0, Set.of(MemoryType.PREFERENCE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneArgumentProcessFailsClosedWithoutCallingReaderOrWorker() {
        var source = source();
        var readerCalls = new AtomicInteger();
        var workerCalls = new AtomicInteger();
        var reader = (CandidateProcessingService.AuthoritySourceReader) (outboxId, eventId) -> {
            readerCalls.incrementAndGet();
            return SourceReadOutcome.authorized(source);
        };
        CandidateWorkerClient worker = (request, deadline) -> {
            workerCalls.incrementAndGet();
            return CandidateWorkerClient.WorkerResult.success(validResponse(request, source));
        };
        var transactions = new RecordingTransactions();
        var repository = new RecordingCandidateRepository(source);
        var history = new RecordingHistory();
        var result = processor(reader, worker, repository, history, transactions).process(source.eventId());

        assertThat(result.diagnosticCode()).isEqualTo("DURABLE_OUTBOX_ID_REQUIRED");
        assertThat(readerCalls).hasValue(0);
        assertThat(workerCalls).hasValue(0);
        assertThat(repository.decisions()).isEmpty();
        assertThat(history.acceptedCount()).isZero();
    }

    private CandidateProcessingService processor(
            CandidateProcessingService.AuthoritySourceReader reader,
            CandidateWorkerClient worker,
            RecordingCandidateRepository repository,
            RecordingHistory history,
            RecordingTransactions transactions) {
        var properties = properties();
        var governance = new GovernCandidateUseCase(
                new PromotionRule(properties), repository, history, activePolicy(), transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new CandidateProcessingService(
                reader, worker, new WorkerProposalValidator(), governance, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), workerExecutor);
    }

    private static CandidateWorkerClient.WorkerResponse validResponse(
            CandidateWorkerClient.WorkerRequest request,
            CandidateProcessingService.AuthorizedSource source) {
        return new CandidateWorkerClient.WorkerResponse(
                request.requestId(), request.schemaVersion(), List.of(
                new CandidateWorkerClient.WorkerProposal(
                        "candidate-0123456789abcdef0123456789abcdef",
                        "PREFERENCE", "{\"answer_style\":\"concise\"}", 0.80d, "STANDARD",
                        List.of(new CandidateWorkerClient.SourceSpan(
                                source.eventId().toString(), 0,
                                source.authorizedSourceText().getBytes(StandardCharsets.UTF_8).length,
                                source.sourceDigest())))), "");
    }

    static CandidateProcessingService.AuthorizedSource source() {
        var eventId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        var text = "请以后回答短一点";
        return new CandidateProcessingService.AuthorizedSource(
                eventId, "v1", "learner-worker-a", 3, text,
                WorkerProposalValidator.sha256(text), PrivacyLevel.STANDARD,
                SourceKind.EXPLICIT_DECLARATION, NOW.minus(Duration.ofMinutes(1)),
                Set.of(MemoryType.PREFERENCE), "zh-CN", "policy-3", "trace-worker-a");
    }

    static MemoryProperties properties() {
        var rules = new EnumMap<MemoryType, MemoryProperties.TypeRule>(MemoryType.class);
        for (var type : MemoryType.values()) {
            rules.put(type, new MemoryProperties.TypeRule(1, new BigDecimal("0.70"), Duration.ofDays(30)));
        }
        return new MemoryProperties(
                "rules-worker-v1", rules,
                new MemoryProperties.RetrievalWeights(
                        new BigDecimal("0.30"), new BigDecimal("0.20"),
                        new BigDecimal("0.50"), new BigDecimal("0.10")),
                60, new MemoryProperties.Budgets(8, 900),
                new MemoryProperties.WorkerSettings(
                        "v1", Duration.ofMillis(40), 4_096, 6, Set.of(MemoryType.PREFERENCE)));
    }

    private static ConsentQuery.PolicyReader activePolicy() {
        return subject -> Optional.of(new ConsentPolicy(
                ConsentPolicy.SubjectStatus.ACTIVE, ConsentPolicy.PolicyStatus.ACTIVE, 3,
                Set.of(ConsentPolicy.MemoryCategory.PREFERENCE), Instant.EPOCH,
                NOW.plus(Duration.ofDays(1))));
    }

    static final class SequencedSourceReader implements CandidateProcessingService.AuthoritySourceReader {
        private final List<CandidateProcessingService.AuthorizedSource> sources;
        private int calls;

        SequencedSourceReader(CandidateProcessingService.AuthorizedSource... sources) {
            this.sources = List.of(sources);
        }

        @Override
        public SourceReadOutcome refetch(UUID outboxId, UUID eventId) {
            if (outboxId == null) {
                return SourceReadOutcome.terminalNoop();
            }
            if (!sources.get(Math.min(calls++, sources.size() - 1)).eventId().equals(eventId)) {
                return SourceReadOutcome.terminalNoop();
            }
            return SourceReadOutcome.authorized(sources.get(Math.min(calls - 1, sources.size() - 1)));
        }

        int calls() {
            return calls;
        }
    }

    static final class RecordingTransactions implements TransactionOperations {
        private boolean inTransaction;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            inTransaction = true;
            try {
                return action.doInTransaction(new TransactionStatus() {
                    @Override public boolean isNewTransaction() { return true; }
                    @Override public boolean hasSavepoint() { return false; }
                    @Override public void setRollbackOnly() { }
                    @Override public boolean isRollbackOnly() { return false; }
                    @Override public void flush() { }
                    @Override public boolean isCompleted() { return false; }
                    @Override public Object createSavepoint() { throw new UnsupportedOperationException(); }
                    @Override public void rollbackToSavepoint(Object savepoint) { throw new UnsupportedOperationException(); }
                    @Override public void releaseSavepoint(Object savepoint) { throw new UnsupportedOperationException(); }
                });
            } finally {
                inTransaction = false;
            }
        }

        boolean inTransaction() {
            return inTransaction;
        }
    }

    static final class RecordingCandidateRepository implements GovernCandidateUseCase.CandidateRepository {
        private final CandidateProcessingService.AuthorizedSource source;
        private final List<GovernanceDecision> decisions = new ArrayList<>();
        private final Map<UUID, GovernanceDecision> decisionsByCandidateId = new HashMap<>();

        RecordingCandidateRepository(CandidateProcessingService.AuthorizedSource source) {
            this.source = source;
        }

        @Override
        public Optional<GovernanceDecision> findExistingDecision(MemoryCandidate candidate) {
            return Optional.ofNullable(decisionsByCandidateId.get(candidate.candidateId()));
        }

        @Override
        public List<MemoryCandidate.ValidatedSource> validateSources(MemoryCandidate candidate) {
            return candidate.sources().stream().map(reference -> new MemoryCandidate.ValidatedSource(
                    reference, source.sourceKind(), source.occurredAt(), source.privacyLevel())).toList();
        }

        @Override
        public GovernCandidateUseCase.CandidateAppendResult appendIfAbsent(
                MemoryCandidate candidate, GovernanceDecision decision, Instant authorityNow) {
            decisions.add(decision);
            decisionsByCandidateId.put(candidate.candidateId(), decision);
            return new GovernCandidateUseCase.CandidateAppendResult(true, decision);
        }

        List<GovernanceDecision> decisions() {
            return List.copyOf(decisions);
        }
    }

    static final class RecordingHistory implements GovernCandidateUseCase.MemoryHistoryRepository {
        private int acceptedCount;

        @Override
        public void appendAccepted(
                MemoryCandidate candidate,
                GovernanceDecision decision,
                List<MemoryCandidate.ValidatedSource> validatedSources,
                MemoryProperties properties,
                Instant authorityNow) {
            acceptedCount++;
        }

        int acceptedCount() {
            return acceptedCount;
        }
    }
}
