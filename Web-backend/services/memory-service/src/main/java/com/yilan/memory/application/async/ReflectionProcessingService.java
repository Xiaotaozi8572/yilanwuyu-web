package com.yilan.memory.application.async;

import java.util.Objects;
import java.util.UUID;

/**
 * Reflections use the same guarded worker proposal path; this class owns no
 * direct authority write, transition or downstream enqueue operation.
 */
public final class ReflectionProcessingService {

    private final CandidateProcessingService candidates;

    public ReflectionProcessingService(CandidateProcessingService candidates) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    public CandidateProcessingService.ProcessingResult process(UUID eventId) {
        return candidates.process(Objects.requireNonNull(eventId, "eventId"));
    }
}
