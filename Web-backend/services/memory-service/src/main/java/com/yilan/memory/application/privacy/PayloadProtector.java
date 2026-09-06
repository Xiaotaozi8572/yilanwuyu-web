package com.yilan.memory.application.privacy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;

/** AES-256-GCM source-material protection for explicitly enabled local/test ingress. */
@Component
public final class PayloadProtector {

    private static final String INTERACTION_EVENT_TABLE = "interaction_event";
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final DataKeyProvider keys;
    private final boolean enabled;
    private final SecureRandom secureRandom;

    @Autowired
    public PayloadProtector(
            DataKeyProvider keys,
            @Value("${memory.source-material-security.mode:disabled}") String mode) {
        this(keys, isLocalDemoOrTest(mode), new SecureRandom());
    }

    public PayloadProtector(DataKeyProvider keys) {
        this(keys, true, new SecureRandom());
    }

    private PayloadProtector(DataKeyProvider keys, boolean enabled, SecureRandom secureRandom) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.enabled = enabled;
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public static PayloadProtector disabled() {
        return new PayloadProtector(new DataKeyProvider() {
            @Override
            public KeyMaterial activeKeyFor(String subjectHash, KeyPurpose purpose) {
                throw new SecurityException("source protection is disabled");
            }

            @Override
            public KeyMaterial keyFor(String subjectHash, KeyPurpose purpose, String keyReference) {
                throw new SecurityException("source protection is disabled");
            }

            @Override
            public SourceKeyMaterial activeSourceKeyFor(String subjectHash, UUID eventId, String schemaVersion) {
                throw new SecurityException("source protection is disabled");
            }

            @Override
            public KeyMaterial sourceKeyFor(String subjectHash, UUID eventId, String schemaVersion, String keyReference) {
                throw new SecurityException("source protection is disabled");
            }
        }, false, new SecureRandom());
    }

    public boolean enabled() {
        return enabled;
    }

    public byte[] seal(SourceMaterialBinding binding, byte[] plaintext) {
        var sealed = sealSource(binding, plaintext);
        keys.storeSourceEnvelopeIfAbsent(binding.subjectHash(), binding.eventId(), binding.eventSchemaVersion(), sealed.envelope());
        return sealed.framed();
    }

    /**
     * Seals a source frame without persisting its envelope. The ingress use
     * case persists that envelope only after the immutable event insert has
     * succeeded, inside the same authority transaction.
     */
    public SealedSource sealSource(SourceMaterialBinding binding, byte[] plaintext) {
        requireEnabled();
        binding = Objects.requireNonNull(binding, "binding");
        var source = Objects.requireNonNull(plaintext, "plaintext").clone();
        var sourceKey = keys.activeSourceKeyFor(binding.subjectHash(), binding.eventId(), binding.eventSchemaVersion());
        return new SealedSource(encryptWithKey(sourceKey.keyMaterial(), binding.aadBytes(), source).serialize(), sourceKey.envelope());
    }

    public byte[] open(SourceMaterialBinding binding, byte[] framed) {
        return decrypt(binding, EncryptedPayload.parse(framed));
    }

    /** Encrypts a protected candidate/version value without exposing its bytes to a caller. */
    public EncryptedPayload encrypt(PayloadBinding binding, byte[] plaintext) {
        return encrypt(binding.subjectHash(), binding.purpose(), binding.aadBytes(), plaintext);
    }

    /** Decrypts a protected candidate/version value only in an authority adapter. */
    public byte[] decrypt(PayloadBinding binding, EncryptedPayload payload) {
        return decrypt(binding.subjectHash(), binding.purpose(), binding.aadBytes(), payload);
    }

    private EncryptedPayload encrypt(SourceMaterialBinding binding, byte[] plaintext) {
        return EncryptedPayload.parse(sealSource(binding, plaintext).framed());
    }

    private byte[] decrypt(SourceMaterialBinding binding, EncryptedPayload payload) {
        requireEnabled();
        payload = Objects.requireNonNull(payload, "payload");
        var key = keys.sourceKeyFor(binding.subjectHash(), binding.eventId(), binding.eventSchemaVersion(), payload.keyReference());
        return decryptWithKey(key, binding.aadBytes(), payload);
    }

    private EncryptedPayload encrypt(String subjectHash, KeyPurpose purpose, byte[] aad, byte[] plaintext) {
        requireEnabled();
        var source = Objects.requireNonNull(plaintext, "plaintext").clone();
        var key = keys.activeKeyFor(subjectHash, purpose);
        return encryptWithKey(key, aad, source);
    }

    private EncryptedPayload encryptWithKey(DataKeyProvider.KeyMaterial key, byte[] aad, byte[] plaintext) {
        var nonce = new byte[GCM_NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key.key(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return new EncryptedPayload(key.keyReference(), nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new SecurityException("protected payload sealing failed");
        }
    }

    private byte[] decrypt(String subjectHash, KeyPurpose purpose, byte[] aad, EncryptedPayload payload) {
        requireEnabled();
        payload = Objects.requireNonNull(payload, "payload");
        var key = keys.keyFor(subjectHash, purpose, payload.keyReference());
        return decryptWithKey(key, aad, payload);
    }

    private static byte[] decryptWithKey(DataKeyProvider.KeyMaterial key, byte[] aad, EncryptedPayload payload) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key.key(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, payload.nonce()));
            cipher.updateAAD(aad);
            return cipher.doFinal(payload.ciphertextAndTag());
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new SecurityException("encrypted payload rejected");
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new SecurityException("source protection is disabled");
        }
    }

    private static boolean isLocalDemoOrTest(String mode) {
        return "local-demo".equals(mode) || "test".equals(mode);
    }

    /** Canonical M4 AAD derivation for protected authority storage. */
    public record PayloadBinding(
            String subjectHash,
            UUID recordId,
            String schemaVersion,
            String tableName,
            KeyPurpose purpose) {

        public PayloadBinding {
            if (subjectHash == null || subjectHash.isBlank() || subjectHash.length() > 512
                    || recordId == null
                    || schemaVersion == null || !schemaVersion.matches("[A-Za-z0-9._/-]{1,32}")
                    || !validTablePurpose(tableName, purpose)) {
                throw new IllegalArgumentException("protected payload binding");
            }
        }

        public byte[] aadBytes() {
            var prefix = "sme1/aad/v2".getBytes(StandardCharsets.US_ASCII);
            var subject = subjectHash.getBytes(StandardCharsets.UTF_8);
            var schema = schemaVersion.getBytes(StandardCharsets.US_ASCII);
            var table = tableName.getBytes(StandardCharsets.US_ASCII);
            var purposeBytes = purpose.name().getBytes(StandardCharsets.US_ASCII);
            return ByteBuffer.allocate(prefix.length + Integer.BYTES + subject.length + Long.BYTES * 2
                            + Integer.BYTES + schema.length + Integer.BYTES + table.length
                            + Integer.BYTES + purposeBytes.length)
                    .put(prefix)
                    .putInt(subject.length).put(subject)
                    .putLong(recordId.getMostSignificantBits()).putLong(recordId.getLeastSignificantBits())
                    .putInt(schema.length).put(schema)
                    .putInt(table.length).put(table)
                    .putInt(purposeBytes.length).put(purposeBytes)
                    .array();
        }

        private static boolean validTablePurpose(String tableName, KeyPurpose purpose) {
            return ("memory_candidate".equals(tableName) && purpose == KeyPurpose.MEMORY_CANDIDATE)
                    || ("memory_version".equals(tableName) && purpose == KeyPurpose.MEMORY_VERSION_VALUE);
        }
    }

    /**
     * Canonical AAD derivation. Values originate only from authenticated
     * identity and the authoritative event command, never transport payload.
     */
    public record SourceMaterialBinding(
            String subjectHash,
            UUID eventId,
            String eventSchemaVersion,
            String tableName,
            KeyPurpose purpose) {

        public SourceMaterialBinding {
            if (subjectHash == null || subjectHash.isBlank() || subjectHash.length() > 512
                    || eventId == null
                    || eventSchemaVersion == null || !eventSchemaVersion.matches("[A-Za-z0-9._-]{1,16}")
                    || !INTERACTION_EVENT_TABLE.equals(tableName)
                    || purpose != KeyPurpose.INTERACTION_SOURCE) {
                throw new IllegalArgumentException("source material binding");
            }
        }

        public byte[] aadBytes() {
            var prefix = "sme1/aad/v1".getBytes(StandardCharsets.US_ASCII);
            var subject = subjectHash.getBytes(StandardCharsets.UTF_8);
            var schema = eventSchemaVersion.getBytes(StandardCharsets.US_ASCII);
            var table = tableName.getBytes(StandardCharsets.US_ASCII);
            var purposeBytes = purpose.name().getBytes(StandardCharsets.US_ASCII);
            return ByteBuffer.allocate(prefix.length + Integer.BYTES + subject.length + Long.BYTES * 2
                            + Integer.BYTES + schema.length + Integer.BYTES + table.length
                            + Integer.BYTES + purposeBytes.length)
                    .put(prefix)
                    .putInt(subject.length).put(subject)
                    .putLong(eventId.getMostSignificantBits()).putLong(eventId.getLeastSignificantBits())
                    .putInt(schema.length).put(schema)
                    .putInt(table.length).put(table)
                    .putInt(purposeBytes.length).put(purposeBytes)
                    .array();
        }
    }

    public record SealedSource(byte[] framed, InteractionPayloadKeyEnvelopeStore.Envelope envelope) {
        public SealedSource {
            framed = Objects.requireNonNull(framed, "framed").clone();
            envelope = Objects.requireNonNull(envelope, "envelope");
        }

        @Override
        public byte[] framed() {
            return framed.clone();
        }
    }
}
