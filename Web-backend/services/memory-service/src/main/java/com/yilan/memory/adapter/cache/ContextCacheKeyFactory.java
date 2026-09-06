package com.yilan.memory.adapter.cache;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.domain.identity.LearnerIdentity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Builds opaque, epoch-bound HMAC keys without persisting raw queries. */
public final class ContextCacheKeyFactory {

    private static final String NAMESPACE = "memory-context/v1";
    private final byte[] secret;

    /** A blank value deliberately disables cache keys; it is never a fallback secret. */
    public ContextCacheKeyFactory(String externallySuppliedSecret) {
        this.secret = externallySuppliedSecret == null || externallySuppliedSecret.isBlank()
                ? new byte[0]
                : externallySuppliedSecret.getBytes(StandardCharsets.UTF_8);
    }

    public Optional<String> create(
            LearnerIdentity identity,
            ResolveMemoryContextUseCase.MemoryQuery query,
            long memoryEpoch,
            long consentEpoch) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(query, "query");
        if (secret.length == 0 || memoryEpoch < 0 || consentEpoch < 0) {
            return Optional.empty();
        }
        var scope = query.cacheScope();
        var payload = String.join("\u001f",
                NAMESPACE,
                sha256Hex(identity.subjectHash()),
                sha256Hex(query.queryText()),
                sceneDigest(scope),
                resolutionScopeDigest(identity, query),
                Long.toString(memoryEpoch),
                Long.toString(consentEpoch),
                scope.schemaVersion());
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Optional.of("memory:ctx:" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.GeneralSecurityException unavailable) {
            return Optional.empty();
        }
    }

    private static String sceneDigest(ResolveMemoryContextUseCase.CacheScope scope) {
        var objectIds = new ArrayList<>(scope.objectIds());
        objectIds.sort(String::compareTo);
        var fields = new ArrayList<String>();
        fields.add(Boolean.toString(scope.scenePresent()));
        fields.add(scope.sceneType());
        fields.add(scope.sceneId());
        fields.add(scope.locale());
        fields.addAll(objectIds);
        return sha256Hex(String.join("\u001f", fields));
    }

    /**
     * Binds the result-affecting internal resolve inputs while keeping every
     * individual field opaque. This digest is deliberately independent of the
     * public cache-key namespace so its format can evolve only with that
     * namespace version.
     */
    private static String resolutionScopeDigest(
            LearnerIdentity identity,
            ResolveMemoryContextUseCase.MemoryQuery query) {
        var digest = sha256();
        updateField(digest, "session-hash", sha256Hex(identity.sessionId()));
        updateField(digest, "task-type-hash", sha256Hex(query.taskType()));
        updateField(digest, "allowed-types", sortedTypeNames(query.allowedTypes()));
        updateField(digest, "required-types", sortedTypeNames(query.requiredTypes()));
        updateField(digest, "max-items", Integer.toString(query.maxItems()));
        updateField(digest, "max-estimated-tokens", Integer.toString(query.maxEstimatedTokens()));
        updateField(digest, "graph-required", Boolean.toString(query.graphRequired()));
        updateField(digest, "remaining-java-budget", query.remainingJavaBudget().toString());

        var embedding = query.queryEmbedding();
        updateField(digest, "embedding-present", Boolean.toString(embedding != null));
        if (embedding != null) {
            updateField(digest, "embedding-model-id", nullableHash(embedding.modelId()));
            updateField(digest, "embedding-model-version", nullableHash(embedding.modelVersion()));
            updateField(digest, "embedding-dimension", Integer.toString(embedding.dimension()));
            updateField(digest, "embedding-normalization", nullableHash(embedding.normalization()));
            updateField(digest, "embedding-fingerprint", embeddingFingerprint(embedding));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sortedTypeNames(
            java.util.Set<com.yilan.memory.domain.governance.MemoryCandidate.MemoryType> types) {
        return types.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
    }

    /** Hashes exact Float IEEE-754 bit patterns in list order; vector values never enter a cache key. */
    private static String embeddingFingerprint(ResolveMemoryContextUseCase.QueryEmbedding embedding) {
        var digest = sha256();
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(embedding.values().size()).array());
        for (var value : embedding.values()) {
            digest.update((byte) (value == null ? 0 : 1));
            if (value != null) {
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(Float.floatToRawIntBits(value)).array());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String nullableHash(String value) {
        return sha256Hex(value == null ? "\u0000" : "\u0001" + value);
    }

    private static void updateField(MessageDigest digest, String name, String value) {
        var nameBytes = name.getBytes(StandardCharsets.UTF_8);
        var valueBytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(nameBytes.length).array());
        digest.update(nameBytes);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(valueBytes.length).array());
        digest.update(valueBytes);
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256()
                .digest(Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
