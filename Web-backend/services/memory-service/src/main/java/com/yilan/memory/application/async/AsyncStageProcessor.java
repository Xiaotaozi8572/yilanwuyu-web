package com.yilan.memory.application.async;

import java.util.UUID;

/**
 * Redis-free application port for one asynchronous processing stage.
 * Implementations prepare work outside the checkpoint transaction and
 * revalidate/finalize it inside the authority transaction.
 */
public interface AsyncStageProcessor {

    /** Runs source read and worker interaction after transaction A commits. */
    PreparedAttempt prepareOutsideTransaction(EventContext context) throws Exception;

    /** Runs authority revalidation/governance only in transaction B. */
    CandidateProcessingService.StageOutcome finalizeInTransaction(EventContext context, PreparedAttempt attempt)
            throws Exception;

    /** Marker for immutable, source-text-free work material between phases. */
    interface PreparedAttempt {
    }

    /** Identifier-only metadata passed across the asynchronous stage port. */
    record EventContext(
            UUID outboxId,
            UUID eventId,
            UUID learnerSubjectId,
            StageName stageName,
            int stageVersion,
            String traceparent) {
    }
}
