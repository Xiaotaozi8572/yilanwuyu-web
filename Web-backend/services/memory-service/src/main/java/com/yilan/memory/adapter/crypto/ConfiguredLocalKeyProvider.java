package com.yilan.memory.adapter.crypto;

import com.yilan.memory.application.privacy.DataKeyProvider;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.LearnerDekEnvelopeStore;
import com.yilan.memory.application.privacy.InteractionPayloadKeyEnvelopeStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Accepts only a caller-provided Base64 AES-256 test/local-demo master key.
 * It deliberately has no configured fallback and does not support production.
 */
@Component
public final class ConfiguredLocalKeyProvider implements DataKeyProvider {

    private final String mode;
    private final String externallySuppliedMasterKey;
    private final LearnerDekEnvelopeStore envelopes;
    private final InteractionPayloadKeyEnvelopeStore sourceEnvelopes;
    private final SecureRandom random = new SecureRandom();

    public ConfiguredLocalKeyProvider(
            @Value("${memory.source-material-security.mode:disabled}") String mode,
            @Value("${memory.source-material-security.master-key:}") String externallySuppliedMasterKey,
            LearnerDekEnvelopeStore envelopes,
            InteractionPayloadKeyEnvelopeStore sourceEnvelopes) {
        this.mode = mode;
        this.externallySuppliedMasterKey = externallySuppliedMasterKey;
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.sourceEnvelopes = Objects.requireNonNull(sourceEnvelopes, "sourceEnvelopes");
    }

    public static ConfiguredLocalKeyProvider from(String mode, String externallySuppliedMasterKey) {
        var provider = new ConfiguredLocalKeyProvider(mode, externallySuppliedMasterKey,
                new InMemoryTestKeyProvider.InMemoryEnvelopeStore(),
                new InMemoryTestKeyProvider.InMemoryInteractionPayloadKeyEnvelopeStore());
        provider.currentKey();
        return provider;
    }

    public static ConfiguredLocalKeyProvider from(String mode, String externallySuppliedMasterKey,
            LearnerDekEnvelopeStore envelopes) {
        var provider = new ConfiguredLocalKeyProvider(mode, externallySuppliedMasterKey, envelopes,
                new InMemoryTestKeyProvider.InMemoryInteractionPayloadKeyEnvelopeStore());
        provider.currentKey();
        return provider;
    }

    @Override
    public KeyMaterial activeKeyFor(String subjectHash, KeyPurpose purpose) {
        requireBinding(subjectHash, purpose);
        var master = currentKey();
        var stored = envelopes.load(subjectHash);
        if (stored.isEmpty()) {
            var dek = new byte[32]; random.nextBytes(dek);
            var candidate = new LearnerDekEnvelopeStore.Envelope(DataKeyProvider.opaqueReference(dek), wrap(master, subjectHash, dek),
                    master.keyReference(), Instant.now());
            envelopes.storeIfAbsent(subjectHash, candidate);
            stored = envelopes.load(subjectHash);
        }
        return material(master, subjectHash, stored.orElseThrow(() -> new SecurityException("protected key unavailable")));
    }

    @Override
    public KeyMaterial keyFor(String subjectHash, KeyPurpose purpose, String keyReference) {
        requireBinding(subjectHash, purpose);
        var envelope = envelopes.load(subjectHash).orElseThrow(() -> new SecurityException("protected key unavailable"));
        if (!envelope.dekReference().equals(keyReference)) {
            throw new SecurityException("protected key unavailable");
        }
        return material(currentKey(), subjectHash, envelope);
    }

    @Override
    public SourceKeyMaterial activeSourceKeyFor(String subjectHash, UUID eventId, String schemaVersion) {
        InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
        var stored = sourceEnvelopes.load(subjectHash, eventId, schemaVersion);
        if (stored.isPresent()) {
            return new SourceKeyMaterial(sourceMaterial(currentKey(), subjectHash, eventId, schemaVersion, stored.orElseThrow()),
                    stored.orElseThrow());
        }
        var master = currentKey();
        var dek = new byte[32]; random.nextBytes(dek);
        var envelope = new InteractionPayloadKeyEnvelopeStore.Envelope(
                DataKeyProvider.opaqueReference(dek), wrap(master, sourceBinding(subjectHash, eventId, schemaVersion), dek),
                master.keyReference(), Instant.now());
        return new SourceKeyMaterial(new KeyMaterial(envelope.dekReference(), dek), envelope);
    }

    @Override
    public KeyMaterial sourceKeyFor(String subjectHash, UUID eventId, String schemaVersion, String keyReference) {
        InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
        var envelope = sourceEnvelopes.load(subjectHash, eventId, schemaVersion)
                .orElseThrow(() -> new SecurityException("protected key unavailable"));
        if (!envelope.dekReference().equals(keyReference)) {
            throw new SecurityException("protected key unavailable");
        }
        return sourceMaterial(currentKey(), subjectHash, eventId, schemaVersion, envelope);
    }

    @Override
    public void storeSourceEnvelopeIfAbsent(
            String subjectHash, UUID eventId, String schemaVersion, InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
        sourceEnvelopes.storeIfAbsent(subjectHash, eventId, schemaVersion, envelope);
    }

    private KeyMaterial currentKey() {
        if (!("local-demo".equals(mode) || "test".equals(mode))) {
            throw new IllegalStateException("source keys are disabled outside local-demo/test");
        }
        if (externallySuppliedMasterKey == null || externallySuppliedMasterKey.isBlank()
                || "default".equalsIgnoreCase(externallySuppliedMasterKey)
                || "change-me".equalsIgnoreCase(externallySuppliedMasterKey)
                || "local-demo".equalsIgnoreCase(externallySuppliedMasterKey)) {
            throw new IllegalStateException("an external non-default source key is required");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(externallySuppliedMasterKey);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("an external non-default source key is required");
        }
        return new KeyMaterial(DataKeyProvider.opaqueReference(decoded), decoded);
    }

    private KeyMaterial material(KeyMaterial master, String subjectHash, LearnerDekEnvelopeStore.Envelope envelope) {
        if (!master.keyReference().equals(envelope.wrappingKeyReference())) {
            throw new SecurityException("protected key unavailable");
        }
        return new KeyMaterial(envelope.dekReference(), unwrap(master, subjectHash, envelope.wrappedDek()));
    }

    private KeyMaterial sourceMaterial(
            KeyMaterial master, String subjectHash, UUID eventId, String schemaVersion,
            InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
        if (!master.keyReference().equals(envelope.wrappingKeyReference())) {
            throw new SecurityException("protected key unavailable");
        }
        return new KeyMaterial(envelope.dekReference(),
                unwrap(master, sourceBinding(subjectHash, eventId, schemaVersion), envelope.wrappedDek()));
    }

    private void requireBinding(String subjectHash, KeyPurpose purpose) {
        if (subjectHash == null || subjectHash.isBlank() || purpose == null) {
            throw new SecurityException("protected key unavailable");
        }
    }

    private byte[] wrap(KeyMaterial master, String subjectHash, byte[] dek) { return crypt(Cipher.ENCRYPT_MODE, master, subjectHash, dek); }
    private byte[] unwrap(KeyMaterial master, String subjectHash, byte[] wrapped) { return crypt(Cipher.DECRYPT_MODE, master, subjectHash, wrapped); }

    private byte[] crypt(int mode, KeyMaterial master, String subjectHash, byte[] input) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (mode == Cipher.ENCRYPT_MODE) {
                var nonce = new byte[12]; random.nextBytes(nonce);
                cipher.init(mode, new SecretKeySpec(master.key(), "AES"), new GCMParameterSpec(128, nonce));
                cipher.updateAAD(aad(subjectHash));
                var ciphertext = cipher.doFinal(input);
                var framed = new byte[nonce.length + ciphertext.length];
                System.arraycopy(nonce, 0, framed, 0, nonce.length);
                System.arraycopy(ciphertext, 0, framed, nonce.length, ciphertext.length);
                return framed;
            }
            if (input == null || input.length <= 12) throw new SecurityException("protected key unavailable");
            cipher.init(mode, new SecretKeySpec(master.key(), "AES"), new GCMParameterSpec(128, input, 0, 12));
            cipher.updateAAD(aad(subjectHash));
            var dek = cipher.doFinal(input, 12, input.length - 12);
            if (dek.length != 32) throw new SecurityException("protected key unavailable");
            return dek;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new SecurityException("protected key unavailable");
        }
    }

    private static byte[] aad(String subjectHash) {
        return ("memory/learner-dek-envelope/v1\u0000" + subjectHash).getBytes(StandardCharsets.UTF_8);
    }

    private static String sourceBinding(String subjectHash, UUID eventId, String schemaVersion) {
        return "memory/interaction-dek-envelope/v1\u0000" + subjectHash + "\u0000" + eventId + "\u0000" + schemaVersion;
    }
}
