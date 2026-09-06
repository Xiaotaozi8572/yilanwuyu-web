package com.yilan.memory.application.privacy;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Authority persistence for opaque locally wrapped learner-DEK envelopes. */
public interface LearnerDekEnvelopeStore {

    Optional<Envelope> load(String subjectHash);

    /** Atomically retains the first envelope created for an active subject. */
    void storeIfAbsent(String subjectHash, Envelope envelope);

    void destroy(String subjectHash);

    default boolean exists(String subjectHash) {
        return load(subjectHash).isPresent();
    }

    record Envelope(String dekReference, byte[] wrappedDek, String wrappingKeyReference, Instant createdAt) {
        public Envelope {
            if (dekReference == null || !dekReference.matches("k-[0-9a-f]{32}")
                    || wrappingKeyReference == null || !wrappingKeyReference.matches("k-[0-9a-f]{32}")
                    || wrappedDek == null || wrappedDek.length <= 12 || createdAt == null) {
                throw new IllegalArgumentException("learner DEK envelope");
            }
            wrappedDek = wrappedDek.clone();
        }

        @Override
        public byte[] wrappedDek() {
            return wrappedDek.clone();
        }
    }
}
