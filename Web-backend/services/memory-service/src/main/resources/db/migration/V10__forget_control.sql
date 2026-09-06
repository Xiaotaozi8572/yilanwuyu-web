CREATE TABLE forget_request (
    forget_request_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    memory_assertion_id uuid REFERENCES memory_assertion (memory_assertion_id),
    scope varchar(16) NOT NULL CHECK (scope IN ('FULL', 'ASSERTION')),
    idempotency_key_digest char(64) NOT NULL,
    state varchar(16) NOT NULL CHECK (state = 'BLOCKED'),
    requested_at timestamptz NOT NULL,
    CHECK ((scope = 'FULL' AND memory_assertion_id IS NULL)
        OR (scope = 'ASSERTION' AND memory_assertion_id IS NOT NULL)),
    UNIQUE (learner_subject_id, idempotency_key_digest)
);
CREATE TABLE forget_tombstone (
    forget_tombstone_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    memory_assertion_id uuid REFERENCES memory_assertion (memory_assertion_id),
    scope varchar(16) NOT NULL CHECK (scope IN ('FULL', 'ASSERTION')),
    request_id uuid NOT NULL REFERENCES forget_request (forget_request_id),
    created_at timestamptz NOT NULL,
    CHECK ((scope = 'FULL' AND memory_assertion_id IS NULL)
        OR (scope = 'ASSERTION' AND memory_assertion_id IS NOT NULL))
);
CREATE TABLE forget_execution (
    forget_execution_id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES forget_request (forget_request_id),
    payload_references_removed bigint NOT NULL CHECK (payload_references_removed >= 0),
    executed_at timestamptz NOT NULL,
    UNIQUE (request_id)
);
CREATE TABLE learner_dek_envelope (
    learner_subject_id uuid PRIMARY KEY REFERENCES learner_subject (learner_subject_id),
    dek_key_reference varchar(34) NOT NULL,
    wrapped_dek bytea NOT NULL,
    wrap_key_reference varchar(34) NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX forget_request_one_full_per_learner_idx
    ON forget_request (learner_subject_id)
    WHERE scope = 'FULL';

CREATE FUNCTION prevent_forget_evidence_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'forget evidence is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER forget_request_is_immutable BEFORE UPDATE OR DELETE ON forget_request
FOR EACH ROW EXECUTE FUNCTION prevent_forget_evidence_mutation();
CREATE TRIGGER forget_tombstone_is_immutable BEFORE UPDATE OR DELETE ON forget_tombstone
FOR EACH ROW EXECUTE FUNCTION prevent_forget_evidence_mutation();
CREATE TRIGGER forget_execution_is_immutable BEFORE UPDATE OR DELETE ON forget_execution
FOR EACH ROW EXECUTE FUNCTION prevent_forget_evidence_mutation();

CREATE FUNCTION enforce_authority_write_gate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
    FROM learner_subject
    WHERE learner_subject_id = NEW.learner_subject_id
      AND status = 'ACTIVE'
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'authority write gate is closed' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_governance_authority_write_gate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
    FROM memory_candidate candidate
    JOIN learner_subject subject ON subject.learner_subject_id = candidate.learner_subject_id
    WHERE candidate.candidate_id = NEW.candidate_id
      AND candidate.learner_subject_id = NEW.learner_subject_id
      AND subject.status = 'ACTIVE'
    FOR KEY SHARE OF subject;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'authority write gate is closed' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_embedding_authority_write_gate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
    FROM memory_version version
    JOIN learner_subject subject ON subject.learner_subject_id = version.learner_subject_id
    WHERE version.memory_version_id = NEW.memory_version_id
      AND version.learner_subject_id = NEW.learner_subject_id
      AND subject.status = 'ACTIVE'
    FOR KEY SHARE OF subject;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'authority write gate is closed' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_head_authority_write_gate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
    FROM learner_subject
    WHERE learner_subject_id = NEW.learner_subject_id
      AND status = 'ACTIVE'
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'authority write gate is closed' USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1 FROM forget_tombstone tombstone
        WHERE tombstone.learner_subject_id = NEW.learner_subject_id
          AND tombstone.memory_assertion_id = NEW.memory_assertion_id
          AND tombstone.scope = 'ASSERTION'
    ) THEN
        RAISE EXCEPTION 'authority write gate is closed' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_forget_tombstone_integrity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_learner uuid;
    request_assertion uuid;
    request_scope varchar(16);
BEGIN
    SELECT learner_subject_id, memory_assertion_id, scope
    INTO request_learner, request_assertion, request_scope
    FROM forget_request
    WHERE forget_request_id = NEW.request_id;
    IF request_learner IS NULL
       OR NEW.learner_subject_id <> request_learner
       OR NEW.scope <> request_scope
       OR NEW.memory_assertion_id IS DISTINCT FROM request_assertion THEN
        RAISE EXCEPTION 'forget tombstone does not match request' USING ERRCODE = '23514';
    END IF;
    IF NEW.memory_assertion_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM memory_assertion assertion
        WHERE assertion.memory_assertion_id = NEW.memory_assertion_id
          AND assertion.learner_subject_id = NEW.learner_subject_id
    ) THEN
        RAISE EXCEPTION 'forget tombstone assertion does not match learner' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER forget_tombstone_has_consistent_request_and_assertion
BEFORE INSERT ON forget_tombstone
FOR EACH ROW EXECUTE FUNCTION enforce_forget_tombstone_integrity();

CREATE TRIGGER consent_policy_version_authority_write_gate
BEFORE INSERT OR UPDATE ON consent_policy_version
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER interaction_event_authority_write_gate
BEFORE INSERT OR UPDATE ON interaction_event
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER memory_candidate_authority_write_gate
BEFORE INSERT OR UPDATE ON memory_candidate
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER governance_decision_authority_write_gate
BEFORE INSERT OR UPDATE ON governance_decision
FOR EACH ROW EXECUTE FUNCTION enforce_governance_authority_write_gate();
CREATE TRIGGER memory_assertion_authority_write_gate
BEFORE INSERT OR UPDATE ON memory_assertion
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER memory_version_authority_write_gate
BEFORE INSERT OR UPDATE ON memory_version
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER memory_transition_authority_write_gate
BEFORE INSERT OR UPDATE ON memory_transition
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER memory_head_projection_authority_write_gate
BEFORE INSERT OR UPDATE ON memory_head_projection
FOR EACH ROW EXECUTE FUNCTION enforce_head_authority_write_gate();
CREATE TRIGGER memory_source_link_authority_write_gate
BEFORE INSERT OR UPDATE ON memory_source_link
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER memory_embedding_authority_write_gate
BEFORE INSERT OR UPDATE ON memory_embedding
FOR EACH ROW EXECUTE FUNCTION enforce_embedding_authority_write_gate();
CREATE TRIGGER memory_relation_event_authority_write_gate
BEFORE INSERT OR UPDATE ON memory_relation_event
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER learner_dek_envelope_authority_write_gate
BEFORE INSERT OR UPDATE ON learner_dek_envelope
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
