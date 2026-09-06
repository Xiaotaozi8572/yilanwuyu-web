package com.yilan.memory.application.management;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Subject-bound detail view with a fail-closed high-privacy gate. */
@Service
public final class GetMemoryExplanationService {

    private final MemoryManagementRepository repository;

    public GetMemoryExplanationService(MemoryManagementRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public MemoryManagementRepository.MemoryDetail get(String subjectHash, UUID memoryAssertionId, boolean freshAuthentication) {
        var detail = repository.findDetail(subjectHash, memoryAssertionId)
                .orElseThrow(MemoryManagementRepository.MemoryNotFoundException::new);
        if (detail.requiresFreshAuthentication() && !freshAuthentication) {
            throw new MemoryManagementRepository.FreshAuthenticationRequiredException();
        }
        return detail;
    }
}
