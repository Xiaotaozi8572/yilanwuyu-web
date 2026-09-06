package com.yilan.memory.adapter.postgres;

import com.yilan.memory.adapter.crypto.InMemoryTestKeyProvider;
import com.yilan.memory.application.async.CandidateProcessingService.SourceReadOutcome;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class EncryptedSourceMaterialIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private static final String SUBJECT_HASH = "source-reader-subject";
    private static final long CONSENT_REVISION = 7L;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcClient jdbcClient;

    private PayloadProtector protector;
    private JdbcAuthorizedSourceReader reader;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcInteractionPayloadKeyEnvelopeStore sourceEnvelopes;

    @BeforeEach
    void setUpAuthority() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        protector = new PayloadProtector(new InMemoryTestKeyProvider(
                key, new InMemoryTestKeyProvider.InMemoryEnvelopeStore(), sourceEnvelopes));
        reader = new JdbcAuthorizedSourceReader(
                jdbcClient,
                protector,
                properties(Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        insertSubject();
        insertPolicy(CONSENT_REVISION, "ACTIVE", "[\"PREFERENCE\"]", Instant.EPOCH,
                Instant.parse("2100-01-01T00:00:00Z"));
    }

    @AfterEach
    void clearAuthorityRows() {
        jdbcClient.sql("TRUNCATE TABLE learner_subject, transactional_outbox CASCADE").update();
    }

    @Test
    void resolvesOnlyTheSchemaNamedByTrustedOutboxAndNeverPersistsPlaintextSource() {
        var eventId = UUID.randomUUID();
        var v1Outbox = insertSealedEvent(eventId, "v1", "v1 source sentinel", NOW.minusSeconds(10));
        insertSealedEvent(eventId, "v2", "v2 source sentinel", NOW.minusSeconds(10));

        var outcome = reader.refetch(v1Outbox, eventId);
        assertThat(outcome).isInstanceOf(SourceReadOutcome.Authorized.class);
        var source = ((SourceReadOutcome.Authorized) outcome).source();

        assertThat(source.schemaVersion()).isEqualTo("v1");
        assertThat(source.authorizedSourceText()).isEqualTo("v1 source sentinel");
        assertThat(source.allowedCandidateTypes()).containsExactly(MemoryType.PREFERENCE);
        assertThat(readOutbox(v1Outbox)).doesNotContain("v1 source sentinel");
        assertThat(contains(readCiphertext(eventId, "v1"), "v1 source sentinel".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void rejectsUnsealedTamperedWrongBindingAndUnknownKeyMaterial() {
        var unsealedEvent = UUID.randomUUID();
        var unsealedOutbox = insertRawEvent(unsealedEvent, "v1", "{\"text\":\"plaintext sentinel\"}".getBytes(StandardCharsets.UTF_8),
                NOW.minusSeconds(10), PrivacyLevel.STANDARD, SourceKind.GENERAL, "PREFERENCE");
        assertThat(reader.refetch(unsealedOutbox, unsealedEvent))
                .isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var tamperedEvent = UUID.randomUUID();
        var tampered = protector.seal(binding(SUBJECT_HASH, tamperedEvent, "v1"),
                jsonText("tamper sentinel").getBytes(StandardCharsets.UTF_8));
        tampered[tampered.length - 1] ^= 1;
        var tamperedOutbox = insertRawEvent(tamperedEvent, "v1", tampered, NOW.minusSeconds(10),
                PrivacyLevel.STANDARD, SourceKind.GENERAL, "PREFERENCE");
        assertThat(reader.refetch(tamperedOutbox, tamperedEvent))
                .isInstanceOf(SourceReadOutcome.Retryable.class);

        var bindingEvent = UUID.randomUUID();
        var ciphertext = protector.seal(binding("different-authenticated-subject", bindingEvent, "v1"),
                jsonText("binding sentinel").getBytes(StandardCharsets.UTF_8));
        var bindingOutbox = insertRawEvent(bindingEvent, "v1", ciphertext, NOW.minusSeconds(10),
                PrivacyLevel.STANDARD, SourceKind.GENERAL, "PREFERENCE");
        assertThat(reader.refetch(bindingOutbox, bindingEvent))
                .isInstanceOf(SourceReadOutcome.Retryable.class);

        var sealedEvent = UUID.randomUUID();
        var sealedOutbox = insertSealedEvent(sealedEvent, "v1", "key sentinel", NOW.minusSeconds(10));
        var otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        var unknownKeyReader = new JdbcAuthorizedSourceReader(
                jdbcClient,
                new PayloadProtector(new InMemoryTestKeyProvider(otherKey)),
                properties(Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(unknownKeyReader.refetch(sealedOutbox, sealedEvent))
                .isInstanceOf(SourceReadOutcome.Retryable.class);
    }

    @Test
    void sealedSourceWithDisabledProtectorRemainsRetryableAfterAuthorityChecks() {
        var eventId = UUID.randomUUID();
        var outboxId = insertSealedEvent(eventId, "v1", "disabled protector sentinel", NOW.minusSeconds(10));
        var disabledReader = new JdbcAuthorizedSourceReader(
                jdbcClient,
                PayloadProtector.disabled(),
                properties(Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var outcome = disabledReader.refetch(outboxId, eventId);

        assertThat(outcome).isInstanceOf(SourceReadOutcome.Retryable.class);
        assertThat(((SourceReadOutcome.Retryable) outcome).diagnosticCode())
                .isEqualTo("SOURCE_KEY_OR_CIPHERTEXT_UNAVAILABLE");
    }

    @Test
    void rejectsRevocationCategoryPrivacySourceRetentionAndUtf8LimitFailures() {
        var eventId = UUID.randomUUID();
        var outboxId = insertSealedEvent(eventId, "v1", "policy sentinel", NOW.minusSeconds(10));
        insertPolicy(CONSENT_REVISION + 1, "REVOKED", "[\"PREFERENCE\"]", Instant.EPOCH,
                Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(reader.refetch(outboxId, eventId)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        clearPolicies();
        insertPolicy(CONSENT_REVISION, "ACTIVE", "[\"MASTERY\"]", Instant.EPOCH,
                Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(reader.refetch(outboxId, eventId)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        clearPolicies();
        insertPolicy(CONSENT_REVISION, "ACTIVE", "[\"PREFERENCE\"]", Instant.EPOCH,
                Instant.parse("2100-01-01T00:00:00Z"));
        var invalidSourceEvent = UUID.randomUUID();
        var invalidSourceOutbox = insertSealedEvent(invalidSourceEvent, "v1", "source kind sentinel", NOW.minusSeconds(10),
                PrivacyLevel.STANDARD, SourceKind.SCORED_ASSESSMENT, "PREFERENCE");
        assertThat(reader.refetch(invalidSourceOutbox, invalidSourceEvent))
                .isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var invalidPrivacyEvent = UUID.randomUUID();
        var invalidPrivacyCiphertext = protector.seal(binding(SUBJECT_HASH, invalidPrivacyEvent, "v1"),
                jsonText("privacy sentinel").getBytes(StandardCharsets.UTF_8));
        var invalidPrivacyOutbox = insertRawEvent(invalidPrivacyEvent, "v1", invalidPrivacyCiphertext,
                NOW.minusSeconds(10), "UNRECOGNIZED", SourceKind.GENERAL.name(), "PREFERENCE");
        assertThat(reader.refetch(invalidPrivacyOutbox, invalidPrivacyEvent))
                .isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var expiredEvent = UUID.randomUUID();
        var expiredOutbox = insertSealedEvent(expiredEvent, "v1", "expired sentinel", NOW.minus(Duration.ofHours(2)));
        assertThat(reader.refetch(expiredOutbox, expiredEvent)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var oversizedEvent = UUID.randomUUID();
        var oversizedOutbox = insertSealedEvent(oversizedEvent, "v1", "中".repeat(1366), NOW.minusSeconds(10));
        assertThat(reader.refetch(oversizedOutbox, oversizedEvent)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);
    }

    @Test
    void acceptsProducerCompatibleDigestMetadataAndRejectsUnknownOrMalformedMetadata() {
        var compatibleEvent = UUID.randomUUID();
        var compatibleOutbox = insertSealedJson(compatibleEvent, "v1", """
                {"text":"allowed","event_digest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","checkpoint_digest":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"}
                """);
        var compatible = reader.refetch(compatibleOutbox, compatibleEvent);
        assertThat(compatible).isInstanceOf(SourceReadOutcome.Authorized.class);
        assertThat(((SourceReadOutcome.Authorized) compatible).source().authorizedSourceText()).isEqualTo("allowed");

        var missingTextEvent = UUID.randomUUID();
        var missingTextOutbox = insertSealedJson(missingTextEvent, "v1", "{\"other\":\"value\"}");
        assertThat(reader.refetch(missingTextOutbox, missingTextEvent)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var nonStringTextEvent = UUID.randomUUID();
        var nonStringTextOutbox = insertSealedJson(nonStringTextEvent, "v1", "{\"text\":17}");
        assertThat(reader.refetch(nonStringTextOutbox, nonStringTextEvent)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var extraFieldEvent = UUID.randomUUID();
        var extraFieldOutbox = insertSealedJson(extraFieldEvent, "v1", "{\"text\":\"allowed\",\"extra\":\"must not reach worker\"}");
        assertThat(reader.refetch(extraFieldOutbox, extraFieldEvent)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var badDigestEvent = UUID.randomUUID();
        var badDigestOutbox = insertSealedJson(badDigestEvent, "v1", "{\"text\":\"allowed\",\"run_digest\":\"UPPERCASE\"}");
        assertThat(reader.refetch(badDigestOutbox, badDigestEvent)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var duplicateTextEvent = UUID.randomUUID();
        var duplicateTextOutbox = insertSealedJson(duplicateTextEvent, "v1", "{\"text\":\"first\",\"text\":\"second\"}");
        assertThat(reader.refetch(duplicateTextOutbox, duplicateTextEvent)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);

        var invalidJsonEvent = UUID.randomUUID();
        var invalidJsonOutbox = insertSealedJson(invalidJsonEvent, "v1", "{\"text\":");
        assertThat(reader.refetch(invalidJsonOutbox, invalidJsonEvent)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);
    }

    @Test
    void rejectsAWorkerSourceLimitAboveTheProtoContract() {
        assertThatThrownBy(() -> new MemoryProperties.WorkerSettings(
                "v1", Duration.ofMillis(40), 4_097, 6, Set.of(MemoryType.PREFERENCE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnOutboxWhosePayloadDigestDoesNotNameTheSealedEventPayload() {
        var eventId = UUID.randomUUID();
        var outboxId = insertSealedEvent(eventId, "v1", "outbox digest sentinel", NOW.minusSeconds(10));
        jdbcClient.sql("""
                        UPDATE transactional_outbox
                        SET payload = payload || CAST(:replacement AS jsonb)
                        WHERE outbox_id = :outboxId
                        """)
                .param("replacement", "{\"payload_digest\":\"" + "0".repeat(64) + "\"}")
                .param("outboxId", outboxId)
                .update();

        assertThat(reader.refetch(outboxId, eventId)).isInstanceOf(SourceReadOutcome.TerminalNoop.class);
    }

    private UUID insertSealedEvent(UUID eventId, String schemaVersion, String text, Instant occurredAt) {
        return insertSealedEvent(eventId, schemaVersion, text, occurredAt,
                PrivacyLevel.STANDARD, SourceKind.GENERAL, "PREFERENCE");
    }

    private UUID insertSealedEvent(
            UUID eventId,
            String schemaVersion,
            String text,
            Instant occurredAt,
            PrivacyLevel privacyLevel,
            SourceKind sourceKind,
            String eventType) {
        var sealed = protector.sealSource(binding(SUBJECT_HASH, eventId, schemaVersion),
                jsonText(text).getBytes(StandardCharsets.UTF_8));
        var outbox = insertRawEvent(eventId, schemaVersion, sealed.framed(), occurredAt, privacyLevel, sourceKind, eventType);
        sourceEnvelopes.storeIfAbsent(SUBJECT_HASH, eventId, schemaVersion, sealed.envelope());
        return outbox;
    }

    private UUID insertSealedJson(UUID eventId, String schemaVersion, String json) {
        var sealed = protector.sealSource(binding(SUBJECT_HASH, eventId, schemaVersion), json.getBytes(StandardCharsets.UTF_8));
        var outbox = insertRawEvent(eventId, schemaVersion, sealed.framed(), NOW.minusSeconds(10),
                PrivacyLevel.STANDARD, SourceKind.GENERAL, "PREFERENCE");
        sourceEnvelopes.storeIfAbsent(SUBJECT_HASH, eventId, schemaVersion, sealed.envelope());
        return outbox;
    }

    private UUID insertRawEvent(
            UUID eventId,
            String schemaVersion,
            byte[] ciphertext,
            Instant occurredAt,
            PrivacyLevel privacyLevel,
            SourceKind sourceKind,
            String eventType) {
        return insertRawEvent(eventId, schemaVersion, ciphertext, occurredAt, privacyLevel.name(), sourceKind.name(), eventType);
    }

    private UUID insertRawEvent(
            UUID eventId,
            String schemaVersion,
            byte[] ciphertext,
            Instant occurredAt,
            String privacyLevel,
            String sourceKind,
            String eventType) {
        jdbcClient.sql("""
                        INSERT INTO interaction_event (
                            event_id, schema_version, learner_subject_id, session_id, event_type, source_kind,
                            occurred_at, received_at, privacy_level, consent_revision, payload_ciphertext,
                            payload_digest, trace_id)
                        SELECT :eventId, :schemaVersion, learner_subject_id, 'source-reader-session', :eventType, :sourceKind,
                               :occurredAt, :receivedAt, :privacyLevel, :consentRevision, :ciphertext,
                               :payloadDigest, 'source-reader-trace'
                        FROM learner_subject
                        WHERE subject_hash = :subjectHash
                        """)
                .param("eventId", eventId)
                .param("schemaVersion", schemaVersion)
                .param("eventType", eventType)
                .param("sourceKind", sourceKind)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("receivedAt", Timestamp.from(NOW))
                .param("privacyLevel", privacyLevel)
                .param("consentRevision", CONSENT_REVISION)
                .param("ciphertext", ciphertext)
                .param("payloadDigest", sha256(ciphertext))
                .param("subjectHash", SUBJECT_HASH)
                .update();
        var outboxId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO transactional_outbox (outbox_id, aggregate_id, event_type, payload, created_at)
                        VALUES (:outboxId, :eventId, 'INTERACTION_EVENT_ACCEPTED', CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("outboxId", outboxId)
                .param("eventId", eventId)
                .param("payload", "{\"event_id\":\"" + eventId + "\",\"event_schema_version\":\"" + schemaVersion
                        + "\",\"event_type\":\"" + eventType + "\",\"payload_digest\":\"" + sha256(ciphertext) + "\"}")
                .param("createdAt", Timestamp.from(NOW))
                .update();
        return outboxId;
    }

    private void insertSubject() {
        jdbcClient.sql("""
                        INSERT INTO learner_subject (learner_subject_id, subject_hash, status, created_at)
                        VALUES (:id, :subjectHash, 'ACTIVE', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("subjectHash", SUBJECT_HASH)
                .param("createdAt", Timestamp.from(NOW))
                .update();
    }

    private void insertPolicy(long revision, String status, String categories, Instant validFrom, Instant validUntil) {
        jdbcClient.sql("""
                        INSERT INTO consent_policy_version (
                            consent_policy_version_id, learner_subject_id, revision, status,
                            allowed_categories, valid_from, valid_until, created_at)
                        SELECT :id, learner_subject_id, :revision, :status, CAST(:categories AS jsonb),
                               :validFrom, :validUntil, :createdAt
                        FROM learner_subject WHERE subject_hash = :subjectHash
                        """)
                .param("id", UUID.randomUUID())
                .param("revision", revision)
                .param("status", status)
                .param("categories", categories)
                .param("validFrom", Timestamp.from(validFrom))
                .param("validUntil", Timestamp.from(validUntil))
                .param("createdAt", Timestamp.from(NOW))
                .param("subjectHash", SUBJECT_HASH)
                .update();
    }

    private void clearPolicies() {
        jdbcClient.sql("DELETE FROM consent_policy_version").update();
    }

    private byte[] readCiphertext(UUID eventId, String schemaVersion) {
        return jdbcClient.sql("SELECT payload_ciphertext FROM interaction_event WHERE event_id = :eventId AND schema_version = :schemaVersion")
                .param("eventId", eventId)
                .param("schemaVersion", schemaVersion)
                .query(byte[].class)
                .single();
    }

    private String readOutbox(UUID outboxId) {
        return jdbcClient.sql("SELECT payload::text FROM transactional_outbox WHERE outbox_id = :outboxId")
                .param("outboxId", outboxId)
                .query(String.class)
                .single();
    }

    private static PayloadProtector.SourceMaterialBinding binding(String subjectHash, UUID eventId, String schemaVersion) {
        return new PayloadProtector.SourceMaterialBinding(
                subjectHash, eventId, schemaVersion, "interaction_event",
                com.yilan.memory.application.privacy.KeyPurpose.INTERACTION_SOURCE);
    }

    private static String jsonText(String text) {
        return "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private static MemoryProperties properties(Duration maxAge) {
        var rules = new EnumMap<MemoryType, MemoryProperties.TypeRule>(MemoryType.class);
        for (var type : MemoryType.values()) {
            rules.put(type, new MemoryProperties.TypeRule(1, new BigDecimal("0.70"), Duration.ofDays(30)));
        }
        return new MemoryProperties(
                "source-reader-v1", rules,
                new MemoryProperties.RetrievalWeights(
                        new BigDecimal("0.30"), new BigDecimal("0.20"),
                        new BigDecimal("0.50"), new BigDecimal("0.10")),
                60,
                new MemoryProperties.Budgets(8, 900),
                new MemoryProperties.WorkerSettings(
                        "v1", Duration.ofMillis(40), 4_096, 6, Set.of(MemoryType.PREFERENCE)),
                new MemoryProperties.SourceMaterialSettings(maxAge));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static boolean contains(byte[] value, byte[] needle) {
        for (var offset = 0; offset <= value.length - needle.length; offset++) {
            var matches = true;
            for (var index = 0; index < needle.length; index++) {
                if (value[offset + index] != needle[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
