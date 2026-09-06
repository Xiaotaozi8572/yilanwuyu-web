ALTER TABLE memory_embedding
    ADD COLUMN embedding_normalization varchar(16) NOT NULL DEFAULT 'UNSPECIFIED'
        CHECK (embedding_normalization IN ('UNSPECIFIED', 'L2'));

DO $$
DECLARE
    prior_profile_constraint text;
BEGIN
    SELECT constraint_record.conname
    INTO prior_profile_constraint
    FROM pg_constraint constraint_record
    WHERE constraint_record.conrelid = 'memory_embedding'::regclass
      AND constraint_record.contype = 'u'
      AND constraint_record.conkey = ARRAY[
          (SELECT attnum FROM pg_attribute
           WHERE attrelid = 'memory_embedding'::regclass AND attname = 'memory_version_id'),
          (SELECT attnum FROM pg_attribute
           WHERE attrelid = 'memory_embedding'::regclass AND attname = 'embedding_profile'),
          (SELECT attnum FROM pg_attribute
           WHERE attrelid = 'memory_embedding'::regclass AND attname = 'embedding_profile_version')
      ]::smallint[];

    IF prior_profile_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE memory_embedding DROP CONSTRAINT %I', prior_profile_constraint);
    END IF;
END;
$$;

ALTER TABLE memory_embedding
    ADD CONSTRAINT memory_embedding_profile_normalization_unique
        UNIQUE (memory_version_id, embedding_profile, embedding_profile_version, embedding_normalization);
