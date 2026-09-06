package com.yilan.memory.application.migration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** Applies the migration-only validation policy before any shadow record can be staged. */
public final class LegacyRecordMapper {

    private static final Set<String> MEMORY_KINDS = Set.of("memory_candidate", "structured_memory");
    private static final Set<String> MEMORY_TYPES = Set.of(
            "preference", "mastery", "misconception", "reflection");
    private static final Set<String> REJECTION_REASONS = Set.of(
            "MISSING_SOURCE_REFERENCE", "UNKNOWN_SOURCE_REFERENCE", "PROHIBITED_MEMORY_TYPE", "SOURCE_REJECTED");
    private static final Map<String, String> TABLE_KINDS = Map.of(
            "interaction_events", "interaction_event",
            "memory_audit_logs", "memory_audit_log",
            "memory_candidates", "memory_candidate",
            "memory_relations", "memory_relation",
            "semantic_memory_entries", "semantic_memory_entry",
            "structured_memories", "structured_memory");

    public ValidationResult validate(MigrationBundleReader.MigrationBundle bundle) {
        var recordsByKey = new HashMap<String, MigrationBundleReader.CanonicalRecord>();
        for (var record : bundle.records()) {
            if (recordsByKey.put(record.legacyKey(), record) != null) {
                throw new IllegalArgumentException("duplicate migration legacy key");
            }
            validateShape(record);
        }
        var accepted = new HashSet<String>();
        var rejected = new HashMap<String, String>();
        for (var record : bundle.records()) {
            var actual = rejectionReason(record, recordsByKey);
            if (record.expectedRejection() == null) {
                if (actual != null) {
                    throw new IllegalArgumentException("critical migration mismatch for " + record.legacyKey() + ": " + actual);
                }
                accepted.add(record.legacyKey());
            } else {
                if (!REJECTION_REASONS.contains(record.expectedRejection()) || !record.expectedRejection().equals(actual)) {
                    throw new IllegalArgumentException("expected legacy rejection does not match record: " + record.legacyKey());
                }
                rejected.put(record.legacyKey(), actual);
            }
        }
        validateSubjectClosure(accepted, recordsByKey);
        var activeVersions = new HashSet<String>();
        for (var key : accepted) {
            var record = recordsByKey.get(key);
            if ("structured_memory".equals(record.kind()) && "active".equals(record.status())) {
                var versionKey = record.subject() + "|" + record.type();
                if (!activeVersions.add(versionKey)) {
                    throw new IllegalArgumentException("duplicate active migration version");
                }
            }
        }
        return new ValidationResult(Set.copyOf(accepted), Map.copyOf(rejected));
    }

    private static void validateSubjectClosure(
            Set<String> accepted, Map<String, MigrationBundleReader.CanonicalRecord> recordsByKey) {
        for (var key : accepted) {
            var record = recordsByKey.get(key);
            var referencedSubjects = new HashSet<String>();
            for (var sourceReference : record.sourceRefs()) {
                if (!accepted.contains(sourceReference)) {
                    continue;
                }
                var sourceSubject = recordsByKey.get(sourceReference).subject();
                if (sourceSubject != null) {
                    referencedSubjects.add(sourceSubject);
                }
            }
            if (record.subject() != null && referencedSubjects.stream().anyMatch(subject -> !subject.equals(record.subject()))) {
                throw new IllegalArgumentException("critical migration subject closure mismatch");
            }
            if (record.subject() == null && referencedSubjects.size() > 1) {
                throw new IllegalArgumentException("critical migration cross-subject source closure");
            }
        }
    }

    private static void validateShape(MigrationBundleReader.CanonicalRecord record) {
        var table = MigrationBundleReader.tableFor(record.legacyKey());
        if (record.kind() == null || record.kind().isBlank() || record.legacyKey() == null || record.legacyKey().isBlank()
                || record.status() == null || record.status().isBlank() || record.privacy() == null || record.privacy().isBlank()
                || record.payloadDigest() == null || !record.payloadDigest().matches("[0-9a-f]{64}")
                || !record.kind().equals(TABLE_KINDS.get(table))) {
            throw new IllegalArgumentException("migration record shape is invalid");
        }
        if (("interaction_event".equals(record.kind()) || MEMORY_KINDS.contains(record.kind()))
                && (record.subject() == null || record.subject().isBlank())) {
            throw new IllegalArgumentException("migration subject binding is invalid");
        }
        if (!sha256(record.payloadJson()).equals(record.payloadDigest())) {
            throw new IllegalArgumentException("migration payload digest mismatch");
        }
    }

    private static String rejectionReason(
            MigrationBundleReader.CanonicalRecord record,
            Map<String, MigrationBundleReader.CanonicalRecord> recordsByKey) {
        if (MEMORY_KINDS.contains(record.kind()) && !MEMORY_TYPES.contains(record.type())) {
            return "PROHIBITED_MEMORY_TYPE";
        }
        if ("memory_candidate".equals(record.kind()) && record.sourceRefs().isEmpty()) {
            return "MISSING_SOURCE_REFERENCE";
        }
        for (var sourceReference : record.sourceRefs()) {
            var source = recordsByKey.get(sourceReference);
            if (source == null) {
                return "UNKNOWN_SOURCE_REFERENCE";
            }
            if (source.expectedRejection() != null) {
                return "SOURCE_REJECTED";
            }
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record ValidationResult(Set<String> acceptedLegacyKeys, Map<String, String> rejectedLegacyKeys) {
        public ValidationResult {
            acceptedLegacyKeys = Set.copyOf(acceptedLegacyKeys);
            rejectedLegacyKeys = Map.copyOf(rejectedLegacyKeys);
        }
    }
}
