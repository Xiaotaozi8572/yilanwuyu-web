package com.yilan.memory.adapter.grpc;

import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.application.event.SubmitMemoryEventsUseCase;
import com.yilan.memory.application.migration.AuthorityMode;
import com.yilan.memory.application.migration.AuthorityModeService;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.contract.v1.EventReceipt;
import com.yilan.memory.contract.v1.MemoryEventEnvelope;
import com.yilan.memory.contract.v1.PrivacyLevel;
import com.yilan.memory.contract.v1.SourceKind;
import com.yilan.memory.contract.v1.SubmitMemoryEventsRequest;
import com.yilan.memory.contract.v1.SubmitMemoryEventsResponse;
import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent;
import com.yilan.memory.domain.identity.LearnerIdentity;
import com.yilan.memory.security.SubjectAuthenticationConverter;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryEventGrpcServiceTest {

    private static final String SUBJECT_HMAC_KEY = "test-only-subject-hmac-key-that-is-long-enough";
    private static final String IMMUTABLE_SUBJECT = "grpc-event-learner-a";
    private static final SubjectAuthenticationConverter SUBJECT_CONVERTER =
            new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY);
    private static final LearnerIdentity SIGNED_IDENTITY = new LearnerIdentity(
            SUBJECT_CONVERTER.fromVerifiedClaims(IMMUTABLE_SUBJECT, List.of("LEARNER")).subjectHash(),
            "session-1", 4);
    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

    @Test
    void signedContextWinsAndMismatchedBodySubjectIsRejectedWithoutAWrite() {
        var events = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        var service = remoteService(useCase(events, event -> { }));
        var observer = new RecordingObserver();

        invokeThroughSignedInterceptor(service, request("subject-body-b"), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceipts(0).getResult()).isEqualTo(EventReceipt.Result.REJECTED);
        assertThat(observer.response.getReceipts(0).getReasonCode()).isEqualTo("CROSS_SUBJECT");
        assertThat(events.inserted).isEmpty();
    }

    @Test
    void durable_non_remote_mode_returns_unavailable_before_the_event_use_case() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = new MemoryEventGrpcService(useCase, ignored -> SIGNED_IDENTITY, authorityService(AuthorityMode.SHADOW));
        var observer = new RecordingObserver();

        service.submitMemoryEvents(request(SIGNED_IDENTITY.subjectHash()), observer);

        assertThat(observer.error).isInstanceOf(io.grpc.StatusRuntimeException.class);
        assertThat(((io.grpc.StatusRuntimeException) observer.error).getStatus().getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
        verifyNoInteractions(useCase);
    }

    @Test
    void unconfigured_public_constructor_gates_signed_event_ingress_before_the_use_case() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = new MemoryEventGrpcService(useCase);
        var observer = new RecordingObserver();

        invokeThroughSignedInterceptor(service, request(SIGNED_IDENTITY.subjectHash()), observer);

        assertThat(observer.error).isInstanceOf(io.grpc.StatusRuntimeException.class);
        assertThat(((io.grpc.StatusRuntimeException) observer.error).getStatus().getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
        verifyNoInteractions(useCase);
    }

    @Test
    void verifiedAdminAndAuditorCannotSubmitLearnerEventsThroughSignedInterceptor() {
        for (var role : List.of("ADMIN", "AUDITOR")) {
            var useCase = mock(SubmitMemoryEventsUseCase.class);
            var service = new MemoryEventGrpcService(useCase);
            var observer = new RecordingObserver();

            var call = invokeThroughSignedInterceptor(
                    service, request(SIGNED_IDENTITY.subjectHash()), observer, List.of(role));

            assertThat(observer.response).isNull();
            assertThat(observer.error).isNull();
            verifyNoInteractions(useCase);
            var status = org.mockito.ArgumentCaptor.forClass(Status.class);
            verify(call).close(status.capture(), org.mockito.ArgumentMatchers.any(Metadata.class));
            assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        }
    }

    @Test
    void databaseFailureMapsToUnavailableRatherThanRejectedReceipt() {
        var events = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED) {
            @Override
            public SubmitMemoryEventsUseCase.InsertOutcome insertIfAbsent(
                    LearnerIdentity identity,
                    InteractionEvent event) {
                throw new DataAccessResourceFailureException("local test failure");
            }
        };
        var service = remoteService(useCase(events, event -> { }));
        var observer = new RecordingObserver();

        invokeThroughSignedInterceptor(service, request(SIGNED_IDENTITY.subjectHash()), observer);

        assertThat(observer.response).isNull();
        assertThat(observer.error).isInstanceOf(io.grpc.StatusRuntimeException.class);
        assertThat(((io.grpc.StatusRuntimeException) observer.error).getStatus().getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void twoValidEnvelopesAreSubmittedTogetherInOneUseCaseCall() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        var first = request(SIGNED_IDENTITY.subjectHash()).getEvents(0);
        var second = first.toBuilder().setId("44444444-4444-4444-4444-444444444444").build();
        when(useCase.submit(eq(SIGNED_IDENTITY), anyList())).thenReturn(List.of(
                SubmitMemoryEventsUseCase.EventReceipt.accepted(UUID.fromString(first.getId())),
                SubmitMemoryEventsUseCase.EventReceipt.accepted(UUID.fromString(second.getId()))));

        service.submitMemoryEvents(requestWithEvents(first, second), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceiptsList())
                .extracting(EventReceipt::getResult)
                .containsExactly(EventReceipt.Result.ACCEPTED, EventReceipt.Result.ACCEPTED);
        assertThat(observer.response.getRequestId()).isEqualTo("request-event-1");
        @SuppressWarnings("unchecked")
        var commands = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(useCase, times(1)).submit(eq(SIGNED_IDENTITY), commands.capture());
        assertThat(commands.getValue()).hasSize(2);
    }

    @Test
    void invalidRequestIdsAreRejectedBeforeTheUseCaseAndNeverReflected() {
        for (var invalidRequestId : List.of("", "request\ncontrol", "x".repeat(129))) {
            var singleUseCase = mock(SubmitMemoryEventsUseCase.class);
            var singleObserver = new RecordingObserver();
            remoteService(singleUseCase, ignored -> SIGNED_IDENTITY)
                    .submitMemoryEvents(request(SIGNED_IDENTITY.subjectHash()).toBuilder()
                            .setRequestId(invalidRequestId).build(), singleObserver);

            assertThat(singleObserver.error).isNull();
            assertThat(singleObserver.response.getRequestId()).isEmpty();
            assertThat(singleObserver.response.getReceiptsList()).singleElement().satisfies(receipt -> {
                assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
                assertThat(receipt.getReasonCode()).isEqualTo("INVALID_EVENT");
            });
            verifyNoInteractions(singleUseCase);

            var multipleUseCase = mock(SubmitMemoryEventsUseCase.class);
            var multipleObserver = new RecordingObserver();
            var first = request(SIGNED_IDENTITY.subjectHash()).getEvents(0);
            var second = first.toBuilder().setId("44444444-4444-4444-4444-444444444444").build();
            remoteService(multipleUseCase, ignored -> SIGNED_IDENTITY)
                    .submitMemoryEvents(requestWithEvents(first, second).toBuilder()
                            .setRequestId(invalidRequestId).build(), multipleObserver);

            assertThat(multipleObserver.error).isNull();
            assertThat(multipleObserver.response.getRequestId()).isEmpty();
            assertThat(multipleObserver.response.getReceiptsList()).hasSize(2).allSatisfy(receipt -> {
                assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
                assertThat(receipt.getReasonCode()).isEqualTo("INVALID_BATCH");
            });
            verifyNoInteractions(multipleUseCase);
        }
    }

    @Test
    void declaredConsentRevisionReachesTheCommandAndAllowsSubmission() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        var event = request(SIGNED_IDENTITY.subjectHash()).getEvents(0);
        when(useCase.submit(eq(SIGNED_IDENTITY), anyList())).thenReturn(List.of(
                SubmitMemoryEventsUseCase.EventReceipt.accepted(UUID.fromString(event.getId()))));

        service.submitMemoryEvents(requestWithEvent(event), observer);

        @SuppressWarnings("unchecked")
        var commands = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(useCase).submit(eq(SIGNED_IDENTITY), commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command ->
                assertThat(((SubmitMemoryEventsUseCase.InteractionEventCommand) command)
                        .declaredConsentRevision()).isEqualTo(SIGNED_IDENTITY.consentRevision()));
        assertThat(observer.response.getReceipts(0).getResult()).isEqualTo(EventReceipt.Result.ACCEPTED);
    }

    @Test
    void missingConsentRevisionRejectsSingleAndMultipleEventsBeforeTheUseCase() {
        for (var eventCount : List.of(1, 2)) {
            var useCase = mock(SubmitMemoryEventsUseCase.class);
            var observer = new RecordingObserver();
            var first = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                    .clearConsentRevision().build();
            var invalid = eventCount == 1 ? requestWithEvent(first) : requestWithEvents(first,
                    first.toBuilder().setId("44444444-4444-4444-4444-444444444444").build());

            remoteService(useCase, ignored -> SIGNED_IDENTITY).submitMemoryEvents(invalid, observer);

            assertThat(observer.error).isNull();
            assertThat(observer.response.getReceiptsList()).hasSize(eventCount).allSatisfy(receipt -> {
                assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
                assertThat(receipt.getReasonCode()).isEqualTo(eventCount == 1 ? "INVALID_EVENT" : "INVALID_BATCH");
            });
            verifyNoInteractions(useCase);
        }
    }

    @Test
    void staleDeclaredConsentRevisionIsRejectedBeforeAnyWrite() {
        var events = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        var service = remoteService(useCase(events, event -> { }));
        var observer = new RecordingObserver();
        var stale = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setConsentRevision(SIGNED_IDENTITY.consentRevision() - 1).build();

        invokeThroughSignedInterceptor(service, requestWithEvent(stale), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceiptsList()).singleElement().satisfies(receipt -> {
            assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
            assertThat(receipt.getReasonCode()).isEqualTo("STALE_EVENT_CONSENT");
        });
        assertThat(events.inserted).isEmpty();
    }

    @Test
    void aLaterCrossSubjectOrStaleEventRejectsTheEntireBatchBeforeAnyWrite() {
        for (var invalidSecond : List.of(
                request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                        .setId("44444444-4444-4444-4444-444444444444").setSubject("subject-body-b").build(),
                request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                        .setId("44444444-4444-4444-4444-444444444444")
                        .setConsentRevision(SIGNED_IDENTITY.consentRevision() - 1).build())) {
            var events = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
            var observer = new RecordingObserver();
            var first = request(SIGNED_IDENTITY.subjectHash()).getEvents(0);

            invokeThroughSignedInterceptor(remoteService(useCase(events, event -> { })),
                    requestWithEvents(first, invalidSecond), observer);

            assertThat(observer.error).isNull();
            assertThat(observer.response.getReceiptsList()).hasSize(2).allSatisfy(receipt -> {
                assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
                assertThat(receipt.getReasonCode()).isEqualTo("BATCH_REJECTED");
            });
            assertThat(events.inserted).isEmpty();
        }
    }

    @Test
    void batchRejectedExceptionMapsToFiniteSingleAndBatchRejections() {
        for (var eventCount : List.of(1, 2)) {
            var useCase = mock(SubmitMemoryEventsUseCase.class);
            var observer = new RecordingObserver();
            var first = request(SIGNED_IDENTITY.subjectHash()).getEvents(0);
            var submitted = eventCount == 1 ? requestWithEvent(first) : requestWithEvents(first,
                    first.toBuilder().setId("44444444-4444-4444-4444-444444444444").build());
            when(useCase.submit(eq(SIGNED_IDENTITY), anyList())).thenThrow(
                    new SubmitMemoryEventsUseCase.BatchRejectedException(UUID.fromString(first.getId()), "CROSS_SUBJECT"));

            remoteService(useCase, ignored -> SIGNED_IDENTITY).submitMemoryEvents(submitted, observer);

            assertThat(observer.error).isNull();
            assertThat(observer.response.getReceiptsList()).hasSize(eventCount).allSatisfy(receipt -> {
                assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
                assertThat(receipt.getReasonCode()).isEqualTo(eventCount == 1 ? "CROSS_SUBJECT" : "BATCH_REJECTED");
            });
        }
    }

    @Test
    void invalidSecondEnvelopeRejectsTheWholeBatchBeforeCallingTheUseCase() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        var first = request(SIGNED_IDENTITY.subjectHash()).getEvents(0);
        var invalidSecond = first.toBuilder()
                .setId("44444444-4444-4444-4444-444444444444")
                .clearSource()
                .build();

        service.submitMemoryEvents(requestWithEvents(first, invalidSecond), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceiptsList()).hasSize(2).allSatisfy(receipt -> {
            assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
            assertThat(receipt.getReasonCode()).isEqualTo("INVALID_BATCH");
        });
        verifyNoInteractions(useCase);
    }

    @Test
    void invalidRequestTraceIsRejectedBeforeTheUseCaseEvenWhenTheEnvelopeTraceIsValid() {
        for (var invalidRequestTrace : List.of("", "x".repeat(65))) {
            var useCase = mock(SubmitMemoryEventsUseCase.class);
            var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
            var observer = new RecordingObserver();
            var event = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                    .setTraceparent("valid-envelope-trace")
                    .build();
            var invalidRequest = requestWithEvent(event).toBuilder()
                    .setTraceparent(invalidRequestTrace)
                    .build();
            when(useCase.submit(eq(SIGNED_IDENTITY), anyList())).thenReturn(List.of(
                    SubmitMemoryEventsUseCase.EventReceipt.accepted(UUID.fromString(event.getId()))));

            service.submitMemoryEvents(invalidRequest, observer);

            assertThat(observer.error).isNull();
            assertThat(observer.response.getReceiptsList()).singleElement().satisfies(receipt -> {
                assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
                assertThat(receipt.getReasonCode()).isEqualTo("INVALID_EVENT");
            });
            verifyNoInteractions(useCase);
        }
    }

    @Test
    void invalidRequestTraceRejectsAMultiEventBatchBeforeTheUseCase() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        var first = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setTraceparent("valid-envelope-trace-1")
                .build();
        var second = first.toBuilder()
                .setId("44444444-4444-4444-4444-444444444444")
                .setTraceparent("valid-envelope-trace-2")
                .build();
        var invalidRequest = requestWithEvents(first, second).toBuilder()
                .setTraceparent("x".repeat(65))
                .build();
        when(useCase.submit(eq(SIGNED_IDENTITY), anyList())).thenReturn(List.of(
                SubmitMemoryEventsUseCase.EventReceipt.accepted(UUID.fromString(first.getId())),
                SubmitMemoryEventsUseCase.EventReceipt.accepted(UUID.fromString(second.getId()))));

        service.submitMemoryEvents(invalidRequest, observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceiptsList()).hasSize(2).allSatisfy(receipt -> {
            assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
            assertThat(receipt.getReasonCode()).isEqualTo("INVALID_BATCH");
        });
        verifyNoInteractions(useCase);
    }

    @Test
    void invalidEnvelopeTimeIsRejectedBeforeTheUseCase() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        var invalid = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setTime("not-an-iso-8601-instant")
                .build();

        service.submitMemoryEvents(requestWithEvent(invalid), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceiptsList()).singleElement().satisfies(receipt -> {
            assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
            assertThat(receipt.getReasonCode()).isEqualTo("INVALID_EVENT");
        });
        verifyNoInteractions(useCase);
    }

    @Test
    void invalidSecondEnvelopeTimeRejectsTheWholeBatchBeforeTheUseCase() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        var first = request(SIGNED_IDENTITY.subjectHash()).getEvents(0);
        var invalidSecond = first.toBuilder()
                .setId("44444444-4444-4444-4444-444444444444")
                .setTime("not-an-iso-8601-instant")
                .build();

        service.submitMemoryEvents(requestWithEvents(first, invalidSecond), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceiptsList()).hasSize(2).allSatisfy(receipt -> {
            assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
            assertThat(receipt.getReasonCode()).isEqualTo("INVALID_BATCH");
        });
        verifyNoInteractions(useCase);
    }

    @Test
    void illegalArgumentExceptionFromUseCaseIsUnavailableAndDoesNotLeakDetails() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        when(useCase.submit(eq(SIGNED_IDENTITY), anyList()))
                .thenThrow(new IllegalArgumentException("sensitive internal persistence detail"));

        service.submitMemoryEvents(request(SIGNED_IDENTITY.subjectHash()), observer);

        assertThat(observer.response).isNull();
        assertThat(observer.error).isInstanceOf(io.grpc.StatusRuntimeException.class);
        var status = ((io.grpc.StatusRuntimeException) observer.error).getStatus();
        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription()).isEqualTo("memory event authority unavailable");
        assertThat(observer.error.getMessage()).doesNotContain("sensitive internal persistence detail");
    }

    @Test
    void nonV1TransportSchemaIsRejectedWithV1ResponseWithoutCallingTheUseCase() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        var event = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setEventSchemaVersion("event-v2")
                .build();
        var nonV1Request = requestWithEvent(event).toBuilder()
                .setSchemaVersion("v2")
                .build();

        service.submitMemoryEvents(nonV1Request, observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getSchemaVersion()).isEqualTo("v1");
        assertThat(observer.response.getReceipts(0).getResult()).isEqualTo(EventReceipt.Result.REJECTED);
        assertThat(observer.response.getReceipts(0).getReasonCode()).isEqualTo("UNSUPPORTED_SCHEMA");
        verifyNoInteractions(useCase);
    }

    @Test
    void nonV1EmptyBatchStillReturnsAFiniteRejectedSchemaReceipt() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);
        var observer = new RecordingObserver();
        var nonV1EmptyRequest = request(SIGNED_IDENTITY.subjectHash()).toBuilder()
                .setSchemaVersion("v2")
                .clearEvents()
                .build();

        service.submitMemoryEvents(nonV1EmptyRequest, observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getSchemaVersion()).isEqualTo("v1");
        assertThat(observer.response.getReceiptsList()).singleElement().satisfies(receipt -> {
            assertThat(receipt.getEventId()).isEmpty();
            assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
            assertThat(receipt.getReasonCode()).isEqualTo("UNSUPPORTED_SCHEMA");
        });
        verifyNoInteractions(useCase);
    }

    @Test
    void v1TransportAcceptsAnIndependentlyVersionedEventSchema() {
        var events = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        var service = remoteService(useCase(events, event -> { }));
        var observer = new RecordingObserver();
        var event = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setEventSchemaVersion("event-v2")
                .build();

        invokeThroughSignedInterceptor(service, requestWithEvent(event), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getSchemaVersion()).isEqualTo("v1");
        assertThat(observer.response.getReceipts(0).getResult()).isEqualTo(EventReceipt.Result.ACCEPTED);
        assertThat(events.inserted).singleElement()
                .extracting(InteractionEvent::schemaVersion)
                .isEqualTo("event-v2");
    }

    @Test
    void missingRequiredCloudEventsAttributesAreRejectedWithoutAWrite() {
        var validEvent = request(SIGNED_IDENTITY.subjectHash()).getEvents(0);

        assertInvalidEvent(requestWithEvent(validEvent.toBuilder().clearSource().build()));
        assertInvalidEvent(requestWithEvent(validEvent.toBuilder().clearSubject().build()));
        assertInvalidEvent(requestWithEvent(validEvent.toBuilder().clearDataschema().build()));
        assertInvalidEvent(requestWithEvent(validEvent.toBuilder().clearDatacontenttype().build()));
    }

    @Test
    void schemaVersionContainingJsonSyntaxIsRejectedWithoutAWrite() {
        var unsafeVersion = "v\"1\\invalid";
        var unsafeEvent = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setEventSchemaVersion(unsafeVersion)
                .build();

        assertInvalidEvent(requestWithEvent(unsafeEvent));
    }

    @Test
    void sourceKindDefaultsToGeneralAndOnlyValidCategoryBoundValuesReachJavaAuthority() {
        var legacyEvents = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        var legacyService = remoteService(useCase(legacyEvents, event -> { }));
        var legacyObserver = new RecordingObserver();

        invokeThroughSignedInterceptor(legacyService, request(SIGNED_IDENTITY.subjectHash()), legacyObserver);

        assertThat(legacyObserver.response.getReceipts(0).getResult()).isEqualTo(EventReceipt.Result.ACCEPTED);
        assertThat(legacyEvents.inserted).singleElement()
                .extracting(InteractionEvent::sourceKind)
                .isEqualTo(InteractionEvent.SourceKind.GENERAL);

        var explicitEvents = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        var explicitService = remoteService(useCase(explicitEvents, event -> { }));
        var explicitObserver = new RecordingObserver();
        var explicit = requestWithEvent(request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setSourceKind(SourceKind.EXPLICIT_DECLARATION)
                .build());

        invokeThroughSignedInterceptor(explicitService, explicit, explicitObserver);

        assertThat(explicitObserver.response.getReceipts(0).getResult()).isEqualTo(EventReceipt.Result.ACCEPTED);
        assertThat(explicitEvents.inserted).singleElement()
                .extracting(InteractionEvent::sourceKind)
                .isEqualTo(InteractionEvent.SourceKind.EXPLICIT_DECLARATION);
        assertInvalidEvent(requestWithEvent(request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setSourceKind(SourceKind.SCORED_ASSESSMENT)
                .build()));
        assertInvalidEvent(requestWithEvent(request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setSourceKindValue(99)
                .build()));
    }

    @Test
    void missingOrUnrecognizedPrivacyRejectsBeforeTheUseCaseAndNeverWrites() {
        var useCase = mock(SubmitMemoryEventsUseCase.class);
        var service = remoteService(useCase, ignored -> SIGNED_IDENTITY);

        var missingObserver = new RecordingObserver();
        var missing = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .clearPrivacyLevel()
                .build();
        service.submitMemoryEvents(requestWithEvent(missing), missingObserver);

        var unknownObserver = new RecordingObserver();
        var unknown = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setPrivacyLevelValue(99)
                .build();
        service.submitMemoryEvents(requestWithEvent(unknown), unknownObserver);

        assertThat(missingObserver.error).isNull();
        assertThat(missingObserver.response.getReceiptsList()).singleElement().satisfies(receipt -> {
            assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
            assertThat(receipt.getReasonCode()).isEqualTo("INVALID_EVENT");
        });
        assertThat(unknownObserver.error).isNull();
        assertThat(unknownObserver.response.getReceiptsList()).singleElement().satisfies(receipt -> {
            assertThat(receipt.getResult()).isEqualTo(EventReceipt.Result.REJECTED);
            assertThat(receipt.getReasonCode()).isEqualTo("INVALID_EVENT");
        });
        verifyNoInteractions(useCase);
    }

    @Test
    void highPrivacyReachesTheAuthorityLedgerWithoutDowngradingToStandard() {
        var events = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        var service = remoteService(useCase(events, event -> { }));
        var observer = new RecordingObserver();
        var high = request(SIGNED_IDENTITY.subjectHash()).getEvents(0).toBuilder()
                .setPrivacyLevel(PrivacyLevel.HIGH)
                .build();

        invokeThroughSignedInterceptor(service, requestWithEvent(high), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceipts(0).getResult()).isEqualTo(EventReceipt.Result.ACCEPTED);
        assertThat(events.inserted).singleElement()
                .extracting(InteractionEvent::privacyLevel)
                .isEqualTo(InteractionEvent.PrivacyLevel.HIGH);
    }

    private static SubmitMemoryEventsUseCase useCase(
            SubmitMemoryEventsUseCase.InteractionEventRepository events,
            SubmitMemoryEventsUseCase.TransactionalOutboxRepository outbox) {
        var masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        var sourceEnvelopes = new InMemoryTestKeyProvider.InMemoryInteractionPayloadKeyEnvelopeStore();
        var protector = new PayloadProtector(new InMemoryTestKeyProvider(
                masterKey, new InMemoryTestKeyProvider.InMemoryEnvelopeStore(), sourceEnvelopes));
        return new SubmitMemoryEventsUseCase(events, outbox, subjectHash -> Optional.of(new ConsentPolicy(
                ConsentPolicy.SubjectStatus.ACTIVE,
                ConsentPolicy.PolicyStatus.ACTIVE,
                SIGNED_IDENTITY.consentRevision(),
                java.util.Set.of(MemoryCategory.PREFERENCE),
                Instant.EPOCH,
                Instant.parse("2100-01-01T00:00:00Z"))), protector, sourceEnvelopes);
    }

    private static AuthorityModeService authorityService(AuthorityMode mode) {
        return new AuthorityModeService(new AuthorityModeService.AuthorityModeRepository() {
            private AuthorityModeService.AuthorityState state = new AuthorityModeService.AuthorityState(mode, 0, 0);
            @Override public AuthorityModeService.AuthorityState read() { return state; }
            @Override public AuthorityModeService.AuthorityState compareAndSet(
                    AuthorityModeService.AuthorityState expected, AuthorityModeService.AuthorityState next) {
                if (!state.equals(expected)) throw new IllegalStateException("changed");
                state = next;
                return state;
            }
        }, "test-only-authority-hmac-key");
    }

    private static MemoryEventGrpcService remoteService(SubmitMemoryEventsUseCase useCase) {
        return remoteService(useCase, SignedSessionInterceptor::requireIdentity);
    }

    private static MemoryEventGrpcService remoteService(
            SubmitMemoryEventsUseCase useCase,
            MemoryEventGrpcService.IdentityResolver identityResolver) {
        return new MemoryEventGrpcService(useCase, identityResolver, authorityService(AuthorityMode.REMOTE));
    }

    private static SubmitMemoryEventsRequest request(String bodySubject) {
        return SubmitMemoryEventsRequest.newBuilder()
                .setRequestId("request-event-1")
                .setSchemaVersion("v1")
                .setTraceparent("trace-event-1")
                .addEvents(MemoryEventEnvelope.newBuilder()
                        .setSpecversion("1.0")
                        .setId("33333333-3333-3333-3333-333333333333")
                        .setSource("urn:yilan:memory:test")
                        .setType("PREFERENCE")
                        .setSubject(bodySubject)
                        .setTime("2026-07-19T12:00:00Z")
                        .setDataschema("urn:yilan:memory:v1")
                        .setDatacontenttype("application/octet-stream")
                        .setData(com.google.protobuf.ByteString.copyFromUtf8("opaque-event-body"))
                        .setEventSchemaVersion("v1")
                        .setConsentRevision(SIGNED_IDENTITY.consentRevision())
                        .setPrivacyLevel(PrivacyLevel.STANDARD))
                .build();
    }

    private static SubmitMemoryEventsRequest requestWithEvent(MemoryEventEnvelope event) {
        return request(SIGNED_IDENTITY.subjectHash()).toBuilder()
                .clearEvents()
                .addEvents(event)
                .build();
    }

    private static SubmitMemoryEventsRequest requestWithEvents(MemoryEventEnvelope... events) {
        return request(SIGNED_IDENTITY.subjectHash()).toBuilder()
                .clearEvents()
                .addAllEvents(List.of(events))
                .build();
    }

    private static void assertInvalidEvent(SubmitMemoryEventsRequest request) {
        var events = new RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome.INSERTED);
        var service = remoteService(useCase(events, event -> { }));
        var observer = new RecordingObserver();

        invokeThroughSignedInterceptor(service, request, observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getReceipts(0).getResult()).isEqualTo(EventReceipt.Result.REJECTED);
        assertThat(observer.response.getReceipts(0).getReasonCode()).isEqualTo("INVALID_EVENT");
        assertThat(events.inserted).isEmpty();
    }

    private static void invokeThroughSignedInterceptor(
            MemoryEventGrpcService service,
            SubmitMemoryEventsRequest request,
            StreamObserver<SubmitMemoryEventsResponse> observer) {
        invokeThroughSignedInterceptor(service, request, observer, List.of("LEARNER"));
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<SubmitMemoryEventsRequest, SubmitMemoryEventsResponse> invokeThroughSignedInterceptor(
            MemoryEventGrpcService service,
            SubmitMemoryEventsRequest request,
            StreamObserver<SubmitMemoryEventsResponse> observer,
            List<String> roles) {
        var interceptor = new SignedSessionInterceptor(token -> new SignedSessionInterceptor.VerifiedSession(
                IMMUTABLE_SUBJECT, SIGNED_IDENTITY.sessionId(), SIGNED_IDENTITY.consentRevision(), roles),
                SUBJECT_CONVERTER);
        ServerCall<SubmitMemoryEventsRequest, SubmitMemoryEventsResponse> call = mock(ServerCall.class);
        var headers = new Metadata();
        headers.put(SignedSessionInterceptor.AUTHORIZATION, "Bearer local-test-signed");
        ServerCallHandler<SubmitMemoryEventsRequest, SubmitMemoryEventsResponse> handler =
                new ServerCallHandler<>() {
                    @Override
                    public ServerCall.Listener<SubmitMemoryEventsRequest> startCall(
                            ServerCall<SubmitMemoryEventsRequest, SubmitMemoryEventsResponse> ignoredCall,
                            Metadata ignoredHeaders) {
                        service.submitMemoryEvents(request, observer);
                        return new ServerCall.Listener<>() {
                        };
                    }
                };

        interceptor.interceptCall(call, headers, handler);
        return call;
    }

    private static class RecordingEvents implements SubmitMemoryEventsUseCase.InteractionEventRepository {

        private final SubmitMemoryEventsUseCase.InsertOutcome outcome;
        private final java.util.ArrayList<InteractionEvent> inserted = new java.util.ArrayList<>();

        private RecordingEvents(SubmitMemoryEventsUseCase.InsertOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public SubmitMemoryEventsUseCase.InsertOutcome insertIfAbsent(LearnerIdentity identity, InteractionEvent event) {
            inserted.add(event);
            return outcome;
        }
    }

    private static final class RecordingObserver implements StreamObserver<SubmitMemoryEventsResponse> {

        private SubmitMemoryEventsResponse response;
        private Throwable error;

        @Override
        public void onNext(SubmitMemoryEventsResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {
        }
    }
}
