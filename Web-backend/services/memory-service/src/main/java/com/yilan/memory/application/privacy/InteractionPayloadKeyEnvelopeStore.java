package com.yilan.memory.application.privacy;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authority persistence for the one-event DEK envelope of a source payload. */
public interface InteractionPayloadKeyEnvelopeStore {

    Optional<Envelope> load(String subjectHash, UUID eventId, String schemaVersion);

    void storeIfAbsent(String subjectHash, UUID eventId, String schemaVersion, Envelope envelope);

    /** Writes immutable retention evidence and erases only the matching source envelope. */
    boolean eraseForRetention(
            String subjectHash, UUID eventId, String schemaVersion,
            String policyVersion, Instant dueAt, Instant executedAt);

    /** Full-subject forget only; callers must already have closed the authority subject gate. */
    int eraseAllForSubject(String subjectHash);

    record Envelope(String dekReference, byte[] wrappedDek, String wrappingKeyReference, Instant createdAt) {
        public Envelope {
            if (dekReference == null || !dekReference.matches("k-[0-9a-f]{32}")
                    || wrappingKeyReference == null || !wrappingKeyReference.matches("k-[0-9a-f]{32}")
                    || wrappedDek == null || wrappedDek.length <= 12 || createdAt == null) {
                throw new IllegalArgumentException("interaction payload key envelope");
            }
            wrappedDek = wrappedDek.clone();
        }

        @Override
        public byte[] wrappedDek() {
            return wrappedDek.clone();
        }
    }

    static void requireBinding(String subjectHash, UUID eventId, String schemaVersion) {
        if (subjectHash == null || subjectHash.isBlank() || eventId == null
                || schemaVersion == null || !schemaVersion.matches("[A-Za-z0-9._-]{1,16}")) {
            throw new IllegalArgumentException("interaction payload key binding");
        }
    }
}
