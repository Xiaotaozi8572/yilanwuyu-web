CREATE TABLE migration_shadow_import (
    migration_shadow_import_id uuid PRIMARY KEY,
    manifest_fingerprint char(64) NOT NULL CHECK (manifest_fingerprint ~ '^[0-9a-f]{64}$'),
    source_total integer NOT NULL CHECK (source_total >= 0),
    accepted_count integer NOT NULL CHECK (accepted_count >= 0),
    rejected_count integer NOT NULL CHECK (rejected_count >= 0),
    duplicate_count integer NOT NULL CHECK (duplicate_count >= 0),
    newly_imported_count integer NOT NULL CHECK (newly_imported_count >= 0),
    created_at timestamptz NOT NULL,
    CHECK (accepted_count + rejected_count = source_total),
    CHECK (duplicate_count + newly_imported_count = source_total)
);

CREATE TABLE migration_shadow_record (
    migration_shadow_record_id uuid PRIMARY KEY,
    migration_shadow_import_id uuid NOT NULL REFERENCES migration_shadow_import (migration_shadow_import_id),
    legacy_key varchar(512) NOT NULL UNIQUE,
    pseudonymous_subject varchar(128),
    source_keys jsonb NOT NULL,
    record_kind varchar(64) NOT NULL,
    legacy_type varchar(96),
    legacy_status varchar(64) NOT NULL,
    memory_type varchar(24),
    shadow_route varchar(40) NOT NULL CHECK (shadow_route IN (
        'PENDING_AUTHORITY_GOVERNANCE', 'EXPLICIT_LEGACY_REJECTION')),
    rejection_code varchar(64),
    payload_digest char(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    payload_frame bytea NOT NULL CHECK (octet_length(payload_frame) > 28),
    payload_algorithm varchar(32) NOT NULL CHECK (payload_algorithm = 'AES-256-GCM'),
    payload_crypto_version varchar(24) NOT NULL CHECK (payload_crypto_version = 'migration-shadow/v1'),
    created_at timestamptz NOT NULL,
    CHECK (jsonb_typeof(source_keys) = 'array'),
    CHECK ((shadow_route = 'PENDING_AUTHORITY_GOVERNANCE' AND rejection_code IS NULL
            AND (record_kind NOT IN ('memory_candidate', 'structured_memory')
                 OR memory_type IN ('PREFERENCE', 'MASTERY', 'MISCONCEPTION', 'REFLECTION')))
        OR (shadow_route = 'EXPLICIT_LEGACY_REJECTION' AND rejection_code IS NOT NULL))
);

CREATE INDEX migration_shadow_record_subject_created_idx
    ON migration_shadow_record (pseudonymous_subject, created_at);

CREATE FUNCTION prevent_migration_shadow_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'migration shadow rows are append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER migration_shadow_import_is_immutable
BEFORE UPDATE OR DELETE ON migration_shadow_import
FOR EACH ROW EXECUTE FUNCTION prevent_migration_shadow_mutation();

CREATE TRIGGER migration_shadow_record_is_immutable
BEFORE UPDATE OR DELETE ON migration_shadow_record
FOR EACH ROW EXECUTE FUNCTION prevent_migration_shadow_mutation();
