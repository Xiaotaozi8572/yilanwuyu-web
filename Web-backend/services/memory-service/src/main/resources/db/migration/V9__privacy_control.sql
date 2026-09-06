ALTER TABLE interaction_event
    ADD COLUMN payload_key_reference varchar(34),
    ADD COLUMN payload_nonce bytea,
    ADD COLUMN payload_algorithm varchar(24),
    ADD COLUMN payload_crypto_version smallint;

ALTER TABLE memory_candidate
    ADD COLUMN candidate_key_reference varchar(34),
    ADD COLUMN candidate_nonce bytea,
    ADD COLUMN candidate_algorithm varchar(24),
    ADD COLUMN candidate_crypto_version smallint;

ALTER TABLE memory_version
    ALTER COLUMN value_json DROP NOT NULL,
    ADD COLUMN protected_value_ciphertext bytea,
    ADD COLUMN protected_value_nonce bytea,
    ADD COLUMN protected_value_key_reference varchar(34),
    ADD COLUMN protected_value_algorithm varchar(24),
    ADD COLUMN protected_value_crypto_version smallint,
    ADD CONSTRAINT memory_version_protected_value_shape_check CHECK (
        privacy_level NOT IN ('SENSITIVE', 'HIGH')
        OR (
            value_json IS NULL
            AND protected_value_ciphertext IS NOT NULL
            AND protected_value_nonce IS NOT NULL
            AND octet_length(protected_value_nonce) = 12
            AND protected_value_key_reference IS NOT NULL
            AND protected_value_key_reference ~ '^k-[0-9a-f]{32}$'
            AND protected_value_algorithm IS NOT NULL
            AND protected_value_algorithm = 'AES-256-GCM'
            AND protected_value_crypto_version IS NOT NULL
            AND protected_value_crypto_version = 1
        )
    ) NOT VALID;

ALTER TABLE interaction_event
    ADD CONSTRAINT interaction_event_protected_payload_shape_check CHECK (
        privacy_level NOT IN ('SENSITIVE', 'HIGH')
        OR (
            payload_ciphertext IS NOT NULL
            AND payload_nonce IS NOT NULL
            AND octet_length(payload_nonce) = 12
            AND payload_key_reference IS NOT NULL
            AND payload_key_reference ~ '^k-[0-9a-f]{32}$'
            AND payload_algorithm IS NOT NULL
            AND payload_algorithm = 'AES-256-GCM'
            AND payload_crypto_version IS NOT NULL
            AND payload_crypto_version = 1
        )
    ) NOT VALID;

ALTER TABLE memory_candidate
    ADD CONSTRAINT memory_candidate_protected_payload_shape_check CHECK (
        privacy_level NOT IN ('SENSITIVE', 'HIGH')
        OR (
            candidate_ciphertext IS NOT NULL
            AND candidate_nonce IS NOT NULL
            AND octet_length(candidate_nonce) = 12
            AND candidate_key_reference IS NOT NULL
            AND candidate_key_reference ~ '^k-[0-9a-f]{32}$'
            AND candidate_algorithm IS NOT NULL
            AND candidate_algorithm = 'AES-256-GCM'
            AND candidate_crypto_version IS NOT NULL
            AND candidate_crypto_version = 1
        )
    ) NOT VALID;

CREATE INDEX interaction_event_protected_payload_key_idx
    ON interaction_event (payload_key_reference)
    WHERE payload_key_reference IS NOT NULL;

CREATE INDEX memory_candidate_protected_payload_key_idx
    ON memory_candidate (candidate_key_reference)
    WHERE candidate_key_reference IS NOT NULL;

CREATE INDEX memory_version_protected_payload_key_idx
    ON memory_version (protected_value_key_reference)
    WHERE protected_value_key_reference IS NOT NULL;
