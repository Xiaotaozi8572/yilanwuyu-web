package com.yilan.memory.adapter.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yilan.memory.application.management.ConfirmMemoryService;
import com.yilan.memory.application.management.CorrectMemoryService;
import com.yilan.memory.application.management.DisableMemoryService;
import com.yilan.memory.application.management.GetMemoryExplanationService;
import com.yilan.memory.application.management.ListMemoriesService;
import com.yilan.memory.application.management.MemoryManagementRepository;
import com.yilan.memory.application.privacy.DeletionReceipt;
import com.yilan.memory.application.privacy.ForgetService;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.security.AuthenticatedSubject;
import com.yilan.memory.security.MemoryRole;
import com.yilan.memory.security.SubjectBindingFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Implements only the frozen subject-bound management API routes. */
@RestController
@RequestMapping(path = "/v1/me/memories", produces = "application/json")
public final class MemoryManagementController {

    private final ListMemoriesService listService;
    private final GetMemoryExplanationService detailService;
    private final ConfirmMemoryService confirmService;
    private final CorrectMemoryService correctService;
    private final DisableMemoryService disableService;
    private final ForgetService forgetService;

    @Autowired
    public MemoryManagementController(
            ListMemoriesService listService,
            GetMemoryExplanationService detailService,
            ConfirmMemoryService confirmService,
            CorrectMemoryService correctService,
            DisableMemoryService disableService,
            ForgetService forgetService) {
        this.listService = listService;
        this.detailService = detailService;
        this.confirmService = confirmService;
        this.correctService = correctService;
        this.disableService = disableService;
        this.forgetService = forgetService;
    }

    /** Direct-controller compatibility for existing management-only tests. */
    public MemoryManagementController(
            ListMemoriesService listService, GetMemoryExplanationService detailService,
            ConfirmMemoryService confirmService, CorrectMemoryService correctService,
            DisableMemoryService disableService) {
        this(listService, detailService, confirmService, correctService, disableService, null);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ForgetReceiptResponse forgetAll(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var subject = requireFreshLearner(request);
        return ForgetReceiptResponse.from(requireForgetService().forgetAll(subject.subjectHash(), idempotencyKey));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ForgetReceiptResponse forgetOne(
            HttpServletRequest request,
            @PathVariable("id") UUID memoryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var subject = requireFreshLearner(request);
        return ForgetReceiptResponse.from(requireForgetService().forgetOne(subject.subjectHash(), memoryId, idempotencyKey));
    }

    @GetMapping
    public MemoryPageResponse list(
            HttpServletRequest request,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(value = "effective_after", required = false) Instant effectiveAfter) {
        var page = listService.list(
                requireLearner(request).subjectHash(), cursor, validatedType(type), status, effectiveAfter);
        return new MemoryPageResponse(page.items().stream().map(MemorySummaryResponse::from).toList(), page.nextCursor());
    }

    @GetMapping("/{id}")
    public MemoryDetailResponse detail(HttpServletRequest request, @PathVariable("id") UUID memoryId) {
        var subject = requireLearner(request);
        return MemoryDetailResponse.from(detailService.get(subject.subjectHash(), memoryId, subject.freshAuthentication()));
    }

    @PostMapping("/{id}:confirm")
    public MemoryDetailResponse confirm(
            HttpServletRequest request,
            @PathVariable("id") UUID memoryId,
            @RequestHeader("If-Match") String expectedVersion,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var subject = requireLearner(request);
        requireMutableDetail(subject, memoryId);
        var detail = confirmService.confirm(subject.subjectHash(), memoryId, expectedVersion, idempotencyKey).detail();
        return MemoryDetailResponse.from(detail);
    }

    @PostMapping(path = "/{id}:correct", consumes = "application/json")
    public MemoryDetailResponse correct(
            HttpServletRequest request,
            @PathVariable("id") UUID memoryId,
            @RequestHeader("If-Match") String expectedVersion,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CorrectionRequest correction) {
        var subject = requireLearner(request);
        requireMutableDetail(subject, memoryId);
        var detail = correctService.correct(
                subject.subjectHash(),
                memoryId,
                expectedVersion,
                idempotencyKey,
                correction.correctedValue(),
                correction.reason()).detail();
        return MemoryDetailResponse.from(detail);
    }

    @PostMapping("/{id}:disable")
    public MemoryDetailResponse disable(
            HttpServletRequest request,
            @PathVariable("id") UUID memoryId,
            @RequestHeader("If-Match") String expectedVersion,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var subject = requireLearner(request);
        requireMutableDetail(subject, memoryId);
        var detail = disableService.disable(subject.subjectHash(), memoryId, expectedVersion, idempotencyKey).detail();
        return MemoryDetailResponse.from(detail);
    }

    private static AuthenticatedSubject requireLearner(HttpServletRequest request) {
        var subject = SubjectBindingFilter.requireBoundSubject(request);
        if (!subject.hasRole(MemoryRole.LEARNER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "learner role required");
        }
        return subject;
    }

    private static AuthenticatedSubject requireFreshLearner(HttpServletRequest request) {
        var subject = requireLearner(request);
        if (!subject.freshAuthentication()) {
            throw new MemoryManagementRepository.FreshAuthenticationRequiredException();
        }
        return subject;
    }

    private ForgetService requireForgetService() {
        if (forgetService == null) throw new IllegalStateException("forget service unavailable");
        return forgetService;
    }

    private void requireMutableDetail(AuthenticatedSubject subject, UUID memoryId) {
        detailService.get(subject.subjectHash(), memoryId, subject.freshAuthentication());
    }

    private static String validatedType(String type) {
        if (type == null || type.isBlank()) {
            return type;
        }
        try {
            MemoryCategory.valueOf(type);
            return type;
        } catch (IllegalArgumentException exception) {
            throw new InvalidCategoryException();
        }
    }

    static final class InvalidCategoryException extends RuntimeException {
        InvalidCategoryException() {
            super("invalid memory category");
        }
    }

    public record CorrectionRequest(
            @JsonProperty("corrected_value") @NotNull Map<String, Object> correctedValue,
            String reason) {
    }

    private record MemoryPageResponse(
            List<MemorySummaryResponse> items,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    private record MemorySummaryResponse(
            UUID id,
            String version,
            String type,
            String status,
            @JsonProperty("confirmation_status") String confirmationStatus,
            @JsonProperty("display_value") Map<String, Object> displayValue,
            @JsonProperty("confidence_band") String confidenceBand,
            @JsonProperty("effective_at") Instant effectiveAt,
            @JsonProperty("expires_at") Instant expiresAt) {

        private static MemorySummaryResponse from(MemoryManagementRepository.MemorySummary summary) {
            return new MemorySummaryResponse(
                    summary.id(), summary.version(), summary.type(), summary.status(), summary.confirmationStatus(),
                    summary.displayValue(), summary.confidenceBand(), summary.effectiveAt(), summary.expiresAt());
        }
    }

    private record MemoryDetailResponse(
            UUID id,
            String version,
            String type,
            String status,
            @JsonProperty("confirmation_status") String confirmationStatus,
            @JsonProperty("display_value") Map<String, Object> displayValue,
            @JsonProperty("confidence_band") String confidenceBand,
            @JsonProperty("effective_at") Instant effectiveAt,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("use_class") String useClass,
            @JsonProperty("privacy_class") String privacyClass,
            @JsonProperty("source_summary") List<String> sourceSummary,
            String reason,
            @JsonProperty("usage_history") List<UsageHistoryResponse> usageHistory) {

        private static MemoryDetailResponse from(MemoryManagementRepository.MemoryDetail detail) {
            return new MemoryDetailResponse(
                    detail.id(), detail.version(), detail.type(), detail.status(), detail.confirmationStatus(),
                    detail.displayValue(), detail.confidenceBand(), detail.effectiveAt(), detail.expiresAt(),
                    detail.useClass(), detail.privacyClass(), detail.sourceSummary(), detail.reason(),
                    detail.usageHistory().stream().map(UsageHistoryResponse::from).toList());
        }
    }

    private record UsageHistoryResponse(
            @JsonProperty("occurred_at") Instant occurredAt,
            @JsonProperty("request_ref") String requestRef,
            @JsonProperty("task_type") String taskType,
            String outcome) {

        private static UsageHistoryResponse from(MemoryManagementRepository.UsageHistory history) {
            return new UsageHistoryResponse(history.occurredAt(), history.requestRef(), history.taskType(), history.outcome());
        }
    }

    private record ForgetReceiptResponse(
            @JsonProperty("request_id") UUID requestId,
            String state,
            @JsonProperty("requested_at") Instant requestedAt) {
        private static ForgetReceiptResponse from(DeletionReceipt receipt) {
            return new ForgetReceiptResponse(receipt.requestId(), receipt.state(), receipt.requestedAt());
        }
    }
}
