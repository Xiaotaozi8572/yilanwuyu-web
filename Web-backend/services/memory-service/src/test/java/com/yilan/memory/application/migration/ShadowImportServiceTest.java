package com.yilan.memory.application.migration;

import com.yilan.memory.support.PostgresIntegrationTest;
import com.yilan.memory.adapter.postgres.JdbcShadowImportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ShadowImportServiceTest extends PostgresIntegrationTest {

    private static final String EMPTY_JSON_DIGEST = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    private static final byte[] TEST_KEY = new byte[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
    };

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void persists_global_legacy_key_idempotency_across_new_service_instances() {
        var legacyKey = "structured_memories:persistent-" + UUID.randomUUID();
        var subject = "shadow-subject-" + UUID.randomUUID();
        var bundle = bundle(record(legacyKey, subject, "{\"value\":\"PRIVATE_SHADOW_SENTINEL\"}"));

        var first = shadowService().importShadow(bundle);
        var second = shadowService().importShadow(bundle);

        assertThat(first.newlyImported()).isEqualTo(1);
        assertThat(second.duplicate()).isEqualTo(1);
        assertThat(second.newlyImported()).isZero();
        assertThat(jdbcClient.sql("select count(*) from migration_shadow_record where legacy_key = :legacyKey")
                .param("legacyKey", legacyKey).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void different_global_legacy_digest_rolls_back_all_new_shadow_rows() {
        var collidingKey = "structured_memories:collision-" + UUID.randomUUID();
        var newKey = "structured_memories:new-" + UUID.randomUUID();
        var subject = "shadow-subject-" + UUID.randomUUID();
        var initial = bundle(record(collidingKey, subject, "{\"value\":\"first\"}"));
        shadowService().importShadow(initial);
        var importsBeforeConflict = jdbcClient.sql("select count(*) from migration_shadow_import").query(Long.class).single();

        var conflicting = bundle(
                record(newKey, subject, "{\"value\":\"new-row\"}"),
                record(collidingKey, subject, "{\"value\":\"different-digest\"}"));

        assertThatThrownBy(() -> shadowService().importShadow(conflicting))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcClient.sql("select count(*) from migration_shadow_record where legacy_key in (:first, :second)")
                .param("first", collidingKey).param("second", newKey).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from migration_shadow_import").query(Long.class).single())
                .isEqualTo(importsBeforeConflict);
    }

    @Test
    void prohibits_aviation_and_non_java_taxonomy_before_pending_shadow_routing() {
        var aviation = record("structured_memories:aviation-" + UUID.randomUUID(), "subject-a", "{}", "aviation_fact",
                "PROHIBITED_MEMORY_TYPE");
        var policy = record("memory_candidates:policy-" + UUID.randomUUID(), "subject-b", "{}", "policy",
                "PROHIBITED_MEMORY_TYPE");

        var validation = new LegacyRecordMapper().validate(bundle(aviation, policy));

        assertThat(validation.acceptedLegacyKeys()).isEmpty();
        assertThat(validation.rejectedLegacyKeys()).containsOnly(
                Map.entry(aviation.legacyKey(), "PROHIBITED_MEMORY_TYPE"),
                Map.entry(policy.legacyKey(), "PROHIBITED_MEMORY_TYPE"));
    }

    @Test
    void imports_python_wire_format_once_and_never_exposes_shadow_records_to_resolution() throws Exception {
        var bundle = Files.createTempFile("python-migration-bundle", ".json");
        Files.write(bundle, Base64.getDecoder().decode(PythonBundleVector.BASE64));
        var service = shadowService();

        var first = service.importShadow(new MigrationBundleReader().read(bundle, TEST_KEY));
        var second = service.importShadow(new MigrationBundleReader().read(bundle, TEST_KEY));

        assertThat(first.accepted()).isZero();
        assertThat(first.rejected()).isEqualTo(1);
        assertThat(second.accepted()).isZero();
        assertThat(second.duplicate()).isEqualTo(1);
        assertThat(second.newlyImported()).isZero();
        assertThat(service.resolveForContext("subject-hash-test-1")).isEmpty();
        assertThat(jdbcClient.sql("select shadow_route from migration_shadow_record where legacy_key = :legacyKey")
                .param("legacyKey", "structured_memories:memory-1").query(String.class).single())
                .isEqualTo("EXPLICIT_LEGACY_REJECTION");
        assertThat(ShadowImportService.parseCommand(List.of(
                "memory-import", "--bundle", bundle.toString(), "--key-env", "TEST_KEY", "--mode", "shadow")))
                .isEqualTo(new ShadowImportService.ImportCommand(bundle, "TEST_KEY", "shadow"));
        assertThatThrownBy(() -> ShadowImportService.parseCommand(List.of(
                "memory-import", "--bundle", bundle.toString(), "--key-env", "TEST_KEY", "--mode", "remote")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void critical_source_mismatches_fail_before_any_shadow_write() {
        var service = shadowService();
        var before = service.shadowRecordCount();
        var record = new MigrationBundleReader.CanonicalRecord(
                "structured_memory", "structured_memories:memory-1", "subject-a", List.of("interaction_events:event-1"),
                "preference", "{}", null, null, null, null, "active", null, "unknown",
                EMPTY_JSON_DIGEST, null);
        var invalidSubject = new MigrationBundleReader.MigrationBundle(
                new MigrationBundleReader.Manifest("legacy-memory-export/v1", "schema", "baseline",
                        Map.of("structured_memories", 1), Map.of("structured_memories", 1), Map.of(), 1,
                        "0".repeat(64), 1),
                List.of(record));

        assertThatThrownBy(() -> service.importShadow(invalidSubject)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.shadowRecordCount()).isEqualTo(before);
    }

    @Test
    void cross_subject_source_closure_fails_before_any_shadow_write() {
        var service = shadowService();
        var before = service.shadowRecordCount();
        var event = new MigrationBundleReader.CanonicalRecord(
                "interaction_event", "interaction_events:event-1", "subject-a", List.of(), "preference_signal", "{}",
                null, null, null, null, "observed", null, "unknown", EMPTY_JSON_DIGEST, null);
        var candidate = new MigrationBundleReader.CanonicalRecord(
                "memory_candidate", "memory_candidates:candidate-1", "subject-b", List.of("interaction_events:event-1"),
                "preference", "{}", null, null, null, null, "candidate", null, "unknown", EMPTY_JSON_DIGEST, null);
        var crossSubject = new MigrationBundleReader.MigrationBundle(
                new MigrationBundleReader.Manifest("legacy-memory-export/v1", "schema", "baseline",
                        Map.of("interaction_events", 1, "memory_candidates", 1),
                        Map.of("interaction_events", 1, "memory_candidates", 1),
                        Map.of("interaction_events", 0, "memory_candidates", 0), 2, "0".repeat(64), 1),
                List.of(event, candidate));

        assertThatThrownBy(() -> service.importShadow(crossSubject)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.shadowRecordCount()).isEqualTo(before);
    }

    @Test
    void duplicate_active_versions_fail_before_any_shadow_write() {
        var service = shadowService();
        var before = service.shadowRecordCount();
        var first = new MigrationBundleReader.CanonicalRecord(
                "structured_memory", "structured_memories:memory-1", "subject-a", List.of(), "preference", "{}",
                null, null, null, null, "active", null, "unknown", EMPTY_JSON_DIGEST, null);
        var second = new MigrationBundleReader.CanonicalRecord(
                "structured_memory", "structured_memories:memory-2", "subject-a", List.of(), "preference", "{}",
                null, null, null, null, "active", null, "unknown", EMPTY_JSON_DIGEST, null);
        var duplicateActive = new MigrationBundleReader.MigrationBundle(
                new MigrationBundleReader.Manifest("legacy-memory-export/v1", "schema", "baseline",
                        Map.of("structured_memories", 2), Map.of("structured_memories", 2), Map.of(), 2,
                        "0".repeat(64), 1),
                List.of(first, second));

        assertThatThrownBy(() -> service.importShadow(duplicateActive)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.shadowRecordCount()).isEqualTo(before);
    }

    @Test
    void shadow_storage_is_append_only_ciphertext_only_and_does_not_write_authority_state() {
        var legacyKey = "structured_memories:immutable-" + UUID.randomUUID();
        var authorityBefore = authorityWriteCounts();
        shadowService().importShadow(bundle(record(
                legacyKey, "shadow-subject-" + UUID.randomUUID(), "{\"secret\":\"PRIVATE_SHADOW_SENTINEL\"}")));

        var columns = jdbcClient.sql("""
                        select column_name from information_schema.columns
                        where table_schema = 'public' and table_name = 'migration_shadow_record'
                        """).query(String.class).list();
        var frame = jdbcClient.sql("select encode(payload_frame, 'base64') from migration_shadow_record where legacy_key = :legacyKey")
                .param("legacyKey", legacyKey).query(String.class).single();
        var recordId = jdbcClient.sql("select migration_shadow_record_id from migration_shadow_record where legacy_key = :legacyKey")
                .param("legacyKey", legacyKey).query(UUID.class).single();
        var importId = jdbcClient.sql("select migration_shadow_import_id from migration_shadow_record where legacy_key = :legacyKey")
                .param("legacyKey", legacyKey).query(UUID.class).single();

        assertThat(columns).doesNotContain("payload_json", "payload_plaintext", "migration_key");
        assertThat(columns).contains("payload_frame", "legacy_status");
        assertThat(frame).doesNotContain("PRIVATE_SHADOW_SENTINEL");
        assertThatThrownBy(() -> jdbcClient.sql("update migration_shadow_record set legacy_key = :changed where migration_shadow_record_id = :id")
                .param("changed", legacyKey + "-changed").param("id", recordId).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcClient.sql("delete from migration_shadow_import where migration_shadow_import_id = :id")
                .param("id", importId).update()).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(authorityWriteCounts()).isEqualTo(authorityBefore);
    }

    static final class PythonBundleVector {
        private PythonBundleVector() { }

        // Generated by the Python v1 serializer with TEST_KEY and a fixed test nonce.
        static final String BASE64 = "eyJjaHVua3MiOlt7ImNpcGhlcnRleHQiOiJQQ0MxZEt1RHEzL29ML1R1azlNV0dPKzZxeGFWSFRrWld4T004M2cyWWNZaktzQ0p3NjArdWhIY0Q0anI4MDFjc1NzRjV6KzExN05RK1Fnak9yT25vYmgxaERPSGxHSStVUkhuSWIzV1VieGgyb3hBV2tFMkVJMlU3S2tMcjlqNTZjSTNrOGd0NURqMWRWRFdhazhERE00VkY4aVhzRmYvS2FBNnFPK0pISGVaZmlLcTBiQ3FNSEcrSThSRStIYmdSWUNSUUZabDVqanBPN2NhbjcrR25Qcnk3UytFbXFFUjFScnd5LzJzcE12OHQ2Wk42czlSaGg3OXJLSVdwTlNLTVhhb3FsK1VnSWhqRTR5dy93S055N01ocXpOelZQTy9OTjI0dW0xcS81czZUN3ZPZ245blREVWl5M3lYSzE0eWtLS0JFYVVDN1pRaENGRVh2LzUydG5ma3lxaXQ2Q2tKUnJsRE5JQVVDV2p0Z0s3N3R1K3luMGdZVkdKdXdJTXFOR0RzS1l1ZytTK21XUWhyYWZQRDhiVG9LeXdnNHE1MDRSTW5OT0pUTG9lbTRoRUlwMElMaHRhU0dFVUNzbWxBcVhTaTZQQTRwM3NtMWdvRjZPNDZtajljMitQZWRodWxWTXJXUXZUQkMwZnFvbm9QaTU5MHNxV0FRcm15STNSQnl2YXl1dUlxNHY3OFhOd01TaUZtOHBFcFZwbjhRVHl3VkhTL01pakVsM1JxRHpVQnBySWxVSnFhcnllZGt0WEdLVVlzYzIvckRaR0N5cGZBdDRreldna0QzYW5MT3VBY256amFPWVQ0dVgxTC9oSWlwcmdrSGlLejA5RmxsdW5hUm9sSVl1T3hvc1N1eWVWTWZhZDVDa0I3MFJrcWV4ZmQiLCJub25jZSI6IkFBRUNBd1FGQmdjSUNRb0wiLCJzZXF1ZW5jZSI6MH1dLCJoZWFkZXIiOnsiYWNjZXB0ZWRfY291bnRzIjp7InN0cnVjdHVyZWRfbWVtb3JpZXMiOjB9LCJjaHVua19jb3VudCI6MSwiZm9ybWF0IjoibGVnYWN5LW1lbW9yeS1leHBvcnQvdjEiLCJyZWNvcmRfY291bnQiOjEsInJlY29yZHNfc2hhMjU2IjoiMTY4YTBjZmI0OTYxMzA2Y2MyNWZmOTc2NmM2ZDNhY2Q1MjM3ODA2OWYwMTg2ZDcwNTcyMTc5MmY1OWUzMjNiOCIsInJlamVjdGlvbl9jb3VudHMiOnsic3RydWN0dXJlZF9tZW1vcmllcyI6MX0sInNjaGVtYV9maW5nZXJwcmludCI6ImRmMGFkNmU0Mzg4MGYwOWM5MGViZjk1ZjE5MTEwMTc4YWJhNjg5MGRmMDAxMGViZGE3NDg1MDI5ZTJiNTQzYjQiLCJzb3VyY2VfYmFzZWxpbmVfZGlnZXN0IjoiOGJhODQ5NmEyNTI1YWUxNzFmZmQxMDRkNjMyZGVkZTZlZjQxOGQ5Yjk1OTYyYTlkODhlMmZjZGJjOGQ0OGQyNCIsInNvdXJjZV9jb3VudHMiOnsic3RydWN0dXJlZF9tZW1vcmllcyI6MX19fQo=";
    }

    private static MigrationBundleReader.MigrationBundle bundle(MigrationBundleReader.CanonicalRecord... records) {
        var count = records.length;
        return new MigrationBundleReader.MigrationBundle(
                new MigrationBundleReader.Manifest(
                        "legacy-memory-export/v1", "0".repeat(64), "1".repeat(64),
                        Map.of("structured_memories", count), Map.of("structured_memories", count),
                        Map.of("structured_memories", 0), count, "2".repeat(64), 1),
                List.of(records));
    }

    private static MigrationBundleReader.CanonicalRecord record(String legacyKey, String subject, String payload) {
        return record(legacyKey, subject, payload, "preference", null);
    }

    private static MigrationBundleReader.CanonicalRecord record(
            String legacyKey, String subject, String payload, String type, String expectedRejection) {
        return new MigrationBundleReader.CanonicalRecord(
                legacyKey.startsWith("memory_candidates:") ? "memory_candidate" : "structured_memory",
                legacyKey, subject, List.of(), type, payload, null, null, null, null, "candidate", null, "unknown",
                sha256(payload), expectedRejection);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private ShadowImportService shadowService() {
        return new ShadowImportService(new JdbcShadowImportRepository(jdbcClient, transactionManager), TEST_KEY);
    }

    private Map<String, Long> authorityWriteCounts() {
        return Map.of(
                "memory_candidate", jdbcClient.sql("select count(*) from memory_candidate").query(Long.class).single(),
                "memory_version", jdbcClient.sql("select count(*) from memory_version").query(Long.class).single(),
                "transactional_outbox", jdbcClient.sql("select count(*) from transactional_outbox").query(Long.class).single(),
                "learner_epoch", jdbcClient.sql("select count(*) from learner_epoch").query(Long.class).single());
    }
}
