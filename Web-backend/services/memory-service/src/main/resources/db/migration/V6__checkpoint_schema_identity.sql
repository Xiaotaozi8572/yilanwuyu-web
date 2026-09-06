ALTER TABLE async_processing_checkpoint
    ADD COLUMN event_schema_version varchar(16);

UPDATE async_processing_checkpoint checkpoint
SET event_schema_version = outbox.payload ->> 'event_schema_version'
FROM transactional_outbox outbox
JOIN interaction_event event
  ON event.event_id = outbox.aggregate_id
 AND event.schema_version = outbox.payload ->> 'event_schema_version'
WHERE checkpoint.outbox_id = outbox.outbox_id
  AND checkpoint.event_id = outbox.aggregate_id
  AND outbox.event_type = 'INTERACTION_EVENT_ACCEPTED'
  AND outbox.payload ->> 'event_id' = CAST(checkpoint.event_id AS text)
  AND octet_length(outbox.payload ->> 'event_schema_version') BETWEEN 1 AND 16
  AND outbox.payload ->> 'event_schema_version' <> '__unresolved__';

UPDATE async_processing_checkpoint
SET event_schema_version = '__unresolved__'
WHERE event_schema_version IS NULL;

ALTER TABLE async_processing_checkpoint
    ALTER COLUMN event_schema_version SET NOT NULL;

ALTER TABLE async_processing_checkpoint
    ADD CONSTRAINT async_processing_checkpoint_schema_version_bytes_check
    CHECK (octet_length(event_schema_version) BETWEEN 1 AND 16);

ALTER TABLE async_processing_checkpoint
    DROP CONSTRAINT async_processing_checkpoint_last_diagnostic_code_check;

ALTER TABLE async_processing_checkpoint
    ADD CONSTRAINT async_checkpoint_diagnostic_allowlist_check
    CHECK (last_diagnostic_code IS NULL OR last_diagnostic_code IN (
        'PROCESSING_FAILURE', 'SUBJECT_INACTIVE', 'UNCLASSIFIED_FAILURE',
        'SOURCE_AUTHORITY_UNAVAILABLE', 'SOURCE_KEY_OR_CIPHERTEXT_UNAVAILABLE',
        'SOURCE_KEY_UNAVAILABLE', 'WORKER_DEADLINE_EXCEEDED', 'WORKER_UNAVAILABLE',
        'MODEL_UNAVAILABLE_RETRYABLE', 'WORKER_PROVIDER_FAILURE',
        'BACKGROUND_CAPACITY_EXHAUSTED', 'ONLINE_ANSWER_PRESSURE',
        'MISSING_HEALTH_LEASE', 'TEMPERATURE_THRESHOLD_EXCEEDED', 'CUDA_OOM'
    ));

ALTER TABLE async_processing_checkpoint
    DROP CONSTRAINT async_processing_checkpoint_pkey;

ALTER TABLE async_processing_checkpoint
    ADD PRIMARY KEY (event_id, event_schema_version, stage_name, stage_version);
