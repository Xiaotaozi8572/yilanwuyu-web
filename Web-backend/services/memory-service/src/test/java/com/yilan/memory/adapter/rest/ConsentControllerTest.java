package com.yilan.memory.adapter.rest;

import com.yilan.memory.application.consent.ConsentCommand;
import com.yilan.memory.application.consent.ConsentService;
import com.yilan.memory.application.consent.ConsentView;
import com.yilan.memory.application.consent.PolicyEpochService;
import com.yilan.memory.security.AuthenticatedSubject;
import com.yilan.memory.security.MemoryRole;
import com.yilan.memory.security.SubjectBindingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConsentControllerTest {

    @Test
    void implementsOnlyExistingBoundMemoryConsentRoute() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ConsentController(new ConsentService(
                new Store(), new PolicyEpochService(subject -> { }),
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC),
                "memory-consent-v1", java.time.Duration.ofDays(30)))).build();
        var subject = new AuthenticatedSubject("a".repeat(43), Set.of(MemoryRole.LEARNER));

        mvc.perform(get("/v1/me/memory-consent")
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.long_term_enabled").value(false));
        mvc.perform(put("/v1/me/memory-consent")
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                        .header("If-Match", "0")
                        .header("Idempotency-Key", "k".repeat(16))
                        .contentType("application/json")
                        .content("{\"long_term_enabled\":true,\"allowed_categories\":[\"PREFERENCE\"],\"retention_days\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1"));
    }

    @Test
    void allowsWithdrawalWithEmptyCategoriesUsingExistingVersionPrecondition() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ConsentController(new ConsentService(
                new Store(), new PolicyEpochService(subject -> { }),
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC),
                "memory-consent-v1", java.time.Duration.ofDays(30)))).build();
        var subject = new AuthenticatedSubject("b".repeat(43), Set.of(MemoryRole.LEARNER));

        mvc.perform(put("/v1/me/memory-consent")
                        .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                        .header("If-Match", "0")
                        .header("Idempotency-Key", "k".repeat(16))
                        .contentType("application/json")
                        .content("{\"long_term_enabled\":false,\"allowed_categories\":[],\"retention_days\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.long_term_enabled").value(false))
                .andExpect(jsonPath("$.allowed_categories").isEmpty());
    }

    @Test
    void forwardsIdempotencyKeyAndReplaysTheOriginalFrozenResponse() throws Exception {
        var store = new Store();
        var mvc = mvc(store);
        var subject = new AuthenticatedSubject("c".repeat(43), Set.of(MemoryRole.LEARNER));
        var key = "consent-idempotency-key-000000000201";
        var request = put("/v1/me/memory-consent")
                .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                .header("If-Match", "0")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"long_term_enabled\":true,\"allowed_categories\":[\"PREFERENCE\"],\"retention_days\":7}");

        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.effective_at").value("2026-07-22T00:00:00Z"));
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.effective_at").value("2026-07-22T00:00:00Z"));
        assertThat(store.idempotencyKeys()).containsExactly(key, key);
        assertThat(store.authorityWrites()).isEqualTo(1);
    }

    @Test
    void mapsStaleAndIdempotencyConflictsToExistingProblemCodes() throws Exception {
        var subject = new AuthenticatedSubject("d".repeat(43), Set.of(MemoryRole.LEARNER));
        var request = put("/v1/me/memory-consent")
                .requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, subject)
                .header("If-Match", "0")
                .header("Idempotency-Key", "consent-idempotency-key-000000000202")
                .contentType("application/json")
                .content("{\"long_term_enabled\":true,\"allowed_categories\":[\"PREFERENCE\"],\"retention_days\":7}");

        mvc(new StaleStore()).perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mvc(new ConflictStore()).perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(ConsentService.ConsentStore store) {
        return MockMvcBuilders.standaloneSetup(new ConsentController(new ConsentService(
                        store, new PolicyEpochService(subject -> { }),
                        Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC),
                        "memory-consent-v1", java.time.Duration.ofDays(30))))
                .setControllerAdvice(new RestProblemHandler())
                .build();
    }

    private static final class Store implements ConsentService.ConsentStore {
        private Optional<ConsentService.StoredConsent> current = Optional.empty();
        private final List<String> idempotencyKeys = new ArrayList<>();
        private String receiptKey;
        private String receiptRequest;
        private ConsentService.StoredConsent receiptResult;
        private int authorityWrites;

        @Override public Optional<ConsentService.StoredConsent> find(String subjectHash) { return current; }
        @Override public ConsentService.Commit append(String subjectHash, long expectedRevision, ConsentService.Policy policy) {
            var actual = current.map(ConsentService.StoredConsent::revision).orElse(0L);
            if (actual != expectedRevision) {
                throw new ConsentService.StaleConsentVersionException();
            }
            current = Optional.of(new ConsentService.StoredConsent(actual + 1, policy.longTermEnabled(), policy.allowedCategories(), policy.effectiveAt()));
            authorityWrites++;
            return new ConsentService.Commit(actual + 1, authorityWrites);
        }
        @Override public ConsentService.AppendResult append(
                String subjectHash, long expectedRevision, ConsentService.Policy policy, String idempotencyKey) {
            idempotencyKeys.add(idempotencyKey);
            var request = expectedRevision + "|" + policy.longTermEnabled() + "|"
                    + policy.allowedCategories().stream().map(Enum::name).sorted().toList() + "|" + policy.retentionDays();
            if (receiptKey != null && receiptKey.equals(idempotencyKey)) {
                if (!receiptRequest.equals(request)) {
                    throw new ConsentService.IdempotencyConflictException();
                }
                return new ConsentService.AppendResult(receiptResult, 0, true);
            }
            append(subjectHash, expectedRevision, policy);
            receiptKey = idempotencyKey;
            receiptRequest = request;
            receiptResult = current.orElseThrow();
            return new ConsentService.AppendResult(receiptResult, authorityWrites, false);
        }

        List<String> idempotencyKeys() {
            return List.copyOf(idempotencyKeys);
        }

        int authorityWrites() {
            return authorityWrites;
        }
    }

    private static final class StaleStore implements ConsentService.ConsentStore {
        @Override public Optional<ConsentService.StoredConsent> find(String subjectHash) { return Optional.empty(); }
        @Override public ConsentService.Commit append(String subjectHash, long expectedRevision, ConsentService.Policy policy) {
            throw new ConsentService.StaleConsentVersionException();
        }
        @Override public ConsentService.AppendResult append(
                String subjectHash, long expectedRevision, ConsentService.Policy policy, String idempotencyKey) {
            throw new ConsentService.StaleConsentVersionException();
        }
    }

    private static final class ConflictStore implements ConsentService.ConsentStore {
        @Override public Optional<ConsentService.StoredConsent> find(String subjectHash) { return Optional.empty(); }
        @Override public ConsentService.Commit append(String subjectHash, long expectedRevision, ConsentService.Policy policy) {
            throw new ConsentService.IdempotencyConflictException();
        }
        @Override public ConsentService.AppendResult append(
                String subjectHash, long expectedRevision, ConsentService.Policy policy, String idempotencyKey) {
            throw new ConsentService.IdempotencyConflictException();
        }
    }
}
