package com.yilan.memory.adapter.grpc;

import com.yilan.memory.application.event.SubmitMemoryEventsUseCase;
import com.yilan.memory.application.event.SubmitMemoryEventsUseCase.InteractionEventCommand;
import com.yilan.memory.application.migration.AuthorityMode;
import com.yilan.memory.application.migration.AuthorityModeService;
import com.yilan.memory.observability.MemoryMetrics;
import com.yilan.memory.contract.v1.EventReceipt;
import com.yilan.memory.contract.v1.MemoryEventEnvelope;
import com.yilan.memory.contract.v1.MemoryEventServiceGrpc;
import com.yilan.memory.contract.v1.SubmitMemoryEventsRequest;
import com.yilan.memory.contract.v1.SubmitMemoryEventsResponse;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.identity.LearnerIdentity;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * v1 ingress adapter. Signed metadata is the sole identity input; CloudEvents
 * subject is only compared as an untrusted consistency claim.
 */
public class MemoryEventGrpcService extends MemoryEventServiceGrpc.MemoryEventServiceImplBase {

    private static final String TRANSPORT_SCHEMA_VERSION = "v1";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final SubmitMemoryEventsUseCase useCase;
    private final IdentityResolver identityResolver;
    private final AuthorityModeService authorityModes;
    private final MemoryMetrics metrics;

    @Autowired
    public MemoryEventGrpcService(
            SubmitMemoryEventsUseCase useCase,
            AuthorityModeService authorityModes,
            MemoryMetrics metrics) {
        this(useCase, SignedSessionInterceptor::requireIdentity, authorityModes, metrics);
    }

    /** Direct construction is fail-closed; Spring injects durable authority state above. */
    public MemoryEventGrpcService(SubmitMemoryEventsUseCase useCase) {
        this(useCase, SignedSessionInterceptor::requireIdentity, AuthorityModeService.disabled(), MemoryMetrics.noop());
    }

    MemoryEventGrpcService(SubmitMemoryEventsUseCase useCase, IdentityResolver identityResolver) {
        this(useCase, identityResolver, AuthorityModeService.disabled(), MemoryMetrics.noop());
    }

    MemoryEventGrpcService(
            SubmitMemoryEventsUseCase useCase,
            IdentityResolver identityResolver,
            AuthorityModeService authorityModes) {
        this(useCase, identityResolver, authorityModes, MemoryMetrics.noop());
    }

    MemoryEventGrpcService(
            SubmitMemoryEventsUseCase useCase,
            IdentityResolver identityResolver,
            AuthorityModeService authorityModes,
            MemoryMetrics metrics) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.authorityModes = Objects.requireNonNull(authorityModes, "authorityModes");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void submitMemoryEvents(
            SubmitMemoryEventsRequest request,
            StreamObserver<SubmitMemoryEventsResponse> responseObserver) {
        String observationStatus = "unknown";
        String observationDiagnostic = "none";
        try {
            var identity = identityResolver.require(Context.current());
            if (authorityModes.state().mode() != AuthorityMode.REMOTE) {
                observationStatus = "unavailable";
                observationDiagnostic = "unavailable";
                throw Status.UNAVAILABLE.withDescription("memory event authority unavailable").asRuntimeException();
            }
            if (!TRANSPORT_SCHEMA_VERSION.equals(request.getSchemaVersion())) {
                responseObserver.onNext(unsupportedSchemaResponse(request));
                responseObserver.onCompleted();
                observationStatus = "rejected";
                observationDiagnostic = "validation_failure";
                return;
            }
            if (!isSafeRequestId(request.getRequestId())) {
                responseObserver.onNext(invalidBatchResponse(request));
                responseObserver.onCompleted();
                observationStatus = "invalid";
                observationDiagnostic = "validation_failure";
                return;
            }
            String requestTraceId;
            var commands = new ArrayList<InteractionEventCommand>(request.getEventsCount());
            try {
                requestTraceId = boundedTraceId(request.getTraceparent());
                for (var envelope : request.getEventsList()) {
                    commands.add(toCommand(identity, requestTraceId, envelope));
                }
            } catch (IllegalArgumentException validationError) {
                responseObserver.onNext(invalidBatchResponse(request));
                responseObserver.onCompleted();
                observationStatus = "invalid";
                observationDiagnostic = "validation_failure";
                return;
            }
            try {
                var receipts = useCase.submit(identity, List.copyOf(commands)).stream()
                        .map(MemoryEventGrpcService::toProto)
                        .toList();
                responseObserver.onNext(SubmitMemoryEventsResponse.newBuilder()
                        .setRequestId(safeRequestId(request.getRequestId()))
                        .setSchemaVersion(TRANSPORT_SCHEMA_VERSION)
                        .addAllReceipts(receipts)
                        .setTraceId(requestTraceId)
                        .build());
                observationStatus = "accepted";
            } catch (SubmitMemoryEventsUseCase.BatchRejectedException rejection) {
                responseObserver.onNext(batchRejectedResponse(request, requestTraceId, rejection));
                observationStatus = "rejected";
                observationDiagnostic = "validation_failure";
            }
            responseObserver.onCompleted();
        } catch (StatusRuntimeException error) {
            observationStatus = "unavailable";
            observationDiagnostic = "unavailable";
            responseObserver.onError(error);
        } catch (RuntimeException error) {
            observationStatus = "unavailable";
            observationDiagnostic = "unavailable";
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("memory event authority unavailable")
                    .asRuntimeException());
        } finally {
            metrics.record("submit", observationStatus, "java.grpc", "grpc", observationDiagnostic,
                    request.getTraceparent(), 0L);
        }
    }

    private static SubmitMemoryEventsResponse unsupportedSchemaResponse(SubmitMemoryEventsRequest request) {
        var response = SubmitMemoryEventsResponse.newBuilder()
                .setRequestId(safeRequestId(request.getRequestId()))
                .setSchemaVersion(TRANSPORT_SCHEMA_VERSION)
                .setTraceId(safeBoundedTraceId(request.getTraceparent()));
        if (request.getEventsCount() == 0) {
            response.addReceipts(unsupportedSchemaReceipt(""));
        }
        for (var envelope : request.getEventsList()) {
            response.addReceipts(unsupportedSchemaReceipt(safeEventId(envelope.getId())));
        }
        return response.build();
    }

    private static EventReceipt unsupportedSchemaReceipt(String eventId) {
        return EventReceipt.newBuilder()
                .setEventId(eventId)
                .setResult(EventReceipt.Result.REJECTED)
                .setReasonCode("UNSUPPORTED_SCHEMA")
                .build();
    }

    private static SubmitMemoryEventsResponse invalidBatchResponse(SubmitMemoryEventsRequest request) {
        var reasonCode = request.getEventsCount() == 1 ? "INVALID_EVENT" : "INVALID_BATCH";
        var response = SubmitMemoryEventsResponse.newBuilder()
                .setRequestId(safeRequestId(request.getRequestId()))
                .setSchemaVersion(TRANSPORT_SCHEMA_VERSION)
                .setTraceId(safeBoundedTraceId(request.getTraceparent()));
        for (var envelope : request.getEventsList()) {
            response.addReceipts(EventReceipt.newBuilder()
                    .setEventId(safeEventId(envelope.getId()))
                    .setResult(EventReceipt.Result.REJECTED)
                    .setReasonCode(reasonCode));
        }
        return response.build();
    }

    private static SubmitMemoryEventsResponse batchRejectedResponse(
            SubmitMemoryEventsRequest request,
            String requestTraceId,
            SubmitMemoryEventsUseCase.BatchRejectedException rejection) {
        var response = SubmitMemoryEventsResponse.newBuilder()
                .setRequestId(safeRequestId(request.getRequestId()))
                .setSchemaVersion(TRANSPORT_SCHEMA_VERSION)
                .setTraceId(requestTraceId);
        var reasonCode = request.getEventsCount() == 1 ? rejection.reasonCode() : "BATCH_REJECTED";
        for (var envelope : request.getEventsList()) {
            response.addReceipts(EventReceipt.newBuilder()
                    .setEventId(safeEventId(envelope.getId()))
                    .setResult(EventReceipt.Result.REJECTED)
                    .setReasonCode(reasonCode));
        }
        return response.build();
    }

    private static InteractionEventCommand toCommand(
            LearnerIdentity identity,
            String requestTraceId,
            MemoryEventEnvelope envelope) {
        if (!envelope.hasConsentRevision()) {
            throw new IllegalArgumentException("consentRevision");
        }
        if (!"1.0".equals(envelope.getSpecversion())) {
            throw new IllegalArgumentException("specversion");
        }
        requireNonBlankBounded(envelope.getSource(), "source", 512);
        requireNonBlankBounded(envelope.getSubject(), "subject", 128);
        requireNonBlankBounded(envelope.getDataschema(), "dataschema", 512);
        requireNonBlankBounded(envelope.getDatacontenttype(), "datacontenttype", 128);
        var category = MemoryCategory.valueOf(envelope.getType());
        var traceId = envelope.getTraceparent().isBlank()
                ? requestTraceId
                : boundedTraceId(envelope.getTraceparent());
        return new InteractionEventCommand(
                UUID.fromString(envelope.getId()),
                envelope.getEventSchemaVersion(),
                envelope.getType(),
                category,
                sourceKind(envelope),
                envelope.getSubject(),
                envelope.getConsentRevision(),
                parseOccurredAt(envelope.getTime()),
                privacyLevel(envelope),
                envelope.getData().toByteArray(),
                traceId);
    }

    private static Instant parseOccurredAt(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("time", error);
        }
    }

    private static SourceKind sourceKind(MemoryEventEnvelope envelope) {
        return switch (envelope.getSourceKind()) {
            case SOURCE_KIND_UNSPECIFIED, GENERAL -> SourceKind.GENERAL;
            case EXPLICIT_DECLARATION -> SourceKind.EXPLICIT_DECLARATION;
            case EPISODE -> SourceKind.EPISODE;
            case SCORED_ASSESSMENT -> SourceKind.SCORED_ASSESSMENT;
            case UNRECOGNIZED -> throw new IllegalArgumentException("sourceKind");
        };
    }

    private static PrivacyLevel privacyLevel(MemoryEventEnvelope envelope) {
        return switch (envelope.getPrivacyLevel()) {
            case LOW -> PrivacyLevel.STANDARD;
            case STANDARD -> PrivacyLevel.SENSITIVE;
            case HIGH -> PrivacyLevel.HIGH;
            case PRIVACY_LEVEL_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("privacyLevel");
        };
    }

    private static EventReceipt toProto(SubmitMemoryEventsUseCase.EventReceipt receipt) {
        var builder = EventReceipt.newBuilder().setEventId(receipt.eventId().toString());
        return switch (receipt.result()) {
            case ACCEPTED -> builder.setResult(EventReceipt.Result.ACCEPTED).build();
            case DUPLICATE -> builder.setResult(EventReceipt.Result.DUPLICATE).build();
            case REJECTED -> builder.setResult(EventReceipt.Result.REJECTED)
                    .setReasonCode(receipt.reasonCode())
                    .build();
        };
    }

    private static String boundedTraceId(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("traceId");
        }
        return value;
    }

    private static String safeBoundedTraceId(String value) {
        return value != null && !value.isBlank() && value.length() <= 64 ? value : "";
    }

    private static String safeEventId(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return "";
        }
    }

    private static boolean isSafeRequestId(String value) {
        return value != null && SAFE_REQUEST_ID.matcher(value).matches();
    }

    private static String safeRequestId(String value) {
        return isSafeRequestId(value) ? value : "";
    }

    private static void requireNonBlankBounded(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field);
        }
    }

    @FunctionalInterface
    interface IdentityResolver {
        LearnerIdentity require(Context context);
    }
}
