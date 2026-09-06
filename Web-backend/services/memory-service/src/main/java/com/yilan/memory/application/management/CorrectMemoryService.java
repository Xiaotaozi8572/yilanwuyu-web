package com.yilan.memory.application.management;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Appends a learner-corrected memory version through the authority boundary. */
@Service
public final class CorrectMemoryService {

    private final MemoryManagementRepository repository;

    public CorrectMemoryService(MemoryManagementRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public MemoryManagementRepository.ActionResult correct(
            String subjectHash,
            UUID memoryAssertionId,
            String expectedVersion,
            String idempotencyKey,
            Map<String, Object> correctedValue,
            String reason) {
        return repository.correct(new MemoryManagementRepository.Correction(
                subjectHash, memoryAssertionId, expectedVersion, idempotencyKey, correctedValue, reason));
    }
}
