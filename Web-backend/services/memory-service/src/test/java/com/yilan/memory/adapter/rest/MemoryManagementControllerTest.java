package com.yilan.memory.adapter.rest;

import com.yilan.memory.application.management.ConfirmMemoryService;
import com.yilan.memory.application.management.CorrectMemoryService;
import com.yilan.memory.application.management.DisableMemoryService;
import com.yilan.memory.application.management.GetMemoryExplanationService;
import com.yilan.memory.application.management.ListMemoriesService;
import com.yilan.memory.application.management.MemoryManagementRepository;
import com.yilan.memory.security.AuthenticatedSubject;
import com.yilan.memory.security.MemoryRole;
import com.yilan.memory.security.SubjectBindingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryManagementControllerTest {

    @Test
    void servesOnlyBoundFrozenManagementRoutesWithRequiredMutationHeaders() throws Exception {
        var id = UUID.randomUUID();
        var repository = new FixtureRepository(detail(id, "STANDARD"));
        var mvc = mvc(repository);
        var subject = subject(false);

        mvc.perform(get("/v1/me/memories")
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].confirmation_status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.items[0].display_value.preference").value("brief"));
        mvc.perform(post("/v1/me/memories/{id}:confirm", id)
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "k".repeat(16)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(post("/v1/me/memories/{id}:correct", id)
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "c".repeat(16))
                        .contentType("application/json")
                        .content("{\"corrected_value\":{\"preference\":\"stepwise\"},\"reason\":\"user-update\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_summary[0]").value("linked-source"));
        mvc.perform(post("/v1/me/memories/{id}:disable", id)
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "d".repeat(16)))
                .andExpect(status().isOk());
    }

    @Test
    void highPrivacyDetailAndInvalidIdempotencyKeyFailWithPayloadFreeStableCodes() throws Exception {
        var highPrivacy = UUID.randomUUID();
        var repository = new FixtureRepository(detail(highPrivacy, "HIGH"));
        var mvc = mvc(repository);

        mvc.perform(get("/v1/me/memories/{id}", highPrivacy)
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FRESH_AUTH_REQUIRED"));
        mvc.perform(post("/v1/me/memories/{id}:confirm", highPrivacy)
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject(true))
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "too-short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedManagementRequestsHaveStablePayloadFreeProblemCodes() throws Exception {
        var id = UUID.randomUUID();
        var mvc = mvc(new FixtureRepository(detail(id, "STANDARD")));
        var subject = subject(false);

        var missingHeaders = mvc.perform(post("/v1/me/memories/{id}:confirm", id)
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        var malformedId = mvc.perform(get("/v1/me/memories/not-a-uuid")
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        var missingValue = mvc.perform(post("/v1/me/memories/{id}:correct", id)
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "a".repeat(16))
                        .contentType("application/json")
                        .content("{\"reason\":\"sensitive-request-text\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        var nullValue = mvc.perform(post("/v1/me/memories/{id}:correct", id)
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "b".repeat(16))
                        .contentType("application/json")
                        .content("{\"corrected_value\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        var invalidCategory = mvc.perform(get("/v1/me/memories?type=NOT_A_CATEGORY")
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CATEGORY"))
                .andReturn().getResponse().getContentAsString();
        assertThat(missingHeaders + malformedId + missingValue + nullValue + invalidCategory)
                .doesNotContain("sensitive-request-text", "not-a-uuid", "NOT_A_CATEGORY");
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(MemoryManagementRepository repository) {
        return MockMvcBuilders.standaloneSetup(new MemoryManagementController(
                        new ListMemoriesService(repository),
                        new GetMemoryExplanationService(repository),
                        new ConfirmMemoryService(repository),
                        new CorrectMemoryService(repository),
                        new DisableMemoryService(repository)))
                .setControllerAdvice(new RestProblemHandler())
                .build();
    }

    private static AuthenticatedSubject subject(boolean fresh) {
        return new AuthenticatedSubject("s".repeat(43), java.util.Set.of(MemoryRole.LEARNER), fresh);
    }

    private static MemoryManagementRepository.MemoryDetail detail(UUID id, String privacyClass) {
        return new MemoryManagementRepository.MemoryDetail(
                id, "1", "PREFERENCE", "ACTIVE", "UNCONFIRMED", Map.of("preference", "brief"), "HIGH",
                Instant.parse("2026-07-22T00:00:00Z"), null, "OPTIONAL", privacyClass,
                List.of("linked-source"), null, List.of());
    }

    private static final class FixtureRepository implements MemoryManagementRepository {
        private final MemoryDetail detail;

        private FixtureRepository(MemoryDetail detail) {
            this.detail = detail;
        }

        @Override public MemoryPage list(ListQuery query) {
            return new MemoryPage(List.of(new MemorySummary(
                    detail.id(), detail.version(), detail.type(), detail.status(), detail.confirmationStatus(),
                    detail.displayValue(), detail.confidenceBand(), detail.effectiveAt(), detail.expiresAt())), null);
        }
        @Override public Optional<MemoryDetail> findDetail(String subjectHash, UUID memoryAssertionId) {
            return detail.id().equals(memoryAssertionId) ? Optional.of(detail) : Optional.empty();
        }
        @Override public ActionResult confirm(Mutation mutation) { return new ActionResult(detail); }
        @Override public ActionResult correct(Correction mutation) { return new ActionResult(detail); }
        @Override public ActionResult disable(Mutation mutation) { return new ActionResult(detail); }
    }
}
