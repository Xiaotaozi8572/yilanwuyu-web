package com.yilan.memory.application.async;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit no-authority seam for background embedding work. Embeddings cannot
 * create or activate memory; durable embedding persistence remains outside
 * this task's allowlist and must be introduced through a future authority use
 * case rather than a worker callback.
 */
public final class EmbeddingProcessingService {

    public ProcessingResult process(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return new ProcessingResult(eventId, "EMBEDDING_NOT_PERSISTED");
    }

    public record ProcessingResult(UUID eventId, String diagnosticCode) {
        public ProcessingResult {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        }
    }
}
