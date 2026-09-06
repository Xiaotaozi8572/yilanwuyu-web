CREATE TABLE consent_action_receipt (
    consent_action_receipt_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    idempotency_key_digest char(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    result_policy_revision bigint NOT NULL CHECK (result_policy_revision >= 1),
    created_at timestamptz NOT NULL,
    UNIQUE (learner_subject_id, idempotency_key_digest)
);

CREATE FUNCTION prevent_consent_action_receipt_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'consent action receipt is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER consent_action_receipt_is_immutable
BEFORE UPDATE OR DELETE ON consent_action_receipt
FOR EACH ROW EXECUTE FUNCTION prevent_consent_action_receipt_mutation();

CREATE TRIGGER consent_action_receipt_authority_write_gate
BEFORE INSERT OR UPDATE ON consent_action_receipt
FOR EACH ROW EXECUTE FUNCTION enforce_authority_write_gate();
