package com.yilan.memory.adapter.crypto;

import com.yilan.memory.application.privacy.DataKeyProvider;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.LearnerDekEnvelopeStore;
import com.yilan.memory.application.privacy.InteractionPayloadKeyEnvelopeStore;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/** Test-only provider; callers must explicitly provide their ephemeral master key. */
public final class InMemoryTestKeyProvider implements DataKeyProvider {

    private final byte[] externallySuppliedTestMasterKey;
    private final LearnerDekEnvelopeStore envelopes;
    private final InteractionPayloadKeyEnvelopeStore sourceEnvelopes;
    private final SecureRandom random = new SecureRandom();

    public InMemoryTestKeyProvider(byte[] externallySuppliedTestMasterKey) {
        this(externallySuppliedTestMasterKey, new InMemoryEnvelopeStore(), new InMemoryInteractionPayloadKeyEnvelopeStore());
    }

    public InMemoryTestKeyProvider(byte[] externallySuppliedTestMasterKey, LearnerDekEnvelopeStore envelopes) {
        this(externallySuppliedTestMasterKey, envelopes, new InMemoryInteractionPayloadKeyEnvelopeStore());
    }

    public InMemoryTestKeyProvider(
            byte[] externallySuppliedTestMasterKey,
            LearnerDekEnvelopeStore envelopes,
            InteractionPayloadKeyEnvelopeStore sourceEnvelopes) {
        var key = Objects.requireNonNull(externallySuppliedTestMasterKey, "externallySuppliedTestMasterKey").clone();
        if (key.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
        }
        this.externallySuppliedTestMasterKey = key;
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.sourceEnvelopes = Objects.requireNonNull(sourceEnvelopes, "sourceEnvelopes");
    }

    @Override
    public KeyMaterial activeKeyFor(String subjectHash, KeyPurpose purpose) {
        requireBinding(subjectHash, purpose);
        var stored = envelopes.load(subjectHash);
        if (stored.isEmpty()) {
            var dek = new byte[32];
            random.nextBytes(dek);
            var candidate = new LearnerDekEnvelopeStore.Envelope(DataKeyProvider.opaqueReference(dek),
                    wrap(subjectHash, dek), DataKeyProvider.opaqueReference(externallySuppliedTestMasterKey), Instant.now());
            envelopes.storeIfAbsent(subjectHash, candidate);
            stored = envelopes.load(subjectHash);
        }
        return material(subjectHash, stored.orElseThrow(() -> new SecurityException("protected key unavailable")));
    }

    @Override
    public KeyMaterial keyFor(String subjectHash, KeyPurpose purpose, String keyReference) {
        requireBinding(subjectHash, purpose);
        var envelope = envelopes.load(subjectHash).orElseThrow(() -> new SecurityException("protected key unavailable"));
        if (!envelope.dekReference().equals(keyReference)) {
            throw new SecurityException("protected key unavailable");
        }
        return material(subjectHash, envelope);
    }

    @Override
    public SourceKeyMaterial activeSourceKeyFor(String subjectHash, UUID eventId, String schemaVersion) {
        InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
        var stored = sourceEnvelopes.load(subjectHash, eventId, schemaVersion);
        if (stored.isPresent()) {
            return new SourceKeyMaterial(sourceMaterial(subjectHash, eventId, schemaVersion, stored.orElseThrow()), stored.orElseThrow());
        }
        var dek = new byte[32];
        random.nextBytes(dek);
        var envelope = new InteractionPayloadKeyEnvelopeStore.Envelope(
                DataKeyProvider.opaqueReference(dek), wrap(sourceBinding(subjectHash, eventId, schemaVersion), dek),
                DataKeyProvider.opaqueReference(externallySuppliedTestMasterKey), Instant.now());
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
        return sourceMaterial(subjectHash, eventId, schemaVersion, envelope);
    }

    @Override
    public void storeSourceEnvelopeIfAbsent(
            String subjectHash, UUID eventId, String schemaVersion, InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
        sourceEnvelopes.storeIfAbsent(subjectHash, eventId, schemaVersion, envelope);
    }

    private KeyMaterial material(String subjectHash, LearnerDekEnvelopeStore.Envelope envelope) {
        if (!envelope.wrappingKeyReference().equals(DataKeyProvider.opaqueReference(externallySuppliedTestMasterKey))) {
            throw new SecurityException("protected key unavailable");
        }
        return new KeyMaterial(envelope.dekReference(), unwrap(subjectHash, envelope.wrappedDek()));
    }

    private KeyMaterial sourceMaterial(
            String subjectHash, UUID eventId, String schemaVersion, InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
        if (!envelope.wrappingKeyReference().equals(DataKeyProvider.opaqueReference(externallySuppliedTestMasterKey))) {
            throw new SecurityException("protected key unavailable");
        }
        return new KeyMaterial(envelope.dekReference(), unwrap(sourceBinding(subjectHash, eventId, schemaVersion), envelope.wrappedDek()));
    }

    private void requireBinding(String subjectHash, KeyPurpose purpose) {
        if (subjectHash == null || subjectHash.isBlank() || purpose == null) {
            throw new SecurityException("protected key unavailable");
        }
    }

    private byte[] wrap(String subjectHash, byte[] dek) { return crypt(Cipher.ENCRYPT_MODE, subjectHash, dek); }
    private byte[] unwrap(String subjectHash, byte[] wrapped) { return crypt(Cipher.DECRYPT_MODE, subjectHash, wrapped); }

    private byte[] crypt(int mode, String subjectHash, byte[] input) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (mode == Cipher.ENCRYPT_MODE) {
                var nonce = new byte[12]; random.nextBytes(nonce);
                cipher.init(mode, new SecretKeySpec(externallySuppliedTestMasterKey, "AES"), new GCMParameterSpec(128, nonce));
                cipher.updateAAD(aad(subjectHash));
                var ciphertext = cipher.doFinal(input);
                var framed = new byte[nonce.length + ciphertext.length];
                System.arraycopy(nonce, 0, framed, 0, nonce.length);
                System.arraycopy(ciphertext, 0, framed, nonce.length, ciphertext.length);
                return framed;
            }
            if (input == null || input.length <= 12) throw new SecurityException("protected key unavailable");
            cipher.init(mode, new SecretKeySpec(externallySuppliedTestMasterKey, "AES"), new GCMParameterSpec(128, input, 0, 12));
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

    /** Injectable test authority store, shared across provider instances to simulate restart. */
    public static final class InMemoryEnvelopeStore implements LearnerDekEnvelopeStore {
        private final ConcurrentHashMap<String, Envelope> entries = new ConcurrentHashMap<>();
        @Override public Optional<Envelope> load(String subjectHash) { return Optional.ofNullable(entries.get(subjectHash)); }
        @Override public void storeIfAbsent(String subjectHash, Envelope envelope) { entries.putIfAbsent(subjectHash, envelope); }
        @Override public void destroy(String subjectHash) { entries.remove(subjectHash); }
    }

    /** Test-only source envelope authority, injectable to model a persisted event scope. */
    public static final class InMemoryInteractionPayloadKeyEnvelopeStore implements InteractionPayloadKeyEnvelopeStore {
        private final ConcurrentHashMap<String, Envelope> entries = new ConcurrentHashMap<>();

        @Override public Optional<Envelope> load(String subjectHash, UUID eventId, String schemaVersion) {
            InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
            return Optional.ofNullable(entries.get(key(subjectHash, eventId, schemaVersion)));
        }

        @Override public void storeIfAbsent(String subjectHash, UUID eventId, String schemaVersion, Envelope envelope) {
            InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
            entries.putIfAbsent(key(subjectHash, eventId, schemaVersion), Objects.requireNonNull(envelope, "envelope"));
        }

        @Override public boolean eraseForRetention(String subjectHash, UUID eventId, String schemaVersion,
                String policyVersion, Instant dueAt, Instant executedAt) {
            InteractionPayloadKeyEnvelopeStore.requireBinding(subjectHash, eventId, schemaVersion);
            return entries.remove(key(subjectHash, eventId, schemaVersion)) != null;
        }

        @Override public int eraseAllForSubject(String subjectHash) {
            var prefix = subjectHash + "\u0000";
            var before = entries.size();
            entries.keySet().removeIf(key -> key.startsWith(prefix));
            return before - entries.size();
        }

        private static String key(String subjectHash, UUID eventId, String schemaVersion) {
            return subjectHash + "\u0000" + eventId + "\u0000" + schemaVersion;
        }
    }
}
