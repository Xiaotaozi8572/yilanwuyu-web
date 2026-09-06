package com.yilan.memory.application.management;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/** Subject-bound management list use case. */
@Service
public final class ListMemoriesService {

    private final MemoryManagementRepository repository;

    public ListMemoriesService(MemoryManagementRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public MemoryManagementRepository.MemoryPage list(
            String subjectHash, String cursor, String type, String status, Instant effectiveAfter) {
        return repository.list(new MemoryManagementRepository.ListQuery(subjectHash, cursor, type, status, effectiveAfter));
    }
}
