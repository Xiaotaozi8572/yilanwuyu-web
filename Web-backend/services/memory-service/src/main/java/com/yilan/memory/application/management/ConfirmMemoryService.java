package com.yilan.memory.application.management;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Appends a learner confirmation through the authority boundary. */
@Service
public final class ConfirmMemoryService {

    private final MemoryManagementRepository repository;

    public ConfirmMemoryService(MemoryManagementRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public MemoryManagementRepository.ActionResult confirm(
            String subjectHash, UUID memoryAssertionId, String expectedVersion, String idempotencyKey) {
        return repository.confirm(new MemoryManagementRepository.Mutation(
                subjectHash, memoryAssertionId, expectedVersion, idempotencyKey));
    }
}
