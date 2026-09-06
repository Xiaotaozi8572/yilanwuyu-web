package com.yilan.memory.adapter.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yilan.memory.application.consent.ConsentCommand;
import com.yilan.memory.application.consent.ConsentService;
import com.yilan.memory.application.consent.ConsentView;
import com.yilan.memory.security.MemoryRole;
import com.yilan.memory.security.SubjectBindingFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Implements the frozen subject-bound memory-consent OpenAPI route. */
@RestController
@RequestMapping(path = "/v1/me/memory-consent", produces = "application/json")
public final class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @GetMapping
    public ConsentView current(HttpServletRequest request) {
        return consentService.current(requireLearner(request).subjectHash());
    }

    @org.springframework.web.bind.annotation.PutMapping(consumes = "application/json")
    public ConsentView replace(
            HttpServletRequest request,
            @RequestHeader("If-Match") String expectedVersion,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConsentUpdate update) {
        if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Idempotency-Key");
        }
        return consentService.replace(
                requireLearner(request).subjectHash(),
                new ConsentCommand(update.longTermEnabled(), validatedCategories(update.allowedCategories()), update.retentionDays()),
                expectedVersion,
                idempotencyKey,
                MemoryRole.LEARNER);
    }

    private static com.yilan.memory.security.AuthenticatedSubject requireLearner(HttpServletRequest request) {
        var subject = SubjectBindingFilter.requireBoundSubject(request);
        if (!subject.hasRole(MemoryRole.LEARNER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "learner role required");
        }
        return subject;
    }

    private static Set<String> validatedCategories(List<String> allowedCategories) {
        var values = new LinkedHashSet<>(allowedCategories);
        if (values.size() != allowedCategories.size()) {
            throw new IllegalArgumentException("duplicate allowed_categories");
        }
        return Set.copyOf(values);
    }

    public record ConsentUpdate(
            @JsonProperty("long_term_enabled") @NotNull Boolean longTermEnabled,
            @JsonProperty("allowed_categories") @NotNull List<@NotNull String> allowedCategories,
            @JsonProperty("retention_days") @Positive Integer retentionDays) {
    }
}
