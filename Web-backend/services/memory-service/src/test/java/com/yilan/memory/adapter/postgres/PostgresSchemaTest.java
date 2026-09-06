package com.yilan.memory.adapter.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.yilan.memory.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PostgresSchemaTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsAuthorityTablesAndVectorExtension() {
        assertThat(tableNames()).contains(
                "learner_subject", "consent_policy_version", "interaction_event",
                "transactional_outbox", "memory_candidate", "governance_decision",
                "memory_assertion", "memory_version", "memory_transition",
                "memory_head_projection", "memory_source_link", "memory_embedding",
                "memory_relation_event", "learner_epoch", "processing_checkpoint",
                "deletion_request", "memory_audit_event", "interaction_payload_key_envelope", "retention_erasure",
                "consent_action_receipt");
        assertThat(extensionNames()).contains("vector");
        assertThat(flywayVersions()).contains("1", "2", "3", "4", "9", "10", "11", "12");
        assertThat(embeddingColumnType()).isEqualTo("vector(1024)");
        assertThat(primaryKeyColumns("interaction_event")).isEqualTo("event_id,schema_version");
        assertThat(indexNames()).contains(
                "interaction_event_learner_occurred_at_idx",
                "transactional_outbox_unpublished_created_at_idx",
                "memory_version_learner_status_time_idx",
                "memory_embedding_learner_profile_created_at_idx",
                "memory_audit_event_learner_created_at_idx",
                "interaction_event_protected_payload_key_idx",
                "memory_candidate_protected_payload_key_idx",
                "memory_version_protected_payload_key_idx");
        assertThat(indexDefinitions())
                .noneMatch(indexDefinition -> indexDefinition.toLowerCase(Locale.ROOT).contains("using hnsw"));
    }

    @Test
    void v12MakesConsentReceiptsDigestOnlyImmutableAndAuthorityGated() {
        var learner = insertLearner();
        var receipt = UUID.randomUUID();
        var keyDigest = "a".repeat(64);
        var requestDigest = "b".repeat(64);

        assertThat(columnNames("consent_action_receipt")).containsExactlyInAnyOrder(
                "consent_action_receipt_id", "learner_subject_id", "idempotency_key_digest",
                "request_digest", "result_policy_revision", "created_at");
        jdbcTemplate.update("""
                insert into consent_action_receipt (
                    consent_action_receipt_id, learner_subject_id, idempotency_key_digest,
                    request_digest, result_policy_revision, created_at)
                values (?, ?, ?, ?, 1, current_timestamp)
                """, receipt, learner, keyDigest, requestDigest);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into consent_action_receipt (
                    consent_action_receipt_id, learner_subject_id, idempotency_key_digest,
                    request_digest, result_policy_revision, created_at)
                values (?, ?, ?, ?, 1, current_timestamp)
                """, UUID.randomUUID(), learner, keyDigest, "c".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertAppendOnlyFactViolation(() -> jdbcTemplate.update(
                "update consent_action_receipt set result_policy_revision = 2 where consent_action_receipt_id = ?", receipt));
        assertAppendOnlyFactViolation(() -> jdbcTemplate.update(
                "delete from consent_action_receipt where consent_action_receipt_id = ?", receipt));
        jdbcTemplate.update("update learner_subject set status = 'DISABLED' where learner_subject_id = ?", learner);
        assertAuthorityGateViolation(() -> jdbcTemplate.update("""
                insert into consent_action_receipt (
                    consent_action_receipt_id, learner_subject_id, idempotency_key_digest,
                    request_digest, result_policy_revision, created_at)
                values (?, ?, ?, ?, 1, current_timestamp)
                """, UUID.randomUUID(), learner, "d".repeat(64), "e".repeat(64)));
    }

    @Test
    void rejectsMemoryTransitionUpdateAndDeleteAtTheDatabaseLayer() {
        var learner = insertLearner();
        var assertion = insertAssertion(learner);
        var version = insertMemoryVersion(assertion, learner);
        var transition = insertMemoryTransition(assertion, version, learner);

        assertAppendOnlyViolation(() -> jdbcTemplate.update(
                "update memory_transition set reason_code = 'CHANGED' where memory_transition_id = ?", transition));
        assertAppendOnlyViolation(() -> jdbcTemplate.update(
                "delete from memory_transition where memory_transition_id = ?", transition));
    }

    @Test
    void allM1FactTablesHaveAppendOnlyMutationTriggers() {
        assertThat(appendOnlyTriggerTables()).containsExactlyInAnyOrder(
                "interaction_event",
                "memory_candidate",
                "governance_decision",
                "memory_version",
                "memory_transition",
                "memory_source_link",
                "memory_relation_event",
                "memory_audit_event");
    }

    @Test
    void rejectsInteractionEventAndMemoryVersionUpdateAndDeleteAtTheDatabaseLayer() {
        var learner = insertLearner();
        var event = insertInteractionEvent(learner);
        var assertion = insertAssertion(learner);
        var version = insertMemoryVersion(assertion, learner);

        assertAppendOnlyFactViolation(() -> jdbcTemplate.update(
                "update interaction_event set trace_id = 'changed' where event_id = ? and schema_version = 'v1'",
                event));
        assertAppendOnlyFactViolation(() -> jdbcTemplate.update(
                "delete from interaction_event where event_id = ? and schema_version = 'v1'", event));
        assertAppendOnlyFactViolation(() -> jdbcTemplate.update(
                "update memory_version set privacy_level = 'HIGH' where memory_version_id = ?", version));
        assertAppendOnlyFactViolation(() -> jdbcTemplate.update(
                "delete from memory_version where memory_version_id = ?", version));
    }

    @Test
    void rejectsCandidateAndSourceLinkThatCrossLearnerEventBoundaries() {
        var learnerA = insertLearner();
        var learnerB = insertLearner();
        var eventB = insertInteractionEvent(learnerB);
        var assertionA = insertAssertion(learnerA);
        var versionA = insertMemoryVersion(assertionA, learnerA);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into memory_candidate (
                    candidate_id, learner_subject_id, event_id, event_schema_version,
                    memory_type, status, privacy_level, candidate_ciphertext,
                    candidate_digest, source_count, created_at
                ) values (?, ?, ?, 'v1', 'PREFERENCE', 'PENDING', 'LOW', ?, ?, 1, current_timestamp)
                """, UUID.randomUUID(), learnerA, eventB, new byte[] {1}, digest("candidate")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into memory_source_link (
                    memory_source_link_id, memory_version_id, learner_subject_id,
                    source_event_id, source_schema_version, source_kind, created_at
                ) values (?, ?, ?, ?, 'v1', 'INTERACTION_EVENT', current_timestamp)
                """, UUID.randomUUID(), versionA, learnerA, eventB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsTransitionAndHeadThatUseAnotherAssertionVersionForTheSameLearner() {
        var learner = insertLearner();
        var assertionA = insertAssertion(learner);
        var assertionB = insertAssertion(learner);
        var versionB = insertMemoryVersion(assertionB, learner);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into memory_transition (
                    memory_transition_id, memory_assertion_id, learner_subject_id,
                    from_memory_version_id, to_memory_version_id, transition_type,
                    reason_code, transitioned_at
                ) values (?, ?, ?, null, ?, 'ACTIVATE', 'TEST', current_timestamp)
                """, UUID.randomUUID(), assertionA, learner, versionB))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into memory_head_projection (
                    memory_assertion_id, learner_subject_id, memory_version_id,
                    status, memory_epoch, updated_at
                ) values (?, ?, ?, 'ACTIVE', 1, current_timestamp)
                """, assertionA, learner, versionB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v9ProvidesOnlyProtectedPayloadMetadataAndRejectsUnbackedProtectedValues() {
        assertThat(columnNames("interaction_event")).contains(
                "payload_key_reference", "payload_nonce", "payload_algorithm", "payload_crypto_version");
        assertThat(columnNames("memory_candidate")).contains(
                "candidate_key_reference", "candidate_nonce", "candidate_algorithm", "candidate_crypto_version");
        assertThat(columnNames("memory_version")).contains(
                "protected_value_ciphertext", "protected_value_nonce", "protected_value_key_reference",
                "protected_value_algorithm", "protected_value_crypto_version");

        var learner = insertLearner();
        var assertion = insertAssertion(learner);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into memory_version (
                    memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                    status, value_json, valid_from, valid_until, recorded_at, recorded_until,
                    confidence, stability_score, privacy_level, consent_revision, created_at
                ) values (?, ?, ?, 1, 'ACTIVE', null, current_timestamp, null,
                    current_timestamp, null, 0.9, 0.9, 'SENSITIVE', 0, current_timestamp)
                """, UUID.randomUUID(), assertion, learner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v9RejectsProtectedPlaintextAndIncompleteSourceOrCandidateEnvelopes() {
        var learner = insertLearner();
        var assertion = insertAssertion(learner);
        var event = insertInteractionEvent(learner);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into interaction_event (
                    event_id, schema_version, learner_subject_id, session_id, event_type,
                    occurred_at, received_at, privacy_level, consent_revision,
                    payload_ciphertext, payload_digest, trace_id
                ) values (?, 'v1', ?, 'session', 'SENSITIVE_EVENT', current_timestamp,
                    current_timestamp, 'SENSITIVE', 0, ?, ?, 'trace')
                """, UUID.randomUUID(), learner, "source plaintext".getBytes(), digest("sensitive-source")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into memory_candidate (
                    candidate_id, learner_subject_id, event_id, event_schema_version,
                    memory_type, status, privacy_level, candidate_ciphertext,
                    candidate_digest, source_count, created_at
                ) values (?, ?, ?, 'v1', 'PREFERENCE', 'PENDING', 'SENSITIVE', ?, ?, 1, current_timestamp)
                """, UUID.randomUUID(), learner, event, "candidate plaintext".getBytes(), digest("sensitive-candidate")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into memory_version (
                    memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                    status, value_json, valid_from, valid_until, recorded_at, recorded_until,
                    confidence, stability_score, privacy_level, consent_revision, created_at
                ) values (?, ?, ?, 1, 'ACTIVE', '{"value":"protected plaintext"}'::jsonb,
                    current_timestamp, null, current_timestamp, null, 0.9, 0.9, 'HIGH', 0, current_timestamp)
                """, UUID.randomUUID(), assertion, learner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v10RequiresScopeConsistentForgetEvidenceAndOneExecutionPerRequest() {
        var learner = insertLearner();
        var assertion = insertAssertion(learner);
        var full = UUID.randomUUID();
        var assertionRequest = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at)
                values (?, ?, ?, 'FULL', ?, 'BLOCKED', current_timestamp)
                """, UUID.randomUUID(), learner, assertion, digest("invalid-full")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at)
                values (?, ?, null, 'ASSERTION', ?, 'BLOCKED', current_timestamp)
                """, UUID.randomUUID(), learner, digest("invalid-assertion")))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("""
                insert into forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at)
                values (?, ?, null, 'FULL', ?, 'BLOCKED', current_timestamp)
                """, full, learner, digest("full"));
        jdbcTemplate.update("""
                insert into forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at)
                values (?, ?, ?, 'ASSERTION', ?, 'BLOCKED', current_timestamp)
                """, assertionRequest, learner, assertion, digest("assertion"));
        jdbcTemplate.update("insert into forget_execution (forget_execution_id, request_id, payload_references_removed, executed_at) values (?, ?, 0, current_timestamp)", UUID.randomUUID(), full);
        assertThatThrownBy(() -> jdbcTemplate.update("insert into forget_execution (forget_execution_id, request_id, payload_references_removed, executed_at) values (?, ?, 0, current_timestamp)", UUID.randomUUID(), full))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v10AllowsAtMostOneFullForgetRequestPerLearner() {
        var learner = insertLearner();
        jdbcTemplate.update("""
                insert into forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at)
                values (?, ?, null, 'FULL', ?, 'BLOCKED', current_timestamp)
                """, UUID.randomUUID(), learner, digest("first-full"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at)
                values (?, ?, null, 'FULL', ?, 'BLOCKED', current_timestamp)
                """, UUID.randomUUID(), learner, digest("second-full")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v10GatesEveryAuthorityWriterAndRejectsWritesForDisabledSubjects() {
        assertThat(authorityGateTriggerTables()).containsExactlyInAnyOrder(
                "consent_policy_version", "interaction_event", "memory_candidate", "governance_decision",
                "memory_assertion", "memory_version", "memory_transition", "memory_head_projection",
                "memory_source_link", "memory_embedding", "memory_relation_event", "learner_dek_envelope",
                "interaction_payload_key_envelope", "consent_action_receipt");

        var learner = insertLearner();
        var event = insertInteractionEvent(learner);
        var assertion = insertAssertion(learner);
        var version = insertMemoryVersion(assertion, learner);
        jdbcTemplate.update("insert into memory_head_projection (memory_assertion_id, learner_subject_id, memory_version_id, status, memory_epoch, updated_at) values (?, ?, ?, 'ACTIVE', 0, current_timestamp)", assertion, learner, version);
        jdbcTemplate.update("update learner_subject set status = 'DISABLED' where learner_subject_id = ?", learner);

        assertAuthorityGateViolation(() -> jdbcTemplate.update("""
                insert into interaction_event (event_id, schema_version, learner_subject_id, session_id, event_type,
                    occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext, payload_digest, trace_id)
                values (?, 'v1', ?, 'session', 'LATE', current_timestamp, current_timestamp, 'LOW', 0, null, ?, 'trace')
                """, UUID.randomUUID(), learner, digest("late-event")));
        assertAuthorityGateViolation(() -> jdbcTemplate.update("""
                insert into memory_candidate (candidate_id, learner_subject_id, event_id, event_schema_version,
                    memory_type, status, privacy_level, candidate_ciphertext, candidate_digest, source_count, created_at)
                values (?, ?, ?, 'v1', 'PREFERENCE', 'PENDING', 'LOW', ?, ?, 1, current_timestamp)
                """, UUID.randomUUID(), learner, event, new byte[] {1}, digest("late-candidate")));
        assertAuthorityGateViolation(() -> jdbcTemplate.update("update memory_head_projection set updated_at = current_timestamp where memory_assertion_id = ?", assertion));
        assertAuthorityGateViolation(() -> jdbcTemplate.update("""
                insert into learner_dek_envelope (learner_subject_id, dek_key_reference, wrapped_dek, wrap_key_reference, created_at)
                values (?, ?, ?, ?, current_timestamp)
                """, learner, "k-" + "1".repeat(32), new byte[] {1}, "k-" + "2".repeat(32)));
    }

    @Test
    void v10AuthorityGateWaitsForFullLockThenRejectsTheStaleWriter() throws Exception {
        var learner = insertLearner();
        DataSource dataSource = jdbcTemplate.getDataSource();
        try (var full = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            full.setAutoCommit(false);
            try (var statement = full.prepareStatement("select learner_subject_id from learner_subject where learner_subject_id = ? for update")) {
                statement.setObject(1, learner);
                statement.executeQuery();
            }
            var writer = pool.submit(() -> {
                try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                        insert into learner_dek_envelope (learner_subject_id, dek_key_reference, wrapped_dek, wrap_key_reference, created_at)
                        values (?, ?, ?, ?, current_timestamp)
                        """)) {
                    statement.setObject(1, learner);
                    statement.setString(2, "k-" + "3".repeat(32));
                    statement.setBytes(3, new byte[] {1});
                    statement.setString(4, "k-" + "4".repeat(32));
                    statement.executeUpdate();
                }
                return null;
            });
            assertThat(writer.isDone()).isFalse();
            Thread.sleep(150);
            assertThat(writer.isDone()).isFalse();
            try (var statement = full.prepareStatement("update learner_subject set status = 'DISABLED' where learner_subject_id = ?")) {
                statement.setObject(1, learner);
                statement.executeUpdate();
            }
            full.commit();
            assertThatThrownBy(() -> writer.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    void v10RejectsTombstonesWhoseRequestOrAssertionBelongsToAnotherLearner() {
        var learnerA = insertLearner();
        var learnerB = insertLearner();
        var assertionA = insertAssertion(learnerA);
        var assertionB = insertAssertion(learnerB);
        var request = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into forget_request (forget_request_id, learner_subject_id, memory_assertion_id, scope, idempotency_key_digest, state, requested_at)
                values (?, ?, ?, 'ASSERTION', ?, 'BLOCKED', current_timestamp)
                """, request, learnerA, assertionA, digest("request-a"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into forget_tombstone (forget_tombstone_id, learner_subject_id, memory_assertion_id, scope, request_id, created_at)
                values (?, ?, ?, 'ASSERTION', ?, current_timestamp)
                """, UUID.randomUUID(), learnerB, assertionB, request))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into forget_tombstone (forget_tombstone_id, learner_subject_id, memory_assertion_id, scope, request_id, created_at)
                values (?, ?, ?, 'ASSERTION', ?, current_timestamp)
                """, UUID.randomUUID(), learnerA, assertionB, request))
                .isInstanceOf(DataAccessException.class);
    }

    private Set<String> tableNames() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "select tablename from pg_tables where schemaname = current_schema()", String.class));
    }

    private Set<String> extensionNames() {
        return Set.copyOf(jdbcTemplate.queryForList("select extname from pg_extension", String.class));
    }

    private Set<String> columnNames(String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = current_schema() and table_name = ?
                """, String.class, tableName));
    }

    private Set<String> flywayVersions() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success = true", String.class));
    }

    private String embeddingColumnType() {
        return jdbcTemplate.queryForObject("""
                select format_type(attribute.atttypid, attribute.atttypmod)
                from pg_attribute attribute
                join pg_class relation on relation.oid = attribute.attrelid
                where relation.relname = 'memory_embedding'
                  and attribute.attname = 'embedding'
                  and attribute.attnum > 0
                  and not attribute.attisdropped
                """, String.class);
    }

    private String primaryKeyColumns(String tableName) {
        return jdbcTemplate.queryForObject("""
                select string_agg(attribute.attname, ',' order by key_column.ordinal_position)
                from pg_constraint table_constraint
                join pg_class relation on relation.oid = table_constraint.conrelid
                cross join lateral unnest(table_constraint.conkey) with ordinality
                    as key_column(attnum, ordinal_position)
                join pg_attribute attribute
                    on attribute.attrelid = relation.oid and attribute.attnum = key_column.attnum
                where table_constraint.contype = 'p' and relation.relname = ?
                group by table_constraint.conname
                """, String.class, tableName);
    }

    private Set<String> appendOnlyTriggerTables() {
        return Set.copyOf(jdbcTemplate.queryForList("""
                select distinct relation.relname
                from pg_trigger table_trigger
                join pg_class relation on relation.oid = table_trigger.tgrelid
                join pg_proc trigger_function on trigger_function.oid = table_trigger.tgfoid
                where not table_trigger.tgisinternal
                  and trigger_function.proname = 'prevent_append_only_fact_mutation'
                """, String.class));
    }

    private Set<String> authorityGateTriggerTables() {
        return Set.copyOf(jdbcTemplate.queryForList("""
                select distinct relation.relname
                from pg_trigger table_trigger
                join pg_class relation on relation.oid = table_trigger.tgrelid
                join pg_proc trigger_function on trigger_function.oid = table_trigger.tgfoid
                where not table_trigger.tgisinternal
                  and trigger_function.proname like '%authority_write_gate'
                """, String.class));
    }

    private Set<String> indexNames() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = current_schema()", String.class));
    }

    private List<String> indexDefinitions() {
        return jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where schemaname = current_schema()", String.class);
    }

    private UUID insertLearner() {
        var learner = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into learner_subject (learner_subject_id, subject_hash, status, created_at)
                values (?, ?, 'ACTIVE', current_timestamp)
                """, learner, "subject-" + learner);
        return learner;
    }

    private UUID insertInteractionEvent(UUID learner) {
        var event = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into interaction_event (
                    event_id, schema_version, learner_subject_id, session_id, event_type,
                    occurred_at, received_at, privacy_level, consent_revision,
                    payload_ciphertext, payload_digest, trace_id
                ) values (?, 'v1', ?, 'session', 'TEST_EVENT', current_timestamp,
                    current_timestamp, 'LOW', 0, null, ?, 'trace')
                """, event, learner, digest("event"));
        return event;
    }

    private UUID insertAssertion(UUID learner) {
        var assertion = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into memory_assertion (
                    memory_assertion_id, learner_subject_id, assertion_key, memory_type, created_at
                ) values (?, ?, ?, 'PREFERENCE', current_timestamp)
                """, assertion, learner, "assertion-" + assertion);
        return assertion;
    }

    private UUID insertMemoryVersion(UUID assertion, UUID learner) {
        var version = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into memory_version (
                    memory_version_id, memory_assertion_id, learner_subject_id, version_sequence,
                    status, value_json, valid_from, valid_until, recorded_at, recorded_until,
                    confidence, stability_score, privacy_level, consent_revision, created_at
                ) values (?, ?, ?, 1, 'ACTIVE', '{}'::jsonb, current_timestamp, null,
                    current_timestamp, null, 0.9000, 0.9000, 'LOW', 0, current_timestamp)
                """, version, assertion, learner);
        return version;
    }

    private UUID insertMemoryTransition(UUID assertion, UUID version, UUID learner) {
        var transition = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into memory_transition (
                    memory_transition_id, memory_assertion_id, learner_subject_id,
                    from_memory_version_id, to_memory_version_id, transition_type,
                    reason_code, transitioned_at
                ) values (?, ?, ?, null, ?, 'ACTIVATE', 'TEST', current_timestamp)
                """, transition, assertion, learner, version);
        return transition;
    }

    private String digest(String value) {
        return (value + "0".repeat(64)).substring(0, 64);
    }

    private void assertAppendOnlyViolation(ThrowingCallable operation) {
        var failure = catchThrowable(operation);
        assertThat(failure)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only fact row mutation is forbidden");
        assertThat(sqlState(failure)).isEqualTo("55000");
    }

    private void assertAppendOnlyFactViolation(ThrowingCallable operation) {
        var failure = catchThrowable(operation);
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(sqlState(failure)).isEqualTo("55000");
    }

    private void assertAuthorityGateViolation(ThrowingCallable operation) {
        var failure = catchThrowable(operation);
        assertThat(failure).isInstanceOf(DataAccessException.class).hasMessageContaining("authority write gate is closed");
        assertThat(sqlState(failure)).isEqualTo("55000");
    }

    private String sqlState(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                return postgresException.getSQLState();
            }
        }
        return null;
    }
}
