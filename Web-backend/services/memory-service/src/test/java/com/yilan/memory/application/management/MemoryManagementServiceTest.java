package com.yilan.memory.application.management;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryManagementServiceTest {

    @Test
    void highPrivacyDetailFailsClosedBeforeItCanLeaveTheApplicationBoundary() {
        var id = UUID.randomUUID();
        var repository = new RecordingRepository(detail(id, "HIGH"));

        assertThatThrownBy(() -> new GetMemoryExplanationService(repository).get(subject(), id, false))
                .isInstanceOf(MemoryManagementRepository.FreshAuthenticationRequiredException.class);
        assertThat(new GetMemoryExplanationService(repository).get(subject(), id, true).privacyClass()).isEqualTo("HIGH");
    }

    @Test
    void correctionDelegatesOnlyTheVerifiedSubjectAndOpaqueMutationBoundary() {
        var id = UUID.randomUUID();
        var repository = new RecordingRepository(detail(id, "STANDARD"));
        var result = new CorrectMemoryService(repository).correct(
                subject(), id, "1", "k".repeat(16), Map.of("preference", "stepwise"), "user-update");

        assertThat(result.detail().id()).isEqualTo(id);
        assertThat(repository.lastCorrection.correctedValue()).containsEntry("preference", "stepwise");
    }

    private static String subject() {
        return "s".repeat(43);
    }

    private static MemoryManagementRepository.MemoryDetail detail(UUID id, String privacyClass) {
        return new MemoryManagementRepository.MemoryDetail(
                id, "1", "PREFERENCE", "ACTIVE", "UNCONFIRMED", Map.of("preference", "brief"), "HIGH",
                Instant.parse("2026-07-22T00:00:00Z"), null, "OPTIONAL", privacyClass,
                List.of("linked-source"), null, List.of());
    }

    private static final class RecordingRepository implements MemoryManagementRepository {
        private final MemoryDetail detail;
        private Correction lastCorrection;

        private RecordingRepository(MemoryDetail detail) {
            this.detail = detail;
        }

        @Override public MemoryPage list(ListQuery query) { return new MemoryPage(List.of(), null); }
        @Override public Optional<MemoryDetail> findDetail(String subjectHash, UUID memoryAssertionId) {
            return detail.id().equals(memoryAssertionId) ? Optional.of(detail) : Optional.empty();
        }
        @Override public ActionResult confirm(Mutation mutation) { return new ActionResult(detail); }
        @Override public ActionResult correct(Correction mutation) {
            lastCorrection = mutation;
            return new ActionResult(detail);
        }
        @Override public ActionResult disable(Mutation mutation) { return new ActionResult(detail); }
    }
}
