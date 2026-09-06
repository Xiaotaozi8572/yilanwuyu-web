ALTER TABLE interaction_event
    ADD COLUMN source_kind varchar(32) DEFAULT 'GENERAL';

UPDATE interaction_event
SET source_kind = 'GENERAL'
WHERE source_kind IS NULL;

ALTER TABLE interaction_event
    ALTER COLUMN source_kind SET NOT NULL;

ALTER TABLE interaction_event
    ADD CONSTRAINT interaction_event_source_kind_check
    CHECK (source_kind IN (
        'GENERAL',
        'EXPLICIT_DECLARATION',
        'EPISODE',
        'SCORED_ASSESSMENT'
    ));

CREATE INDEX interaction_event_learner_source_kind_occurred_at_idx
    ON interaction_event (learner_subject_id, source_kind, occurred_at DESC);
