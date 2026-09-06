package com.yilan.memory.application.management;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Appends a learner disable transition through the authority boundary. */
@Service
public final class DisableMemoryService {

    private final MemoryManagementRepository repository;

    public DisableMemoryService(MemoryManagementRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public MemoryManagementRepository.ActionResult disable(
            String subjectHash, UUID memoryAssertionId, String expectedVersion, String idempotencyKey) {
        return repository.disable(new MemoryManagementRepository.Mutation(
                subjectHash, memoryAssertionId, expectedVersion, idempotencyKey));
    }
}
