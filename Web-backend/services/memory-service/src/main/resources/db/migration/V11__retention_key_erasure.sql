CREATE TABLE interaction_payload_key_envelope (
    event_id uuid NOT NULL,
    schema_version varchar(16) NOT NULL,
    learner_subject_id uuid NOT NULL,
    dek_key_reference varchar(34) NOT NULL CHECK (dek_key_reference ~ '^k-[0-9a-f]{32}$'),
    wrapped_dek bytea NOT NULL CHECK (octet_length(wrapped_dek) > 12),
    wrap_key_reference varchar(34) NOT NULL CHECK (wrap_key_reference ~ '^k-[0-9a-f]{32}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (event_id, schema_version),
    FOREIGN KEY (event_id, schema_version, learner_subject_id)
        REFERENCES interaction_event (event_id, schema_version, learner_subject_id)
);

CREATE TABLE retention_erasure (
    retention_erasure_id uuid PRIMARY KEY,
    target_kind varchar(24) NOT NULL CHECK (target_kind IN ('INTERACTION_PAYLOAD', 'AUDIT_EVENT')),
    target_id uuid NOT NULL,
    event_schema_version varchar(16),
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    policy_version varchar(64) NOT NULL CHECK (policy_version ~ '^[A-Za-z0-9._-]{1,64}$'),
    due_at timestamptz NOT NULL,
    executed_at timestamptz NOT NULL,
    CHECK ((target_kind = 'INTERACTION_PAYLOAD' AND event_schema_version IS NOT NULL)
        OR (target_kind = 'AUDIT_EVENT' AND event_schema_version IS NULL))
);

CREATE UNIQUE INDEX retention_erasure_target_once_idx
    ON retention_erasure (target_kind, target_id, COALESCE(event_schema_version, ''));
CREATE INDEX interaction_payload_key_envelope_learner_idx
    ON interaction_payload_key_envelope (learner_subject_id, created_at);

CREATE FUNCTION prevent_retention_erasure_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'retention erasure evidence is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER retention_erasure_is_immutable
BEFORE UPDATE OR DELETE ON retention_erasure
FOR EACH ROW EXECUTE FUNCTION prevent_retention_erasure_mutation();

CREATE FUNCTION enforce_interaction_envelope_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM retention_erasure erasure
        WHERE erasure.target_kind = 'INTERACTION_PAYLOAD'
          AND erasure.target_id = OLD.event_id
          AND erasure.event_schema_version = OLD.schema_version
          AND erasure.learner_subject_id = OLD.learner_subject_id
    ) THEN
        RETURN OLD;
    END IF;
    IF EXISTS (
        SELECT 1 FROM learner_subject subject
        WHERE subject.learner_subject_id = OLD.learner_subject_id
          AND subject.status = 'DISABLED'
    ) AND EXISTS (
        SELECT 1 FROM forget_request request
        WHERE request.learner_subject_id = OLD.learner_subject_id
          AND request.scope = 'FULL'
    ) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'interaction envelope deletion requires retention or full forget evidence' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER interaction_payload_key_envelope_authority_write_gate
BEFORE INSERT OR UPDATE ON interaction_payload_key_envelope
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
CREATE TRIGGER interaction_payload_key_envelope_delete_gate
BEFORE DELETE ON interaction_payload_key_envelope
FOR EACH ROW EXECUTE FUNCTION enforce_interaction_envelope_delete();

CREATE OR REPLACE FUNCTION prevent_append_only_fact_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'memory_audit_event' AND TG_OP = 'DELETE' THEN
        IF EXISTS (
            SELECT 1 FROM retention_erasure erasure
            WHERE erasure.target_kind = 'AUDIT_EVENT'
              AND erasure.target_id = (to_jsonb(OLD) ->> 'memory_audit_event_id')::uuid
              AND erasure.event_schema_version IS NULL
              AND erasure.learner_subject_id = (to_jsonb(OLD) ->> 'learner_subject_id')::uuid
        ) THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'append-only fact row mutation is forbidden' USING ERRCODE = '55000';
END;
$$;
