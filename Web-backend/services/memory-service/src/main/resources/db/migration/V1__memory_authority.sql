CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE learner_subject (
    learner_subject_id uuid PRIMARY KEY,
    subject_hash varchar(128) UNIQUE NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL
);

CREATE TABLE consent_policy_version (
    consent_policy_version_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    revision bigint NOT NULL CHECK (revision >= 0),
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    allowed_categories jsonb NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    created_at timestamptz NOT NULL,
    UNIQUE (learner_subject_id, revision)
);

CREATE TABLE interaction_event (
    event_id uuid PRIMARY KEY,
    schema_version varchar(16) NOT NULL,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    session_id varchar(128) NOT NULL,
    event_type varchar(96) NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    privacy_level varchar(24) NOT NULL,
    consent_revision bigint NOT NULL,
    payload_ciphertext bytea,
    payload_digest char(64) NOT NULL,
    trace_id varchar(64) NOT NULL,
    UNIQUE (event_id, schema_version),
    UNIQUE (event_id, schema_version, learner_subject_id)
);

CREATE TABLE transactional_outbox (
    outbox_id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    event_type varchar(96) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);

CREATE TABLE memory_candidate (
    candidate_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    event_id uuid NOT NULL,
    event_schema_version varchar(16) NOT NULL,
    memory_type varchar(64) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('PENDING', 'GOVERNED', 'REJECTED')),
    privacy_level varchar(24) NOT NULL,
    candidate_ciphertext bytea NOT NULL,
    candidate_digest char(64) NOT NULL,
    source_count integer NOT NULL DEFAULT 1 CHECK (source_count >= 0),
    created_at timestamptz NOT NULL,
    UNIQUE (candidate_id, learner_subject_id),
    FOREIGN KEY (event_id, event_schema_version, learner_subject_id)
        REFERENCES interaction_event (event_id, schema_version, learner_subject_id)
);

CREATE TABLE governance_decision (
    governance_decision_id uuid PRIMARY KEY,
    candidate_id uuid NOT NULL,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    decision varchar(24) NOT NULL CHECK (decision IN ('ACCEPTED', 'REJECTED', 'PENDING')),
    rule_set_version varchar(64) NOT NULL,
    reason_codes jsonb NOT NULL,
    decided_at timestamptz NOT NULL,
    FOREIGN KEY (candidate_id, learner_subject_id)
        REFERENCES memory_candidate (candidate_id, learner_subject_id)
);

CREATE TABLE memory_assertion (
    memory_assertion_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    assertion_key varchar(128) NOT NULL,
    memory_type varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (learner_subject_id, assertion_key),
    UNIQUE (memory_assertion_id, learner_subject_id)
);

CREATE TABLE memory_version (
    memory_version_id uuid PRIMARY KEY,
    memory_assertion_id uuid NOT NULL,
    learner_subject_id uuid NOT NULL,
    version_sequence bigint NOT NULL CHECK (version_sequence > 0),
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'STALE', 'SUPERSEDED', 'EXPIRED', 'REJECTED', 'ARCHIVED')),
    value_json jsonb NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    recorded_at timestamptz NOT NULL,
    recorded_until timestamptz,
    confidence numeric(5,4) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    stability_score numeric(5,4) NOT NULL CHECK (stability_score >= 0 AND stability_score <= 1),
    privacy_level varchar(24) NOT NULL,
    consent_revision bigint NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (memory_assertion_id, version_sequence),
    UNIQUE (memory_version_id, learner_subject_id),
    UNIQUE (memory_version_id, memory_assertion_id, learner_subject_id),
    FOREIGN KEY (memory_assertion_id, learner_subject_id)
        REFERENCES memory_assertion (memory_assertion_id, learner_subject_id)
);

CREATE TABLE memory_transition (
    memory_transition_id uuid PRIMARY KEY,
    memory_assertion_id uuid NOT NULL,
    learner_subject_id uuid NOT NULL,
    from_memory_version_id uuid,
    to_memory_version_id uuid NOT NULL,
    transition_type varchar(24) NOT NULL CHECK (transition_type IN ('ACTIVATE', 'STALE', 'SUPERSEDE', 'EXPIRE', 'REJECT', 'ARCHIVE')),
    reason_code varchar(96) NOT NULL,
    transitioned_at timestamptz NOT NULL,
    FOREIGN KEY (memory_assertion_id, learner_subject_id)
        REFERENCES memory_assertion (memory_assertion_id, learner_subject_id),
    FOREIGN KEY (from_memory_version_id, memory_assertion_id, learner_subject_id)
        REFERENCES memory_version (memory_version_id, memory_assertion_id, learner_subject_id),
    FOREIGN KEY (to_memory_version_id, memory_assertion_id, learner_subject_id)
        REFERENCES memory_version (memory_version_id, memory_assertion_id, learner_subject_id)
);

CREATE FUNCTION prevent_memory_transition_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'memory_transition is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER memory_transition_is_immutable
BEFORE UPDATE OR DELETE ON memory_transition
FOR EACH ROW
EXECUTE FUNCTION prevent_memory_transition_mutation();

CREATE TABLE memory_head_projection (
    memory_assertion_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL,
    memory_version_id uuid NOT NULL,
    status varchar(24) NOT NULL,
    memory_epoch bigint NOT NULL CHECK (memory_epoch >= 0),
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (memory_assertion_id, learner_subject_id)
        REFERENCES memory_assertion (memory_assertion_id, learner_subject_id),
    FOREIGN KEY (memory_version_id, memory_assertion_id, learner_subject_id)
        REFERENCES memory_version (memory_version_id, memory_assertion_id, learner_subject_id)
);

CREATE TABLE memory_source_link (
    memory_source_link_id uuid PRIMARY KEY,
    memory_version_id uuid NOT NULL,
    learner_subject_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    source_schema_version varchar(16) NOT NULL,
    source_kind varchar(32) NOT NULL CHECK (source_kind IN ('INTERACTION_EVENT')),
    created_at timestamptz NOT NULL,
    UNIQUE (memory_version_id, source_event_id, source_schema_version),
    FOREIGN KEY (memory_version_id, learner_subject_id)
        REFERENCES memory_version (memory_version_id, learner_subject_id),
    FOREIGN KEY (source_event_id, source_schema_version, learner_subject_id)
        REFERENCES interaction_event (event_id, schema_version, learner_subject_id)
);

CREATE TABLE memory_embedding (
    memory_embedding_id uuid PRIMARY KEY,
    memory_version_id uuid NOT NULL,
    learner_subject_id uuid NOT NULL,
    embedding vector(1024) NOT NULL,
    embedding_profile varchar(96) NOT NULL,
    embedding_profile_version varchar(32) NOT NULL,
    embedding_dimension smallint NOT NULL DEFAULT 1024 CHECK (embedding_dimension = 1024),
    content_digest char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (memory_version_id, embedding_profile, embedding_profile_version),
    FOREIGN KEY (memory_version_id, learner_subject_id)
        REFERENCES memory_version (memory_version_id, learner_subject_id)
);

CREATE TABLE memory_relation_event (
    memory_relation_event_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL,
    from_memory_assertion_id uuid NOT NULL,
    to_memory_assertion_id uuid NOT NULL,
    relation_type varchar(48) NOT NULL,
    relation_status varchar(24) NOT NULL CHECK (relation_status IN ('ACTIVE', 'REJECTED', 'RETRACTED')),
    event_id uuid,
    event_schema_version varchar(16),
    created_at timestamptz NOT NULL,
    CHECK (from_memory_assertion_id <> to_memory_assertion_id),
    CHECK ((event_id IS NULL) = (event_schema_version IS NULL)),
    FOREIGN KEY (from_memory_assertion_id, learner_subject_id)
        REFERENCES memory_assertion (memory_assertion_id, learner_subject_id),
    FOREIGN KEY (to_memory_assertion_id, learner_subject_id)
        REFERENCES memory_assertion (memory_assertion_id, learner_subject_id),
    FOREIGN KEY (event_id, event_schema_version, learner_subject_id)
        REFERENCES interaction_event (event_id, schema_version, learner_subject_id)
);

CREATE TABLE learner_epoch (
    learner_subject_id uuid PRIMARY KEY REFERENCES learner_subject (learner_subject_id),
    memory_epoch bigint NOT NULL DEFAULT 0 CHECK (memory_epoch >= 0),
    consent_epoch bigint NOT NULL DEFAULT 0 CHECK (consent_epoch >= 0),
    updated_at timestamptz NOT NULL
);

CREATE TABLE processing_checkpoint (
    processor_name varchar(96) NOT NULL,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    checkpoint_key varchar(128) NOT NULL,
    last_outbox_id uuid,
    last_event_id uuid,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (processor_name, learner_subject_id, checkpoint_key)
);

CREATE TABLE deletion_request (
    deletion_request_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    request_id uuid NOT NULL UNIQUE,
    deletion_scope varchar(48) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'REJECTED')),
    requested_at timestamptz NOT NULL,
    completed_at timestamptz
);

CREATE TABLE memory_audit_event (
    memory_audit_event_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    audit_type varchar(96) NOT NULL,
    actor_type varchar(32) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    redacted_metadata jsonb NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX consent_policy_version_learner_status_time_idx
    ON consent_policy_version (learner_subject_id, status, valid_from, valid_until);
CREATE INDEX interaction_event_learner_occurred_at_idx
    ON interaction_event (learner_subject_id, occurred_at DESC);
CREATE INDEX interaction_event_learner_event_type_occurred_at_idx
    ON interaction_event (learner_subject_id, event_type, occurred_at DESC);
CREATE INDEX transactional_outbox_unpublished_created_at_idx
    ON transactional_outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX memory_candidate_learner_status_created_at_idx
    ON memory_candidate (learner_subject_id, status, created_at DESC);
CREATE INDEX governance_decision_candidate_decided_at_idx
    ON governance_decision (candidate_id, decided_at DESC);
CREATE INDEX memory_assertion_learner_type_idx
    ON memory_assertion (learner_subject_id, memory_type);
CREATE INDEX memory_version_learner_status_time_idx
    ON memory_version (learner_subject_id, status, valid_from, valid_until);
CREATE INDEX memory_version_assertion_recorded_at_idx
    ON memory_version (memory_assertion_id, recorded_at DESC);
CREATE INDEX memory_head_projection_learner_status_idx
    ON memory_head_projection (learner_subject_id, status);
CREATE INDEX memory_source_link_source_event_idx
    ON memory_source_link (source_event_id, source_schema_version);
CREATE INDEX memory_embedding_learner_profile_created_at_idx
    ON memory_embedding (learner_subject_id, embedding_profile, created_at DESC);
CREATE INDEX memory_relation_event_learner_created_at_idx
    ON memory_relation_event (learner_subject_id, created_at DESC);
CREATE INDEX deletion_request_learner_status_requested_at_idx
    ON deletion_request (learner_subject_id, status, requested_at DESC);
CREATE INDEX memory_audit_event_learner_created_at_idx
    ON memory_audit_event (learner_subject_id, created_at DESC);
