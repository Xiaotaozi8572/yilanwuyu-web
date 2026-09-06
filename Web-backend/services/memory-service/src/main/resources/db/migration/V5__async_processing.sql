CREATE TABLE async_processing_checkpoint (
    event_id uuid NOT NULL,
    stage_name varchar(64) NOT NULL,
    stage_version integer NOT NULL CHECK (stage_version > 0),
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    outbox_id uuid,
    status varchar(24) NOT NULL CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCEEDED', 'RETRYABLE', 'DEAD_LETTER', 'OBSOLETE'
    )),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0 AND attempts <= 5),
    next_attempt_at timestamptz,
    last_diagnostic_code varchar(96),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (event_id, stage_name, stage_version),
    CHECK (last_diagnostic_code IS NULL OR last_diagnostic_code IN (
        'PROCESSING_FAILURE', 'SUBJECT_INACTIVE', 'UNCLASSIFIED_FAILURE'
    ))
);

CREATE INDEX async_processing_checkpoint_recovery_idx
    ON async_processing_checkpoint (status, next_attempt_at, updated_at);

CREATE INDEX async_processing_checkpoint_learner_stage_idx
    ON async_processing_checkpoint (learner_subject_id, stage_name, stage_version);
