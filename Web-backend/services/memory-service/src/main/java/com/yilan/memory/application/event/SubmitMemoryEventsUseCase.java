package com.yilan.memory.application.event;

import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.application.privacy.InteractionPayloadKeyEnvelopeStore;
import com.yilan.memory.domain.consent.ConsentPolicy.ConsentDecision;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.identity.LearnerIdentity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The only M1 event-write application boundary. A technical persistence error
 * escapes this use case so the transport can surface UNAVAILABLE rather than a
 * business rejection receipt.
 */
@Service
public class SubmitMemoryEventsUseCase {

    private final InteractionEventRepository events;
    private final TransactionalOutboxRepository outbox;
    private final ConsentQuery consentQuery;
    private final PayloadProtector payloadProtector;
    private final InteractionPayloadKeyEnvelopeStore sourceEnvelopes;

    @Autowired
    public SubmitMemoryEventsUseCase(
            InteractionEventRepository events,
            TransactionalOutboxRepository outbox,
            ConsentQuery.PolicyReader policyReader,
            PayloadProtector payloadProtector,
            InteractionPayloadKeyEnvelopeStore sourceEnvelopes) {
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.consentQuery = new ConsentQuery(Objects.requireNonNull(policyReader, "policyReader"));
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
        this.sourceEnvelopes = Objects.requireNonNull(sourceEnvelopes, "sourceEnvelopes");
    }

    /** Compatibility constructor for direct unit adapters that do not enable source protection. */
    public SubmitMemoryEventsUseCase(
            InteractionEventRepository events,
            TransactionalOutboxRepository outbox,
            ConsentQuery.PolicyReader policyReader,
            PayloadProtector payloadProtector) {
        this(events, outbox, policyReader, payloadProtector, new DisabledSourceEnvelopeStore());
    }

    /** Compatibility constructor for direct unit adapters where the bridge is disabled. */
    public SubmitMemoryEventsUseCase(
            InteractionEventRepository events,
            TransactionalOutboxRepository outbox,
            ConsentQuery.PolicyReader policyReader) {
        this(events, outbox, policyReader, PayloadProtector.disabled(), new DisabledSourceEnvelopeStore());
    }

    /**
     * Commits every accepted event and its outbox row in the same PostgreSQL
     * transaction. A duplicate never adds a second outbox row.
     */
    @Transactional
    public List<EventReceipt> submit(LearnerIdentity identity, List<InteractionEventCommand> commands) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(commands, "commands");
        var batch = List.copyOf(commands);
        var rejectionReasons = new ArrayList<String>(batch.size());
        var preflightAt = Instant.now();
        for (var command : batch) {
            rejectionReasons.add(preflightRejection(identity, command, preflightAt));
        }
        if (rejectionReasons.stream().anyMatch(Objects::nonNull)) {
            if (batch.size() == 1) {
                return List.of(EventReceipt.rejected(batch.getFirst().eventId(), rejectionReasons.getFirst()));
            }
            return batch.stream()
                    .map(command -> EventReceipt.rejected(command.eventId(), "BATCH_REJECTED"))
                    .toList();
        }

        var receipts = new ArrayList<EventReceipt>(batch.size());
        for (var command : batch) {
            receipts.add(persistPreflighted(identity, command));
        }
        return List.copyOf(receipts);
    }

    private String preflightRejection(
            LearnerIdentity identity,
            InteractionEventCommand command,
            Instant preflightAt) {
        if (!command.declaredSubjectHash().equals(identity.subjectHash())) {
            return "CROSS_SUBJECT";
        }
        if (command.declaredConsentRevision() != identity.consentRevision()) {
            return "STALE_EVENT_CONSENT";
        }

        ConsentDecision consent = consentQuery.evaluate(identity, command.category(), preflightAt);
        return consent.allowed() ? null : consent.denialReason().name();
    }

    private EventReceipt persistPreflighted(LearnerIdentity identity, InteractionEventCommand command) {
        var protectedSource = protectForPersistence(identity, command);
        var event = InteractionEvent.opaque(
                command.eventId(),
                command.schemaVersion(),
                command.eventType(),
                command.sourceKind(),
                command.occurredAt(),
                Instant.now(),
                command.privacyLevel(),
                command.declaredConsentRevision(),
                protectedSource.payload(),
                command.traceId());
        return switch (events.insertIfAbsent(identity, event)) {
            case DUPLICATE -> EventReceipt.duplicate(command.eventId());
            case CROSS_SUBJECT -> throw new BatchRejectedException(command.eventId(), "CROSS_SUBJECT");
            case INSERTED -> {
                if (protectedSource.envelope() != null) {
                    sourceEnvelopes.storeIfAbsent(identity.subjectHash(), command.eventId(), command.schemaVersion(),
                            protectedSource.envelope());
                }
                outbox.insert(OutboxEvent.forInteraction(event));
                yield EventReceipt.accepted(command.eventId());
            }
        };
    }

    private PersistedSource protectForPersistence(LearnerIdentity identity, InteractionEventCommand command) {
        if (!payloadProtector.enabled()) {
            if (requiresProtection(command.privacyLevel())) {
                throw new SecurityException("a configured local/test key is required for protected event persistence");
            }
            return new PersistedSource(command.payloadCiphertext(), null);
        }
        var sealed = payloadProtector.sealSource(new PayloadProtector.SourceMaterialBinding(
                identity.subjectHash(),
                command.eventId(),
                command.schemaVersion(),
                "interaction_event",
                com.yilan.memory.application.privacy.KeyPurpose.INTERACTION_SOURCE), command.payloadCiphertext());
        return new PersistedSource(sealed.framed(), sealed.envelope());
    }

    private static boolean requiresProtection(PrivacyLevel privacyLevel) {
        return privacyLevel == PrivacyLevel.SENSITIVE || privacyLevel == PrivacyLevel.HIGH;
    }

    /**
     * Signals a finite business rejection discovered after the batch preflight,
     * while writing to the durable idempotency boundary. It must escape the
     * transactional proxy so all earlier writes in this batch roll back.
     */
    public static final class BatchRejectedException extends RuntimeException {

        private final UUID eventId;
        private final String reasonCode;

        public BatchRejectedException(UUID eventId, String reasonCode) {
            super("batch rejected: " + reasonCode);
            this.eventId = Objects.requireNonNull(eventId, "eventId");
            if (!"CROSS_SUBJECT".equals(reasonCode)) {
                throw new IllegalArgumentException("reasonCode");
            }
            this.reasonCode = reasonCode;
        }

        public UUID eventId() {
            return eventId;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }

    public interface InteractionEventRepository {
        InsertOutcome insertIfAbsent(LearnerIdentity identity, InteractionEvent event);
    }

    private record PersistedSource(byte[] payload, InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
        private PersistedSource {
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    private static final class DisabledSourceEnvelopeStore implements InteractionPayloadKeyEnvelopeStore {
        @Override public java.util.Optional<Envelope> load(String subjectHash, UUID eventId, String schemaVersion) {
            return java.util.Optional.empty();
        }
        @Override public void storeIfAbsent(String subjectHash, UUID eventId, String schemaVersion, Envelope envelope) {
            throw new SecurityException("source envelope authority is unavailable");
        }
        @Override public boolean eraseForRetention(String subjectHash, UUID eventId, String schemaVersion,
                String policyVersion, Instant dueAt, Instant executedAt) { return false; }
        @Override public int eraseAllForSubject(String subjectHash) { return 0; }
    }

    public interface TransactionalOutboxRepository {
        void insert(OutboxEvent event);
    }

    public enum InsertOutcome {
        INSERTED,
        DUPLICATE,
        CROSS_SUBJECT
    }

    public enum EventResult {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }

    public record EventReceipt(UUID eventId, EventResult result, String reasonCode) {

        public EventReceipt {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(result, "result");
            if ((result == EventResult.ACCEPTED || result == EventResult.DUPLICATE) && reasonCode != null) {
                throw new IllegalArgumentException("successful receipt cannot include reasonCode");
            }
            if (result == EventResult.REJECTED && (reasonCode == null || reasonCode.isBlank())) {
                throw new IllegalArgumentException("rejected receipt requires reasonCode");
            }
        }

        public static EventReceipt accepted(UUID eventId) {
            return new EventReceipt(eventId, EventResult.ACCEPTED, null);
        }

        public static EventReceipt duplicate(UUID eventId) {
            return new EventReceipt(eventId, EventResult.DUPLICATE, null);
        }

        public static EventReceipt rejected(UUID eventId, String reasonCode) {
            return new EventReceipt(eventId, EventResult.REJECTED, reasonCode);
        }
    }

    public record InteractionEventCommand(
            UUID eventId,
            String schemaVersion,
            String eventType,
            MemoryCategory category,
            SourceKind sourceKind,
            String declaredSubjectHash,
            long declaredConsentRevision,
            Instant occurredAt,
            PrivacyLevel privacyLevel,
            byte[] payloadCiphertext,
            String traceId) {

        public InteractionEventCommand {
            Objects.requireNonNull(eventId, "eventId");
            if (schemaVersion == null || !schemaVersion.matches("[A-Za-z0-9._-]{1,16}")) {
                throw new IllegalArgumentException("schemaVersion");
            }
            if (eventType == null || eventType.isBlank() || eventType.length() > 96) {
                throw new IllegalArgumentException("eventType");
            }
            Objects.requireNonNull(category, "category");
            if (!category.name().equals(eventType)) {
                throw new IllegalArgumentException("eventType must match the consent category");
            }
            sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
            validateSourceKind(category, sourceKind);
            declaredSubjectHash = declaredSubjectHash == null ? "" : declaredSubjectHash;
            if (declaredConsentRevision < 0) {
                throw new IllegalArgumentException("declaredConsentRevision");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(privacyLevel, "privacyLevel");
            payloadCiphertext = Objects.requireNonNull(payloadCiphertext, "payloadCiphertext").clone();
            if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
                throw new IllegalArgumentException("traceId");
            }
        }

        @Override
        public byte[] payloadCiphertext() {
            return payloadCiphertext.clone();
        }

        private static void validateSourceKind(MemoryCategory category, SourceKind sourceKind) {
            boolean allowed = switch (sourceKind) {
                case GENERAL -> true;
                case EXPLICIT_DECLARATION -> category == MemoryCategory.PREFERENCE;
                case EPISODE -> category == MemoryCategory.REFLECTION;
                case SCORED_ASSESSMENT -> category == MemoryCategory.MASTERY;
            };
            if (!allowed) {
                throw new IllegalArgumentException("sourceKind is not valid for the event category");
            }
        }
    }

    public record OutboxEvent(
            UUID outboxId,
            UUID aggregateId,
            String eventType,
            String payloadJson,
            Instant createdAt) {

        public static OutboxEvent forInteraction(InteractionEvent event) {
            Objects.requireNonNull(event, "event");
            var payload = "{\"event_id\":\"" + event.eventId()
                    + "\",\"event_schema_version\":\"" + event.schemaVersion()
                    + "\",\"event_type\":\"" + event.eventType()
                    + "\",\"payload_digest\":\"" + event.payloadDigest() + "\"}";
            return new OutboxEvent(
                    UUID.randomUUID(),
                    event.eventId(),
                    "INTERACTION_EVENT_ACCEPTED",
                    payload,
                    event.receivedAt());
        }
    }
}
