package com.yilan.memory.application.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads only the temporary, authenticated v1 migration hand-off format. */
public final class MigrationBundleReader {

    public static final String FORMAT = "legacy-memory-export/v1";
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> HEADER_FIELDS = Set.of(
            "accepted_counts", "chunk_count", "format", "record_count", "records_sha256",
            "rejection_counts", "schema_fingerprint", "source_baseline_digest", "source_counts");
    private static final Set<String> RECORD_FIELDS = Set.of(
            "confidence", "effective_at", "expected_rejection", "expires_at", "kind", "legacy_key",
            "payload", "payload_digest", "privacy", "source_at", "source_refs", "status", "subject",
            "system_at", "type");

    public MigrationBundle read(Path path, byte[] key) {
        try {
            return read(Files.readAllBytes(Objects.requireNonNull(path, "path")), key);
        } catch (IOException failure) {
            throw new IllegalArgumentException("migration bundle cannot be read", failure);
        }
    }

    public MigrationBundle read(byte[] encoded, byte[] key) {
        requireKey(key);
        try {
            var envelope = JSON.readTree(encoded);
            if (envelope == null || !envelope.isObject() || !fieldNames(envelope).equals(Set.of("chunks", "header"))) {
                throw new IllegalArgumentException("migration bundle envelope fields are invalid");
            }
            var header = envelope.path("header");
            var manifest = parseManifest(header);
            var chunks = envelope.path("chunks");
            if (!chunks.isArray() || chunks.size() != 1 || manifest.chunkCount() != 1) {
                throw new IllegalArgumentException("migration bundle chunk count is invalid");
            }
            var chunk = chunks.get(0);
            if (!chunk.isObject() || !fieldNames(chunk).equals(Set.of("ciphertext", "nonce", "sequence"))
                    || !chunk.path("sequence").canConvertToInt() || chunk.path("sequence").asInt() != 0) {
                throw new IllegalArgumentException("migration bundle chunk fields are invalid");
            }
            var nonce = base64(chunk.path("nonce").asText(), "migration bundle nonce");
            var ciphertext = base64(chunk.path("ciphertext").asText(), "migration bundle ciphertext");
            if (nonce.length != GCM_NONCE_BYTES || ciphertext.length == 0) {
                throw new IllegalArgumentException("migration bundle ciphertext is invalid");
            }
            var plaintext = decrypt(key, nonce, ciphertext, aad(header, 0));
            if (!sha256(plaintext).equals(manifest.recordsSha256())) {
                throw new IllegalArgumentException("migration bundle record digest is invalid");
            }
            var records = parseRecords(plaintext);
            validateCounts(manifest, records);
            return new MigrationBundle(manifest, records);
        } catch (IOException failure) {
            throw new IllegalArgumentException("migration bundle is not valid JSON", failure);
        }
    }

    public static byte[] keyFromEnvironment(Map<String, String> environment, String name) {
        if (environment == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("migration bundle key environment variable name is required");
        }
        var value = environment.get(name);
        if (value == null) {
            throw new IllegalArgumentException("migration bundle key environment variable is not set: " + name);
        }
        byte[] key;
        try {
            key = value.matches("[0-9a-fA-F]{64}")
                    ? HexFormat.of().parseHex(value)
                    : Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("migration bundle key must be base64 or 64-character hexadecimal", failure);
        }
        requireKey(key);
        return key;
    }

    private static Manifest parseManifest(JsonNode header) {
        if (!header.isObject() || !fieldNames(header).equals(HEADER_FIELDS)) {
            throw new IllegalArgumentException("migration bundle header fields are invalid");
        }
        if (!FORMAT.equals(header.path("format").asText())
                || !header.path("record_count").canConvertToInt()
                || !header.path("chunk_count").canConvertToInt()
                || !hexDigest(header.path("records_sha256").asText())
                || !hexDigest(header.path("schema_fingerprint").asText())
                || !hexDigest(header.path("source_baseline_digest").asText())) {
            throw new IllegalArgumentException("migration bundle header values are invalid");
        }
        return new Manifest(
                header.path("format").asText(),
                header.path("schema_fingerprint").asText(),
                header.path("source_baseline_digest").asText(),
                counts(header.path("source_counts")), counts(header.path("accepted_counts")),
                counts(header.path("rejection_counts")), header.path("record_count").asInt(),
                header.path("records_sha256").asText(), header.path("chunk_count").asInt());
    }

    private static List<CanonicalRecord> parseRecords(byte[] plaintext) {
        try {
            var text = new String(plaintext, StandardCharsets.UTF_8);
            if (text.isEmpty() || !text.endsWith("\n")) {
                throw new IllegalArgumentException("migration bundle JSONL is invalid");
            }
            var records = new ArrayList<CanonicalRecord>();
            for (var line : text.split("\\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                var node = JSON.readTree(line);
                if (node == null || !node.isObject() || !fieldNames(node).equals(RECORD_FIELDS)) {
                    throw new IllegalArgumentException("migration bundle record fields are invalid");
                }
                var sourceRefs = new ArrayList<String>();
                var refs = node.path("source_refs");
                if (!refs.isArray()) {
                    throw new IllegalArgumentException("migration bundle source references are invalid");
                }
                for (var reference : refs) {
                    if (!reference.isTextual() || reference.asText().isBlank()) {
                        throw new IllegalArgumentException("migration bundle source references are invalid");
                    }
                    sourceRefs.add(reference.asText());
                }
                records.add(new CanonicalRecord(
                        requiredText(node, "kind"), requiredText(node, "legacy_key"), nullableText(node, "subject"),
                        sourceRefs, nullableText(node, "type"), canonicalJson(node.path("payload")),
                        nullableText(node, "source_at"), nullableText(node, "effective_at"),
                        nullableText(node, "system_at"), nullableText(node, "expires_at"),
                        requiredText(node, "status"), nullableDecimal(node, "confidence"), requiredText(node, "privacy"),
                        requiredText(node, "payload_digest"), nullableText(node, "expected_rejection")));
            }
            var keys = records.stream().map(CanonicalRecord::legacyKey).toList();
            if (keys.stream().distinct().count() != keys.size()) {
                throw new IllegalArgumentException("migration bundle contains duplicate legacy keys");
            }
            return List.copyOf(records);
        } catch (IOException failure) {
            throw new IllegalArgumentException("migration bundle JSONL is invalid", failure);
        }
    }

    private static void validateCounts(Manifest manifest, List<CanonicalRecord> records) {
        if (manifest.recordCount() != records.size() || !manifest.sourceCounts().keySet().equals(manifest.acceptedCounts().keySet())
                || !manifest.sourceCounts().keySet().equals(manifest.rejectionCounts().keySet())) {
            throw new IllegalArgumentException("migration bundle count manifest is invalid");
        }
        var sourceTotal = manifest.sourceCounts().values().stream().mapToInt(Integer::intValue).sum();
        if (sourceTotal != records.size()) {
            throw new IllegalArgumentException("migration bundle source count does not match records");
        }
        var rejectionActual = new LinkedHashMap<String, Integer>();
        var sourceActual = new LinkedHashMap<String, Integer>();
        for (var record : records) {
            var table = tableFor(record.legacyKey());
            if (!manifest.sourceCounts().containsKey(table)) {
                throw new IllegalArgumentException("migration bundle record table is absent from the manifest");
            }
            sourceActual.merge(table, 1, Integer::sum);
            rejectionActual.merge(table, record.expectedRejection() == null ? 0 : 1, Integer::sum);
        }
        for (var table : manifest.sourceCounts().keySet()) {
            var source = manifest.sourceCounts().get(table);
            var accepted = manifest.acceptedCounts().get(table);
            var rejected = manifest.rejectionCounts().get(table);
            if (source < 0 || accepted < 0 || rejected < 0 || sourceActual.getOrDefault(table, 0) != source
                    || accepted + rejected != source
                    || rejectionActual.getOrDefault(table, 0) != rejected) {
                throw new IllegalArgumentException("migration bundle counts do not reconcile");
            }
        }
    }

    private static Map<String, Integer> counts(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("migration bundle count manifest is invalid");
        }
        var result = new LinkedHashMap<String, Integer>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().isBlank() || !entry.getValue().canConvertToInt() || entry.getValue().asInt() < 0) {
                throw new IllegalArgumentException("migration bundle count manifest is invalid");
            }
            result.put(entry.getKey(), entry.getValue().asInt());
        }
        return Map.copyOf(result);
    }

    private static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException failure) {
            throw new SecurityException("migration bundle authentication failed", failure);
        }
    }

    private static byte[] aad(JsonNode header, int sequence) {
        return (FORMAT + "\n" + canonicalJson(header) + "\n" + sequence).getBytes(StandardCharsets.UTF_8);
    }

    static String canonicalJson(JsonNode value) {
        if (value.isObject()) {
            var names = new ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            var encoded = new StringBuilder("{");
            for (var index = 0; index < names.size(); index++) {
                if (index > 0) {
                    encoded.append(',');
                }
                var name = names.get(index);
                encoded.append(canonicalJson(JSON.getNodeFactory().textNode(name))).append(':')
                        .append(canonicalJson(value.get(name)));
            }
            return encoded.append('}').toString();
        }
        if (value.isArray()) {
            var encoded = new StringBuilder("[");
            for (var index = 0; index < value.size(); index++) {
                if (index > 0) {
                    encoded.append(',');
                }
                encoded.append(canonicalJson(value.get(index)));
            }
            return encoded.append(']').toString();
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException failure) {
            throw new IllegalArgumentException("migration bundle JSON cannot be canonicalized", failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] base64(String value, String field) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " encoding is invalid", failure);
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("migration bundle key must decode to exactly 32 bytes");
        }
    }

    private static String requiredText(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("migration bundle record " + name + " is invalid");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode node, String name) {
        var value = node.path(name);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("migration bundle record " + name + " is invalid");
        }
        return value.asText();
    }

    private static Double nullableDecimal(JsonNode node, String name) {
        var value = node.path(name);
        if (value.isNull()) {
            return null;
        }
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IllegalArgumentException("migration bundle record " + name + " is invalid");
        }
        return value.asDouble();
    }

    private static boolean hexDigest(String value) {
        return value.matches("[0-9a-f]{64}");
    }

    static String tableFor(String legacyKey) {
        var delimiter = legacyKey.indexOf(':');
        if (delimiter <= 0 || delimiter == legacyKey.length() - 1) {
            throw new IllegalArgumentException("migration bundle legacy key is invalid");
        }
        return legacyKey.substring(0, delimiter);
    }

    private static Set<String> fieldNames(JsonNode node) {
        var names = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    public record Manifest(
            String format,
            String schemaFingerprint,
            String sourceBaselineDigest,
            Map<String, Integer> sourceCounts,
            Map<String, Integer> acceptedCounts,
            Map<String, Integer> rejectionCounts,
            int recordCount,
            String recordsSha256,
            int chunkCount) {

        public Manifest {
            sourceCounts = Map.copyOf(sourceCounts);
            acceptedCounts = Map.copyOf(acceptedCounts);
            rejectionCounts = Map.copyOf(rejectionCounts);
        }
    }

    public record CanonicalRecord(
            String kind,
            String legacyKey,
            String subject,
            List<String> sourceRefs,
            String type,
            String payloadJson,
            String sourceAt,
            String effectiveAt,
            String systemAt,
            String expiresAt,
            String status,
            Double confidence,
            String privacy,
            String payloadDigest,
            String expectedRejection) {

        public CanonicalRecord {
            sourceRefs = List.copyOf(sourceRefs);
        }
    }

    public record MigrationBundle(Manifest manifest, List<CanonicalRecord> records) {
        public MigrationBundle {
            manifest = Objects.requireNonNull(manifest, "manifest");
            records = List.copyOf(records);
        }
    }
}
