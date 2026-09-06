package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.privacy.LearnerDekEnvelopeStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/** Envelope existence is authority state; plaintext DEKs are never handled here. */
@Repository
public class JdbcLearnerDekEnvelopeStore implements LearnerDekEnvelopeStore {
    private final JdbcClient jdbc;
    public JdbcLearnerDekEnvelopeStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Envelope> load(String subjectHash) {
        return jdbc.sql("SELECT e.dek_key_reference, e.wrapped_dek, e.wrap_key_reference, e.created_at FROM learner_dek_envelope e JOIN learner_subject s ON s.learner_subject_id=e.learner_subject_id WHERE s.subject_hash=:subject")
                .param("subject", subjectHash)
                .query((rs, ignored) -> new Envelope(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getTimestamp(4).toInstant()))
                .optional();
    }

    @Override public void storeIfAbsent(String subjectHash, Envelope envelope) {
        jdbc.sql("INSERT INTO learner_dek_envelope (learner_subject_id, dek_key_reference, wrapped_dek, wrap_key_reference, created_at) SELECT learner_subject_id, :dekRef, :wrapped, :wrapRef, :created FROM learner_subject WHERE subject_hash=:subject AND status='ACTIVE' ON CONFLICT (learner_subject_id) DO NOTHING")
                .param("subject", subjectHash).param("dekRef", envelope.dekReference())
                .param("wrapped", envelope.wrappedDek()).param("wrapRef", envelope.wrappingKeyReference())
                .param("created", Timestamp.from(envelope.createdAt())).update();
    }
    @Override public void destroy(String subjectHash) { jdbc.sql("DELETE FROM learner_dek_envelope e USING learner_subject s WHERE e.learner_subject_id=s.learner_subject_id AND s.subject_hash=:subject").param("subject", subjectHash).update(); }
}
