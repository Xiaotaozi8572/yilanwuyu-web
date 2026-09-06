ALTER TABLE consent_policy_version
    ADD COLUMN purpose_text_version varchar(64) NOT NULL DEFAULT 'legacy-unrecorded',
    ADD COLUMN source_capture_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN retention_days integer,
    ADD COLUMN actor_role varchar(24) NOT NULL DEFAULT 'SYSTEM',
    ADD CONSTRAINT consent_policy_version_retention_days_check
        CHECK (retention_days IS NULL OR retention_days > 0),
    ADD CONSTRAINT consent_policy_version_actor_role_check
        CHECK (actor_role IN ('LEARNER', 'ADMIN', 'AUDITOR', 'SYSTEM'));

CREATE INDEX consent_policy_version_current_lookup_idx
    ON consent_policy_version (learner_subject_id, revision DESC, created_at DESC);

CREATE INDEX consent_policy_version_actor_time_idx
    ON consent_policy_version (learner_subject_id, actor_role, created_at DESC);
