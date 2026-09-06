package com.yilan.memory.adapter.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yilan.memory.application.migration.ImportReconciliationReport;
import com.yilan.memory.application.migration.LegacyRecordMapper;
import com.yilan.memory.application.migration.MigrationBundleReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL-only, non-authoritative staging for a one-way migration handoff.
 * It never writes an authority table, resolver, outbox, or learner epoch.
 */
@Repository
public class JdbcShadowImportRepository {

    private static final String PENDING_ROUTE = "PENDING_AUTHORITY_GOVERNANCE";
    private static final String REJECTED_ROUTE = "EXPLICIT_LEGACY_REJECTION";
    private static final String SHADOW_VERSION = "migration-shadow/v1";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcShadowImportRepository(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public ImportReconciliationReport stage(
            MigrationBundleReader.MigrationBundle bundle,
            LegacyRecordMapper.ValidationResult validation,
            byte[] migrationKey) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(validation, "validation");
        requireKey(migrationKey);
        return Objects.requireNonNull(transactions.execute(status -> stageInSingleTransaction(bundle, validation, migrationKey)));
    }

    public long shadowRecordCount() {
        return jdbcClient.sql("SELECT count(*) FROM migration_shadow_record").query(Long.class).single();
    }

    private ImportReconciliationReport stageInSingleTransaction(
            MigrationBundleReader.MigrationBundle bundle,
            LegacyRecordMapper.ValidationResult validation,
            byte[] migrationKey) {
        lockLegacyKeys(bundle.records());

        var newRecords = new ArrayList<MigrationBundleReader.CanonicalRecord>();
        var duplicate = 0;
        for (var record : bundle.records()) {
            var existing = existingPayloadDigest(record.legacyKey());
            if (existing.isEmpty()) {
                newRecords.add(record);
            } else if (existing.orElseThrow().equals(record.payloadDigest())) {
                duplicate++;
            } else {
                throw new IllegalArgumentException("critical migration legacy-key payload digest conflict");
            }
        }

        var importId = UUID.randomUUID();
        var accepted = validation.acceptedLegacyKeys().size();
        var rejected = validation.rejectedLegacyKeys().size();
        var report = new ImportReconciliationReport(
                bundle.records().size(), accepted, rejected, duplicate, newRecords.size(), rejectionCounts(validation));
        insertImport(importId, bundle.manifest(), report);
        for (var record : newRecords) {
            insertRecord(importId, record, validation.rejectedLegacyKeys().get(record.legacyKey()), migrationKey);
        }
        return report;
    }

    private void lockLegacyKeys(List<MigrationBundleReader.CanonicalRecord> records) {
        records.stream().map(MigrationBundleReader.CanonicalRecord::legacyKey).sorted().forEach(legacyKey ->
                jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:legacyKey, 0))")
                        .param("legacyKey", legacyKey)
                        .query((resultSet, rowNumber) -> resultSet.getObject(1)).single());
    }

    private java.util.Optional<String> existingPayloadDigest(String legacyKey) {
        return jdbcClient.sql("SELECT payload_digest FROM migration_shadow_record WHERE legacy_key = :legacyKey")
                .param("legacyKey", legacyKey).query(String.class).optional();
    }

    private void insertImport(
            UUID importId, MigrationBundleReader.Manifest manifest, ImportReconciliationReport report) {
        jdbcClient.sql("""
                        INSERT INTO migration_shadow_import (
                            migration_shadow_import_id, manifest_fingerprint, source_total, accepted_count,
                            rejected_count, duplicate_count, newly_imported_count, created_at)
                        VALUES (:id, :fingerprint, :sourceTotal, :accepted, :rejected, :duplicate, :newlyImported, :createdAt)
                        """)
                .param("id", importId)
                .param("fingerprint", manifestFingerprint(manifest))
                .param("sourceTotal", report.sourceTotal())
                .param("accepted", report.accepted())
                .param("rejected", report.rejected())
                .param("duplicate", report.duplicate())
                .param("newlyImported", report.newlyImported())
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
    }

    private void insertRecord(
            UUID importId,
            MigrationBundleReader.CanonicalRecord record,
            String rejection,
            byte[] migrationKey) {
        var route = rejection == null ? PENDING_ROUTE : REJECTED_ROUTE;
        var memoryType = isMemoryRecord(record.kind()) && rejection == null ? record.type().toUpperCase(java.util.Locale.ROOT) : null;
        jdbcClient.sql("""
                        INSERT INTO migration_shadow_record (
                            migration_shadow_record_id, migration_shadow_import_id, legacy_key, pseudonymous_subject,
                            source_keys, record_kind, legacy_type, legacy_status, memory_type, shadow_route, rejection_code,
                            payload_digest, payload_frame, payload_algorithm, payload_crypto_version, created_at)
                        VALUES (
                            :id, :importId, :legacyKey, :subject, CAST(:sourceKeys AS jsonb), :recordKind,
                            :legacyType, :legacyStatus, :memoryType, :route, :rejection, :payloadDigest, :payloadFrame,
                            'AES-256-GCM', 'migration-shadow/v1', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("importId", importId)
                .param("legacyKey", record.legacyKey())
                .param("subject", record.subject())
                .param("sourceKeys", sourceKeys(record.sourceRefs()))
                .param("recordKind", record.kind())
                .param("legacyType", record.type())
                .param("legacyStatus", record.status())
                .param("memoryType", memoryType)
                .param("route", route)
                .param("rejection", rejection)
                .param("payloadDigest", record.payloadDigest())
                .param("payloadFrame", encryptPayload(record, migrationKey))
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
    }

    private byte[] encryptPayload(MigrationBundleReader.CanonicalRecord record, byte[] migrationKey) {
        var nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(migrationKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD((SHADOW_VERSION + "\n" + record.legacyKey() + "\n" + record.payloadDigest())
                    .getBytes(StandardCharsets.UTF_8));
            var ciphertext = cipher.doFinal(record.payloadJson().getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("migration shadow payload encryption failed", failure);
        }
    }

    private static Map<String, Integer> rejectionCounts(LegacyRecordMapper.ValidationResult validation) {
        var result = new java.util.HashMap<String, Integer>();
        validation.rejectedLegacyKeys().values().forEach(reason -> result.merge(reason, 1, Integer::sum));
        return Map.copyOf(result);
    }

    private String sourceKeys(List<String> sourceRefs) {
        try {
            return json.writeValueAsString(sourceRefs);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("migration source keys cannot be encoded", failure);
        }
    }

    private static boolean isMemoryRecord(String kind) {
        return "memory_candidate".equals(kind) || "structured_memory".equals(kind);
    }

    private static String manifestFingerprint(MigrationBundleReader.Manifest manifest) {
        var encoded = new StringBuilder(manifest.format())
                .append('\n').append(manifest.schemaFingerprint())
                .append('\n').append(manifest.sourceBaselineDigest())
                .append('\n').append(manifest.recordsSha256())
                .append('\n').append(manifest.recordCount())
                .append('\n').append(manifest.chunkCount());
        appendCounts(encoded, manifest.sourceCounts());
        appendCounts(encoded, manifest.acceptedCounts());
        appendCounts(encoded, manifest.rejectionCounts());
        return sha256(encoded.toString());
    }

    private static void appendCounts(StringBuilder target, Map<String, Integer> counts) {
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> target.append('\n').append(entry.getKey()).append('=').append(entry.getValue()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("migration key must be exactly 32 bytes");
        }
    }
}
