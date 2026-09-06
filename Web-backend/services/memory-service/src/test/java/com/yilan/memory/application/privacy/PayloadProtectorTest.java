package com.yilan.memory.application.privacy;

import com.yilan.memory.adapter.crypto.ConfiguredLocalKeyProvider;
import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadProtectorTest {

    private static final String SUBJECT_A = "subject-a";
    private static final String SUBJECT_B = "subject-b";
    private static final UUID EVENT_A = UUID.fromString("7bbfe68f-1fbb-4d15-b4ee-1b9ab9f80f3d");
    private static final UUID EVENT_B = UUID.fromString("9d720a29-d565-4b99-b94d-2d7c14eb005e");

    @Test
    void sealRoundTripsOnlyForTheSameAuthenticatedBinding() {
        var protector = testProtector();
        var sealed = protector.seal(binding(SUBJECT_A, EVENT_A, "v1"), bytes("source sentinel"));

        assertThat(protector.open(binding(SUBJECT_A, EVENT_A, "v1"), sealed))
                .isEqualTo(bytes("source sentinel"));
        assertThatThrownBy(() -> protector.open(binding(SUBJECT_B, EVENT_A, "v1"), sealed))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> protector.open(binding(SUBJECT_A, EVENT_B, "v1"), sealed))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> protector.open(binding(SUBJECT_A, EVENT_A, "v2"), sealed))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void sourceKeysAreScopedToOneEventAndCannotBeSubstituted() {
        var masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        var sourceEnvelopes = new InMemoryTestKeyProvider.InMemoryInteractionPayloadKeyEnvelopeStore();
        var protector = new PayloadProtector(new InMemoryTestKeyProvider(
                masterKey, new InMemoryTestKeyProvider.InMemoryEnvelopeStore(), sourceEnvelopes));

        var sealed = protector.sealSource(binding(SUBJECT_A, EVENT_A, "v1"), bytes("event scoped sentinel"));
        sourceEnvelopes.storeIfAbsent(SUBJECT_A, EVENT_A, "v1", sealed.envelope());

        assertThat(protector.open(binding(SUBJECT_A, EVENT_A, "v1"), sealed.framed()))
                .isEqualTo(bytes("event scoped sentinel"));
        assertThatThrownBy(() -> protector.open(binding(SUBJECT_A, EVENT_B, "v1"), sealed.framed()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> protector.open(binding(SUBJECT_B, EVENT_A, "v1"), sealed.framed()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void tamperedFramesAndWrongPurposeBindingsFailClosed() {
        var protector = testProtector();
        var sealed = protector.seal(binding(SUBJECT_A, EVENT_A, "v1"), bytes("source sentinel"));
        sealed[sealed.length - 1] ^= 1;

        assertThatThrownBy(() -> protector.open(binding(SUBJECT_A, EVENT_A, "v1"), sealed))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> new PayloadProtector.SourceMaterialBinding(
                SUBJECT_A, EVENT_A, "v1", "interaction_event", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void genericProtectedPayloadsAreBoundToLearnerRecordTablePurposeAndSchema() {
        var protector = testProtector();
        var binding = new PayloadProtector.PayloadBinding(
                SUBJECT_A, EVENT_A, "memory-value/v1", "memory_version", KeyPurpose.MEMORY_VERSION_VALUE);

        var encrypted = protector.encrypt(binding, bytes("protected value sentinel"));

        assertThat(protector.decrypt(binding, encrypted)).isEqualTo(bytes("protected value sentinel"));
        assertThatThrownBy(() -> protector.decrypt(new PayloadProtector.PayloadBinding(
                SUBJECT_B, EVENT_A, "memory-value/v1", "memory_version", KeyPurpose.MEMORY_VERSION_VALUE), encrypted))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> protector.decrypt(new PayloadProtector.PayloadBinding(
                SUBJECT_A, EVENT_B, "memory-value/v1", "memory_version", KeyPurpose.MEMORY_VERSION_VALUE), encrypted))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void configuredProviderRejectsMissingDefaultBadOrProductionKey() {
        assertThatThrownBy(() -> ConfiguredLocalKeyProvider.from("local-demo", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConfiguredLocalKeyProvider.from("test", "default"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConfiguredLocalKeyProvider.from("test",
                Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfiguredLocalKeyProvider.from("production", encodedRandomKey()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void envelopeBackedKeysSurviveRestartButCannotResurrectAfterEnvelopeDestruction() {
        var masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        var store = new InMemoryTestKeyProvider.InMemoryEnvelopeStore();
        var first = new PayloadProtector(new InMemoryTestKeyProvider(masterKey, store));
        var binding = new PayloadProtector.PayloadBinding(
                SUBJECT_A, EVENT_A, "memory-value/v1", "memory_version", KeyPurpose.MEMORY_VERSION_VALUE);
        var sealed = first.encrypt(binding, bytes("crypto-shred sentinel"));

        var restarted = new PayloadProtector(new InMemoryTestKeyProvider(masterKey, store));
        assertThat(restarted.decrypt(binding, sealed))
                .isEqualTo(bytes("crypto-shred sentinel"));

        store.destroy(SUBJECT_A);
        var afterForget = new PayloadProtector(new InMemoryTestKeyProvider(masterKey, store));
        assertThatThrownBy(() -> afterForget.decrypt(binding, sealed))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void randomThirtyTwoByteDekIsOpaqueAndNeverDerivedFromTheWrappingMaster() {
        var masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        var store = new InMemoryTestKeyProvider.InMemoryEnvelopeStore();
        var first = new InMemoryTestKeyProvider(masterKey, store);
        var keyA = first.activeKeyFor(SUBJECT_A, KeyPurpose.INTERACTION_SOURCE);
        var keyB = first.activeKeyFor(SUBJECT_B, KeyPurpose.INTERACTION_SOURCE);
        var restarted = new InMemoryTestKeyProvider(masterKey, store);

        assertThat(keyA.key()).hasSize(32).isNotEqualTo(keyB.key());
        assertThat(restarted.activeKeyFor(SUBJECT_A, KeyPurpose.INTERACTION_SOURCE).key())
                .isEqualTo(keyA.key());
        var envelope = store.load(SUBJECT_A).orElseThrow();
        assertThat(envelope.dekReference()).isEqualTo(keyA.keyReference());
        assertThat(envelope.wrappedDek()).hasSize(60).isNotEqualTo(keyA.key()).isNotEqualTo(masterKey);
    }

    private static PayloadProtector testProtector() {
        var masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        return new PayloadProtector(new InMemoryTestKeyProvider(masterKey));
    }

    private static PayloadProtector.SourceMaterialBinding binding(String subject, UUID eventId, String schemaVersion) {
        return new PayloadProtector.SourceMaterialBinding(
                subject,
                eventId,
                schemaVersion,
                "interaction_event",
                KeyPurpose.INTERACTION_SOURCE);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String encodedRandomKey() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
