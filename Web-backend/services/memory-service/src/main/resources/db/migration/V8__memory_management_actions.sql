CREATE TABLE memory_management_action_receipt (
    memory_management_action_receipt_id uuid PRIMARY KEY,
    learner_subject_id uuid NOT NULL REFERENCES learner_subject (learner_subject_id),
    memory_assertion_id uuid NOT NULL,
    action_type varchar(24) NOT NULL CHECK (action_type IN ('CONFIRM', 'CORRECT', 'DISABLE')),
    idempotency_key_digest char(64) NOT NULL,
    request_digest char(64) NOT NULL,
    result_memory_version_id uuid NOT NULL,
    result_head_status varchar(24) NOT NULL CHECK (result_head_status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL,
    UNIQUE (learner_subject_id, idempotency_key_digest),
    FOREIGN KEY (memory_assertion_id, learner_subject_id)
        REFERENCES memory_assertion (memory_assertion_id, learner_subject_id),
    FOREIGN KEY (result_memory_version_id, memory_assertion_id, learner_subject_id)
        REFERENCES memory_version (memory_version_id, memory_assertion_id, learner_subject_id)
);

CREATE INDEX memory_management_action_receipt_subject_action_time_idx
    ON memory_management_action_receipt (learner_subject_id, action_type, created_at DESC);

CREATE INDEX memory_management_action_receipt_result_idx
    ON memory_management_action_receipt (result_memory_version_id);
