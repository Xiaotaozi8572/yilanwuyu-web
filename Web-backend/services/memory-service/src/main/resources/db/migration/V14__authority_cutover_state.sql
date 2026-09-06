CREATE TABLE memory_authority_cutover_state (
    control_id SMALLINT PRIMARY KEY CHECK (control_id = 1),
    authority_mode VARCHAR(32) NOT NULL CHECK (authority_mode IN ('LOCAL', 'SHADOW', 'CUTOVER_PREPARED', 'REMOTE')),
    authority_epoch BIGINT NOT NULL CHECK (authority_epoch >= 0),
    python_outbox_watermark BIGINT NOT NULL CHECK (python_outbox_watermark >= 0),
    schema_version VARCHAR(16) NOT NULL CHECK (schema_version = 'v1'),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO memory_authority_cutover_state(
    control_id, authority_mode, authority_epoch, python_outbox_watermark, schema_version
) VALUES (1, 'LOCAL', 0, 0, 'v1');
