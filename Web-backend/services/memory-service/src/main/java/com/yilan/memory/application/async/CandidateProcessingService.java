package com.yilan.memory.application.async;

import com.yilan.memory.adapter.worker.CandidateWorkerClient;
import com.yilan.memory.adapter.worker.WorkerProposalValidator;
import com.yilan.memory.application.governance.GovernCandidateUseCase;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.GovernanceDecision;
import com.yilan.memory.domain.governance.MemoryCandidate;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Background-only worker orchestration. The authority source is read before
 * and after the worker call, while the M1 use case is the sole writer of a
 * candidate, decision, active version and transition.
 */
public final class CandidateProcessingService implements AsyncStageProcessor {

    private final AuthoritySourceReader sources;
    private final CandidateWorkerClient worker;
    private final WorkerProposalValidator validator;
    private final GovernCandidateUseCase governance;
    private final MemoryProperties properties;
    private final Clock clock;
    private final ExecutorService workerExecutor;

    public CandidateProcessingService(
            AuthoritySourceReader sources,
            CandidateWorkerClient worker,
            WorkerProposalValidator validator,
            GovernCandidateUseCase governance,
            MemoryProperties properties,
            Clock clock,
            ExecutorService workerExecutor) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
    }

    public ProcessingResult process(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return ProcessingResult.noop("DURABLE_OUTBOX_ID_REQUIRED");
    }

    /**
     * Uses the outbox identifier as the authoritative schema-version lookup
     * key. Calls without that durable identifier fail closed before any reader
     * or worker boundary is invoked.
     */
    public ProcessingResult process(UUID outboxId, UUID eventId) {
        var context = new AsyncStageProcessor.EventContext(
                outboxId, eventId, new UUID(0L, 0L), StageName.CANDIDATE_EXTRACTION, 1, "direct-test");
        var prepared = prepareOutsideTransaction(context);
        return finalizeDetailed(context, prepared);
    }

    /**
     * Phase two of candidate extraction. It is deliberately source/worker
     * only: no governance call, database transaction, learner lock, or
     * plaintext-bearing attempt object crosses this boundary.
     */
    @Override
    public PreparedAttempt prepareOutsideTransaction(AsyncStageProcessor.EventContext context) {
        if (context == null || context.outboxId() == null || context.eventId() == null) {
            return PreparedAttempt.completed(StageOutcome.noop("DURABLE_OUTBOX_ID_REQUIRED"));
        }
        var beforeRead = sources.refetch(context.outboxId(), context.eventId());
        if (beforeRead instanceof SourceReadOutcome.Retryable retryable) {
            return PreparedAttempt.completed(StageOutcome.retryable(retryable.diagnosticCode()));
        }
        if (!(beforeRead instanceof SourceReadOutcome.Authorized authorized) || !eligible(authorized.source())) {
            return PreparedAttempt.completed(StageOutcome.noop("SOURCE_NOT_ELIGIBLE"));
        }
        var source = authorized.source();
        if (source.authorizedSourceText().getBytes(StandardCharsets.UTF_8).length > properties.worker().maxSourceBytes()) {
            return PreparedAttempt.completed(StageOutcome.noop("SOURCE_BYTE_LIMIT_EXCEEDED"));
        }
        var request = requestFor(source);
        var workerResult = invokeBounded(request);
        if (workerResult instanceof WorkerCallResult.Retryable retryable) {
            return PreparedAttempt.completed(StageOutcome.retryable(retryable.diagnosticCode()));
        }
        return new PreparedAttempt.Ready(
                SourceFingerprint.from(source), RequestDescriptor.from(request), workerAttempt(workerResult, request));
    }

    /**
     * Phase three runs only under the consumer's transaction B. It re-reads
     * the current source authority before the sole M1 governance write.
     */
    @Override
    public StageOutcome finalizeInTransaction(
            AsyncStageProcessor.EventContext context,
            AsyncStageProcessor.PreparedAttempt attempt) {
        return finalizeDetailed(context, attempt).outcome();
    }

    private ProcessingResult finalizeDetailed(
            AsyncStageProcessor.EventContext context,
            AsyncStageProcessor.PreparedAttempt rawAttempt) {
        if (!(rawAttempt instanceof PreparedAttempt attempt)) {
            return ProcessingResult.retryable("PROCESSING_FAILURE");
        }
        if (attempt instanceof PreparedAttempt.Completed completed) {
            return new ProcessingResult(completed.outcome(), List.of());
        }
        if (context == null || context.outboxId() == null || context.eventId() == null) {
            return ProcessingResult.noop("DURABLE_OUTBOX_ID_REQUIRED");
        }
        var ready = (PreparedAttempt.Ready) attempt;
        var afterRead = sources.refetch(context.outboxId(), context.eventId());
        if (afterRead instanceof SourceReadOutcome.Retryable retryable) {
            return ProcessingResult.retryable(retryable.diagnosticCode());
        }
        if (!(afterRead instanceof SourceReadOutcome.Authorized reauthorized)
                || !eligible(reauthorized.source())
                || !ready.source().matches(reauthorized.source())
                || reauthorized.source().authorizedSourceText().getBytes(StandardCharsets.UTF_8).length
                > properties.worker().maxSourceBytes()) {
            return ProcessingResult.noop("SOURCE_REVALIDATION_FAILED");
        }
        var source = reauthorized.source();
        var request = requestFor(source);
        if (!ready.request().matches(request)) {
            return ProcessingResult.noop("SOURCE_REVALIDATION_FAILED");
        }
        if (ready.workerAttempt() instanceof WorkerAttempt.Noop noop
                && request.requestId().equals(noop.requestId())
                && request.schemaVersion().equals(noop.schemaVersion())) {
            return ProcessingResult.noop("WORKER_NO_CANDIDATE");
        }
        if (ready.workerAttempt() instanceof WorkerAttempt.BoundaryViolation
                || ready.workerAttempt() instanceof WorkerAttempt.Noop) {
            return rejectWorkerBoundary(source);
        }
        var proposals = ((WorkerAttempt.Success) ready.workerAttempt()).proposals();
        var decisions = new ArrayList<GovernanceDecision>(proposals.size());
        for (var proposal : proposals) {
            var candidate = new MemoryCandidate(
                    proposal.candidateId(), source.subjectHash(), source.consentRevision(), proposal.memoryType(),
                    proposal.assertionKey(), proposal.valueJson(), proposal.confidence(), proposal.confidence(),
                    proposal.privacyLevel(), source.occurredAt(),
                    List.of(new MemoryCandidate.SourceReference(source.eventId(), source.schemaVersion())),
                    proposal.candidateCiphertext());
            decisions.add(governance.govern(candidate));
        }
        return ProcessingResult.success(decisions);
    }

    private WorkerAttempt workerAttempt(
            WorkerCallResult workerResult,
            CandidateWorkerClient.WorkerRequest request) {
        if (workerResult instanceof WorkerCallResult.Success success) {
            var validation = validator.validate(success.response(), request, properties.worker());
            return validation.accepted()
                    ? new WorkerAttempt.Success(validation.proposals())
                    : new WorkerAttempt.BoundaryViolation();
        }
        if (workerResult instanceof WorkerCallResult.Noop noop) {
            return new WorkerAttempt.Noop(noop.requestId(), noop.schemaVersion());
        }
        return new WorkerAttempt.BoundaryViolation();
    }

    private WorkerCallResult invokeBounded(CandidateWorkerClient.WorkerRequest request) {
        var deadline = properties.worker().backgroundDeadline();
        Future<CandidateWorkerClient.WorkerResult> future = workerExecutor.submit(() -> worker.propose(request, deadline));
        try {
            var result = future.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
            if (result instanceof CandidateWorkerClient.WorkerResult.Success success) {
                return new WorkerCallResult.Success(success.response());
            }
            if (result instanceof CandidateWorkerClient.WorkerResult.Retryable retryable) {
                return new WorkerCallResult.Retryable(retryable.diagnosticCode());
            }
            if (result instanceof CandidateWorkerClient.WorkerResult.Noop noop) {
                return new WorkerCallResult.Noop(noop.requestId(), noop.schemaVersion());
            }
            return new WorkerCallResult.BoundaryViolation();
        } catch (TimeoutException failure) {
            future.cancel(true);
            return new WorkerCallResult.Retryable("WORKER_DEADLINE_EXCEEDED");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new WorkerCallResult.Retryable("WORKER_UNAVAILABLE");
        } catch (ExecutionException failure) {
            var cause = failure.getCause();
            return new WorkerCallResult.Retryable(cause instanceof CandidateWorkerClient.WorkerUnavailableException unavailable
                    ? unavailable.diagnosticCode() : "WORKER_UNAVAILABLE");
        } catch (RuntimeException failure) {
            return new WorkerCallResult.Retryable("WORKER_UNAVAILABLE");
        }
    }

    private boolean eligible(AuthorizedSource source) {
        return source.allowedCandidateTypes().stream().anyMatch(properties.worker().allowedCandidateTypes()::contains);
    }

    private CandidateWorkerClient.WorkerRequest requestFor(AuthorizedSource source) {
        var allowed = source.allowedCandidateTypes().stream()
                .filter(properties.worker().allowedCandidateTypes()::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new CandidateWorkerClient.WorkerRequest(
                UUID.nameUUIDFromBytes(("worker-request/v1|" + source.eventId() + "|" + source.sourceDigest())
                        .getBytes(StandardCharsets.UTF_8)).toString(),
                properties.worker().schemaVersion(), source.eventId().toString(), source.eventId().toString(),
                source.sourceDigest(), source.authorizedSourceText(), allowed, source.locale(), source.policyVersion(),
                source.traceparent(), source.privacyLevel(), properties.worker().maxResponseBytes());
    }

    private ProcessingResult rejectWorkerBoundary(AuthorizedSource source) {
        var candidate = new MemoryCandidate(
                UUID.nameUUIDFromBytes(("worker-boundary/v1|" + source.eventId() + "|" + source.sourceDigest())
                        .getBytes(StandardCharsets.UTF_8)),
                source.subjectHash(), source.consentRevision(), MemoryType.OPTIMIZATION,
                "worker-boundary:" + source.eventId(), "{}", BigDecimal.ZERO, BigDecimal.ZERO,
                source.privacyLevel(), Instant.now(clock),
                List.of(new MemoryCandidate.SourceReference(source.eventId(), source.schemaVersion())),
                WorkerProposalValidator.sha256Bytes("worker-boundary/v1|" + source.eventId()));
        return ProcessingResult.boundaryRejected(List.of(governance.rejectWorkerBoundary(candidate)));
    }

    /**
     * Immutable material handed from the no-transaction worker phase to the
     * final authority phase. Neither variant contains source plaintext.
     */
    public sealed interface PreparedAttempt extends AsyncStageProcessor.PreparedAttempt
            permits PreparedAttempt.Ready, PreparedAttempt.Completed {

        static Completed completed(StageOutcome outcome) {
            return new Completed(outcome);
        }

        record Ready(SourceFingerprint source, RequestDescriptor request, WorkerAttempt workerAttempt)
                implements PreparedAttempt {
            public Ready {
                Objects.requireNonNull(source, "source");
                Objects.requireNonNull(request, "request");
                Objects.requireNonNull(workerAttempt, "workerAttempt");
            }
        }

        record Completed(StageOutcome outcome) implements PreparedAttempt {
            public Completed {
                Objects.requireNonNull(outcome, "outcome");
            }
        }
    }

    /** Non-secret source identity used to prove the post-worker re-read is stable. */
    public record SourceFingerprint(
            UUID eventId,
            String schemaVersion,
            String subjectHash,
            long consentRevision,
            String sourceDigest,
            PrivacyLevel privacyLevel,
            SourceKind sourceKind,
            Instant occurredAt,
            Set<MemoryType> allowedCandidateTypes,
            String locale,
            String policyVersion,
            String traceparent) {

        public SourceFingerprint {
            Objects.requireNonNull(eventId, "eventId");
            requireBounded(schemaVersion, "schemaVersion", 16);
            requireBounded(subjectHash, "subjectHash", 128);
            if (consentRevision < 0) {
                throw new IllegalArgumentException("consentRevision");
            }
            if (sourceDigest == null || !sourceDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sourceDigest");
            }
            Objects.requireNonNull(privacyLevel, "privacyLevel");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(occurredAt, "occurredAt");
            allowedCandidateTypes = Set.copyOf(Objects.requireNonNull(allowedCandidateTypes, "allowedCandidateTypes"));
            requireBounded(locale, "locale", 32);
            requireBounded(policyVersion, "policyVersion", 64);
            requireBounded(traceparent, "traceparent", 128);
        }

        static SourceFingerprint from(AuthorizedSource source) {
            return new SourceFingerprint(
                    source.eventId(), source.schemaVersion(), source.subjectHash(), source.consentRevision(),
                    source.sourceDigest(), source.privacyLevel(), source.sourceKind(), source.occurredAt(),
                    source.allowedCandidateTypes(), source.locale(), source.policyVersion(), source.traceparent());
        }

        boolean matches(AuthorizedSource source) {
            return eventId.equals(source.eventId())
                    && schemaVersion.equals(source.schemaVersion())
                    && subjectHash.equals(source.subjectHash())
                    && consentRevision == source.consentRevision()
                    && sourceDigest.equals(source.sourceDigest())
                    && privacyLevel == source.privacyLevel()
                    && sourceKind == source.sourceKind()
                    && occurredAt.equals(source.occurredAt())
                    && allowedCandidateTypes.equals(source.allowedCandidateTypes())
                    && locale.equals(source.locale())
                    && policyVersion.equals(source.policyVersion())
                    && traceparent.equals(source.traceparent());
        }
    }

    /** Worker request identity without the transient source text. */
    public record RequestDescriptor(
            String requestId,
            String schemaVersion,
            String eventId,
            String sourceId,
            String sourceDigest,
            Set<MemoryType> allowedCandidateTypes,
            String locale,
            String policyVersion,
            String traceparent,
            PrivacyLevel sourcePrivacyLevel) {

        public RequestDescriptor {
            requireBounded(requestId, "requestId", 128);
            requireBounded(schemaVersion, "schemaVersion", 16);
            requireBounded(eventId, "eventId", 64);
            requireBounded(sourceId, "sourceId", 64);
            if (sourceDigest == null || !sourceDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sourceDigest");
            }
            allowedCandidateTypes = Set.copyOf(Objects.requireNonNull(allowedCandidateTypes, "allowedCandidateTypes"));
            requireBounded(locale, "locale", 32);
            requireBounded(policyVersion, "policyVersion", 64);
            requireBounded(traceparent, "traceparent", 128);
            Objects.requireNonNull(sourcePrivacyLevel, "sourcePrivacyLevel");
        }

        static RequestDescriptor from(CandidateWorkerClient.WorkerRequest request) {
            return new RequestDescriptor(
                    request.requestId(), request.schemaVersion(), request.eventId(), request.sourceId(),
                    request.sourceDigest(), request.allowedCandidateTypes(), request.locale(), request.policyVersion(),
                    request.traceparent(), request.sourcePrivacyLevel());
        }

        boolean matches(CandidateWorkerClient.WorkerRequest request) {
            return requestId.equals(request.requestId())
                    && schemaVersion.equals(request.schemaVersion())
                    && eventId.equals(request.eventId())
                    && sourceId.equals(request.sourceId())
                    && sourceDigest.equals(request.sourceDigest())
                    && allowedCandidateTypes.equals(request.allowedCandidateTypes())
                    && locale.equals(request.locale())
                    && policyVersion.equals(request.policyVersion())
                    && traceparent.equals(request.traceparent())
                    && sourcePrivacyLevel == request.sourcePrivacyLevel();
        }
    }

    /** Bounded worker material retained between phases without source text. */
    public sealed interface WorkerAttempt permits WorkerAttempt.Success, WorkerAttempt.Noop,
            WorkerAttempt.BoundaryViolation {
        record Success(List<WorkerProposalValidator.ValidatedProposal> proposals) implements WorkerAttempt {
            public Success {
                proposals = List.copyOf(Objects.requireNonNull(proposals, "proposals"));
                if (proposals.isEmpty()) {
                    throw new IllegalArgumentException("proposals");
                }
            }
        }

        record Noop(String requestId, String schemaVersion) implements WorkerAttempt {
            public Noop {
                requireBounded(requestId, "requestId", 128);
                requireBounded(schemaVersion, "schemaVersion", 16);
            }
        }

        record BoundaryViolation() implements WorkerAttempt {
        }
    }

    private static void requireBounded(String value, String name, int maxBytes) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(name);
        }
    }

    public interface AuthoritySourceReader {
        /**
         * Durable implementations must derive the schema version from the
         * trusted outbox record, then resolve the exact event tuple.
         */
        SourceReadOutcome refetch(UUID outboxId, UUID eventId);
    }

    /**
     * Closed source-read result. The worker never receives source text until
     * an authoritative reader has returned {@link Authorized}; terminal
     * rejections fail closed while bounded retryable diagnostics remain
     * available to the checkpoint/replay layer.
     */
    public sealed interface SourceReadOutcome permits SourceReadOutcome.Authorized,
            SourceReadOutcome.TerminalNoop, SourceReadOutcome.Retryable {

        static Authorized authorized(AuthorizedSource source) {
            return new Authorized(source);
        }

        static TerminalNoop terminalNoop() {
            return new TerminalNoop();
        }

        static Retryable retryable(String diagnosticCode) {
            return new Retryable(diagnosticCode);
        }

        record Authorized(AuthorizedSource source) implements SourceReadOutcome {
            public Authorized {
                Objects.requireNonNull(source, "source");
            }
        }

        record TerminalNoop() implements SourceReadOutcome {
        }

        record Retryable(String diagnosticCode) implements SourceReadOutcome {
            public Retryable {
                if (diagnosticCode == null || !diagnosticCode.matches("[A-Z0-9_]{1,64}")) {
                    throw new IllegalArgumentException("diagnosticCode");
                }
            }
        }
    }

    /**
     * Authority-only source material after current subject binding, consent,
     * retention, category and privacy checks. Implementations must return
     * empty rather than forwarding a source that fails any of those checks.
     */
    public record AuthorizedSource(
            UUID eventId,
            String schemaVersion,
            String subjectHash,
            long consentRevision,
            String authorizedSourceText,
            String sourceDigest,
            PrivacyLevel privacyLevel,
            SourceKind sourceKind,
            Instant occurredAt,
            Set<MemoryType> allowedCandidateTypes,
            String locale,
            String policyVersion,
            String traceparent) {

        public AuthorizedSource {
            Objects.requireNonNull(eventId, "eventId");
            requireBounded(schemaVersion, "schemaVersion", 16);
            requireBounded(subjectHash, "subjectHash", 128);
            if (consentRevision < 0) {
                throw new IllegalArgumentException("consentRevision");
            }
            requireBounded(authorizedSourceText, "authorizedSourceText", 4_096);
            if (!WorkerProposalValidator.sha256(authorizedSourceText).equals(sourceDigest)) {
                throw new IllegalArgumentException("sourceDigest");
            }
            Objects.requireNonNull(privacyLevel, "privacyLevel");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(occurredAt, "occurredAt");
            allowedCandidateTypes = Set.copyOf(Objects.requireNonNull(allowedCandidateTypes, "allowedCandidateTypes"));
            if (allowedCandidateTypes.isEmpty()
                    || allowedCandidateTypes.contains(MemoryType.AVIATION_FACT)
                    || allowedCandidateTypes.contains(MemoryType.OPTIMIZATION)) {
                throw new IllegalArgumentException("allowedCandidateTypes");
            }
            requireBounded(locale, "locale", 32);
            requireBounded(policyVersion, "policyVersion", 64);
            requireBounded(traceparent, "traceparent", 128);
        }

        boolean stableForWorkerCall(AuthorizedSource other) {
            return eventId.equals(other.eventId)
                    && schemaVersion.equals(other.schemaVersion)
                    && subjectHash.equals(other.subjectHash)
                    && consentRevision == other.consentRevision
                    && authorizedSourceText.equals(other.authorizedSourceText)
                    && sourceDigest.equals(other.sourceDigest)
                    && privacyLevel == other.privacyLevel
                    && sourceKind == other.sourceKind
                    && occurredAt.equals(other.occurredAt)
                    && allowedCandidateTypes.equals(other.allowedCandidateTypes)
                    && locale.equals(other.locale)
                    && policyVersion.equals(other.policyVersion);
        }

        private static void requireBounded(String value, String name, int maxBytes) {
            if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException(name);
            }
        }
    }

    /**
     * Consumer-facing state for the Task 4 claim/work/finalize flow. A
     * retryable outcome is deliberately distinct from success and boundary
     * rejection so the checkpoint cannot be ACKed by a generic catch path.
     */
    public sealed interface StageOutcome permits StageOutcome.Success, StageOutcome.NoopSuccess,
            StageOutcome.BoundaryRejected, StageOutcome.Retryable {

        enum Kind { SUCCESS, NOOP_SUCCESS, BOUNDARY_REJECTED, RETRYABLE }

        Kind kind();

        String diagnosticCode();

        static Success success() {
            return new Success();
        }

        static NoopSuccess noop(String diagnosticCode) {
            return new NoopSuccess(diagnosticCode);
        }

        static BoundaryRejected boundaryRejected() {
            return new BoundaryRejected();
        }

        static Retryable retryable(String diagnosticCode) {
            return new Retryable(diagnosticCode);
        }

        record Success() implements StageOutcome {
            @Override public Kind kind() { return Kind.SUCCESS; }
            @Override public String diagnosticCode() { return ""; }
        }

        record NoopSuccess(String diagnosticCode) implements StageOutcome {
            public NoopSuccess {
                requireDiagnostic(diagnosticCode);
            }
            @Override public Kind kind() { return Kind.NOOP_SUCCESS; }
        }

        record BoundaryRejected() implements StageOutcome {
            @Override public Kind kind() { return Kind.BOUNDARY_REJECTED; }
            @Override public String diagnosticCode() { return WorkerProposalValidator.BOUNDARY_VIOLATION; }
        }

        record Retryable(String diagnosticCode) implements StageOutcome {
            public Retryable {
                requireDiagnostic(diagnosticCode);
            }
            @Override public Kind kind() { return Kind.RETRYABLE; }
        }

        private static void requireDiagnostic(String diagnosticCode) {
            if (diagnosticCode == null || !diagnosticCode.matches("[A-Z0-9_]{1,64}")) {
                throw new IllegalArgumentException("diagnosticCode");
            }
        }
    }

    public record ProcessingResult(StageOutcome outcome, List<GovernanceDecision> decisions) {
        public ProcessingResult {
            Objects.requireNonNull(outcome, "outcome");
            decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        }

        static ProcessingResult success(List<GovernanceDecision> decisions) {
            return new ProcessingResult(new StageOutcome.Success(), decisions);
        }

        static ProcessingResult noop(String diagnosticCode) {
            return new ProcessingResult(new StageOutcome.NoopSuccess(diagnosticCode), List.of());
        }

        static ProcessingResult boundaryRejected(List<GovernanceDecision> decisions) {
            return new ProcessingResult(new StageOutcome.BoundaryRejected(), decisions);
        }

        static ProcessingResult retryable(String diagnosticCode) {
            return new ProcessingResult(new StageOutcome.Retryable(diagnosticCode), List.of());
        }

        public StageOutcome.Kind kind() {
            return outcome.kind();
        }

        public String diagnosticCode() {
            return outcome.diagnosticCode();
        }
    }

    private sealed interface WorkerCallResult permits WorkerCallResult.Success, WorkerCallResult.Noop,
            WorkerCallResult.Retryable, WorkerCallResult.BoundaryViolation {
        record Success(CandidateWorkerClient.WorkerResponse response) implements WorkerCallResult {
            public Success {
                Objects.requireNonNull(response, "response");
            }
        }

        record Retryable(String diagnosticCode) implements WorkerCallResult {
            public Retryable {
                if (diagnosticCode == null || !diagnosticCode.matches("[A-Z0-9_]{1,64}")) {
                    throw new IllegalArgumentException("diagnosticCode");
                }
            }
        }

        record Noop(String requestId, String schemaVersion) implements WorkerCallResult {
            public Noop {
                if (requestId == null || requestId.isBlank() || schemaVersion == null || schemaVersion.isBlank()) {
                    throw new IllegalArgumentException("worker no-op identity");
                }
            }
        }

        record BoundaryViolation() implements WorkerCallResult {
        }
    }
}
