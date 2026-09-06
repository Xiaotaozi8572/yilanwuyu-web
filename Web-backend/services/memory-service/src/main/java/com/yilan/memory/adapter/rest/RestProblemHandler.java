package com.yilan.memory.adapter.rest;

import com.yilan.memory.application.consent.ConsentService;
import com.yilan.memory.application.management.MemoryManagementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

/** Emits stable, payload-free RFC 9457 problem details for management actions. */
@ControllerAdvice(assignableTypes = {MemoryManagementController.class, ConsentController.class})
public final class RestProblemHandler {

    @ExceptionHandler(MemoryManagementRepository.MemoryNotFoundException.class)
    ResponseEntity<ProblemDetail> memoryNotFound() {
        return problem(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND");
    }

    @ExceptionHandler({
            MemoryManagementRepository.StaleVersionException.class,
            ConsentService.StaleConsentVersionException.class
    })
    ResponseEntity<ProblemDetail> staleVersion() {
        return problem(HttpStatus.CONFLICT, "STALE_VERSION");
    }

    @ExceptionHandler({
            MemoryManagementRepository.IdempotencyConflictException.class,
            ConsentService.IdempotencyConflictException.class
    })
    ResponseEntity<ProblemDetail> idempotencyConflict() {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
    }

    @ExceptionHandler(MemoryManagementRepository.PolicyDisabledException.class)
    ResponseEntity<ProblemDetail> policyDisabled() {
        return problem(HttpStatus.CONFLICT, "POLICY_DISABLED");
    }

    @ExceptionHandler(MemoryManagementRepository.FreshAuthenticationRequiredException.class)
    ResponseEntity<ProblemDetail> freshAuthenticationRequired() {
        return problem(HttpStatus.FORBIDDEN, "FRESH_AUTH_REQUIRED");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ExceptionHandler(MemoryManagementController.InvalidCategoryException.class)
    ResponseEntity<ProblemDetail> invalidCategory() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY");
    }

    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class,
            BindException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> malformedRequest() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        var detail = ProblemDetail.forStatus(status);
        detail.setTitle(status.getReasonPhrase());
        detail.setInstance(URI.create("urn:memory:management-problem"));
        detail.setProperty("code", code);
        return ResponseEntity.status(status).body(detail);
    }
}
