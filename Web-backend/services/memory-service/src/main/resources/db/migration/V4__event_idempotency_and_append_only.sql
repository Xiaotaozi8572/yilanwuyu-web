ALTER TABLE interaction_event
    DROP CONSTRAINT interaction_event_pkey;

ALTER TABLE interaction_event
    DROP CONSTRAINT interaction_event_event_id_schema_version_key;

ALTER TABLE interaction_event
    ADD CONSTRAINT interaction_event_pkey PRIMARY KEY (event_id, schema_version);

DROP TRIGGER IF EXISTS memory_transition_is_immutable ON memory_transition;
DROP FUNCTION IF EXISTS prevent_memory_transition_mutation();

CREATE FUNCTION prevent_append_only_fact_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'append-only fact row mutation is forbidden';
END;
$$;

CREATE TRIGGER interaction_event_is_append_only
BEFORE UPDATE OR DELETE ON interaction_event
FOR EACH ROW EXECUTE FUNCTION prevent_append_only_fact_mutation();

CREATE TRIGGER memory_candidate_is_append_only
BEFORE UPDATE OR DELETE ON memory_candidate
FOR EACH ROW EXECUTE FUNCTION prevent_append_only_fact_mutation();

CREATE TRIGGER governance_decision_is_append_only
BEFORE UPDATE OR DELETE ON governance_decision
FOR EACH ROW EXECUTE FUNCTION prevent_append_only_fact_mutation();

CREATE TRIGGER memory_version_is_append_only
BEFORE UPDATE OR DELETE ON memory_version
FOR EACH ROW EXECUTE FUNCTION prevent_append_only_fact_mutation();

CREATE TRIGGER memory_transition_is_append_only
BEFORE UPDATE OR DELETE ON memory_transition
FOR EACH ROW EXECUTE FUNCTION prevent_append_only_fact_mutation();

CREATE TRIGGER memory_source_link_is_append_only
BEFORE UPDATE OR DELETE ON memory_source_link
FOR EACH ROW EXECUTE FUNCTION prevent_append_only_fact_mutation();

CREATE TRIGGER memory_relation_event_is_append_only
BEFORE UPDATE OR DELETE ON memory_relation_event
FOR EACH ROW EXECUTE FUNCTION prevent_append_only_fact_mutation();

CREATE TRIGGER memory_audit_event_is_append_only
BEFORE UPDATE OR DELETE ON memory_audit_event
FOR EACH ROW EXECUTE FUNCTION prevent_append_only_fact_mutation();
