package com.yilan.memory.adapter.postgres;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yilan.memory.application.async.CandidateProcessingService.AuthorizedSource;
import com.yilan.memory.application.async.CandidateProcessingService.AuthoritySourceReader;
import com.yilan.memory.application.async.CandidateProcessingService.SourceReadOutcome;
import com.yilan.memory.application.privacy.KeyPurpose;
import com.yilan.memory.application.privacy.PayloadProtector;
import com.yilan.memory.config.MemoryProperties;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL authority reader for the M3 local-demo/test source-material
 * bridge. It uses a trusted outbox row to select the exact event schema, then
 * fail-closes before any plaintext is exposed outside Java memory.
 */
public final class JdbcAuthorizedSourceReader implements AuthoritySourceReader {

    private static final String INTERACTION_EVENT_ACCEPTED = "INTERACTION_EVENT_ACCEPTED";
    private static final String INTERACTION_EVENT_TABLE = "interaction_event";
    private static final byte[] SME1_MAGIC = {'S', 'M', 'E', '1'};
    private static final Set<String> ALLOWED_PAYLOAD_FIELDS = Set.of(
            "text", "event_digest", "checkpoint_digest", "run_digest", "turn_digest");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final JdbcClient jdbcClient;
    private final PayloadProtector payloadProtector;
    private final MemoryProperties properties;
    private final Clock clock;

    public JdbcAuthorizedSourceReader(
            JdbcClient jdbcClient,
            PayloadProtector payloadProtector,
            MemoryProperties properties) {
        this(jdbcClient, payloadProtector, properties, Clock.systemUTC());
    }

    JdbcAuthorizedSourceReader(
            JdbcClient jdbcClient,
            PayloadProtector payloadProtector,
            MemoryProperties properties,
            Clock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadProtector = Objects.requireNonNull(payloadProtector, "payloadProtector");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SourceReadOutcome refetch(UUID outboxId, UUID eventId) {
        if (outboxId == null || eventId == null) {
            return SourceReadOutcome.terminalNoop();
        }
        try {
            var row = jdbcClient.sql("""
                            SELECT event.event_id,
                                   event.schema_version,
                                   event.event_type,
                                   event.source_kind,
                                   event.occurred_at,
                                   event.privacy_level,
                                   event.consent_revision,
                                   event.payload_ciphertext,
                                   event.payload_digest,
                                   outbox.payload ->> 'payload_digest' AS outbox_payload_digest,
                                   event.trace_id,
                                   subject.subject_hash,
                                   subject.status AS subject_status,
                                   policy.revision AS policy_revision,
                                   policy.status AS policy_status,
                                   policy.allowed_categories::text AS allowed_categories,
                                   policy.valid_from,
                                   policy.valid_until,
                                   envelope.event_id IS NOT NULL AS has_source_envelope,
                                   EXISTS (SELECT 1 FROM retention_erasure erasure
                                           WHERE erasure.target_kind = 'INTERACTION_PAYLOAD'
                                             AND erasure.target_id = event.event_id
                                             AND erasure.event_schema_version = event.schema_version
                                             AND erasure.learner_subject_id = event.learner_subject_id) AS source_erased
                            FROM transactional_outbox outbox
                            JOIN interaction_event event
                              ON event.event_id = outbox.aggregate_id
                             AND event.schema_version = outbox.payload ->> 'event_schema_version'
                            JOIN learner_subject subject
                              ON subject.learner_subject_id = event.learner_subject_id
                            LEFT JOIN LATERAL (
                                SELECT revision, status, allowed_categories, valid_from, valid_until
                                FROM consent_policy_version
                                WHERE learner_subject_id = subject.learner_subject_id
                                ORDER BY revision DESC, created_at DESC
                                LIMIT 1
                            ) policy ON TRUE
                            LEFT JOIN interaction_payload_key_envelope envelope
                              ON envelope.event_id = event.event_id
                             AND envelope.schema_version = event.schema_version
                             AND envelope.learner_subject_id = event.learner_subject_id
                            WHERE outbox.outbox_id = :outboxId
                              AND outbox.aggregate_id = :eventId
                              AND outbox.event_type = :eventType
                              AND outbox.payload ->> 'event_id' = CAST(:eventId AS text)
                            """)
                    .param("outboxId", outboxId)
                    .param("eventId", eventId)
                    .param("eventType", INTERACTION_EVENT_ACCEPTED)
                    .query(this::mapRow)
                    .optional();
            return row.map(this::authorizeAndMinimize).orElseGet(SourceReadOutcome::terminalNoop);
        } catch (RuntimeException unavailable) {
            return SourceReadOutcome.retryable("SOURCE_AUTHORITY_UNAVAILABLE");
        }
    }

    private SourceReadOutcome authorizeAndMinimize(SourceRow row) {
        try {
            var now = Instant.now(clock);
            var category = MemoryCategory.valueOf(row.eventType());
            var privacy = PrivacyLevel.valueOf(row.privacyLevel());
            var sourceKind = SourceKind.valueOf(row.sourceKind());
            if (!"ACTIVE".equals(row.subjectStatus())
                    || !"ACTIVE".equals(row.policyStatus())
                    || row.policyRevision() == null
                    || row.policyRevision() != row.consentRevision()
                    || row.policyValidFrom() == null
                    || row.policyValidFrom().isAfter(now)
                    || (row.policyValidUntil() != null && !row.policyValidUntil().isAfter(now))
                    || !parseCategories(row.allowedCategories()).contains(category)
                    || !validSourceKind(category, sourceKind)
                    || row.sourceErased()
                    || !withinLocalDemoRetention(row.occurredAt(), now)
                    || !matchesPayloadDigest(row.payloadCiphertext(), row.payloadDigest())
                    || !matchesOutboxPayloadDigest(row.payloadDigest(), row.outboxPayloadDigest())) {
                return SourceReadOutcome.terminalNoop();
            }
            if (!isSme1Frame(row.payloadCiphertext())) {
                return SourceReadOutcome.terminalNoop();
            }
            var sourceText = decryptAndExtractText(row);
            if (sourceText == null) {
                return SourceReadOutcome.terminalNoop();
            }
            if (sourceText.getBytes(StandardCharsets.UTF_8).length > properties.worker().maxSourceBytes()) {
                return SourceReadOutcome.terminalNoop();
            }
            return SourceReadOutcome.authorized(new AuthorizedSource(
                    row.eventId(), row.schemaVersion(), row.subjectHash(), row.consentRevision(), sourceText,
                    sha256(sourceText), privacy, sourceKind, row.occurredAt(),
                    allowedTypesFor(category), "und", "consent-revision-" + row.policyRevision(), row.traceId()));
        } catch (RetryableSourceMaterialException retryable) {
            return SourceReadOutcome.retryable("SOURCE_KEY_OR_CIPHERTEXT_UNAVAILABLE");
        } catch (RuntimeException invalidAuthority) {
            return SourceReadOutcome.terminalNoop();
        }
    }

    /**
     * Decryption is intentionally isolated after all authority, retention and
     * ciphertext-digest checks. Failures disclose neither key nor payload
     * material and remain replayable because a local key or transient cipher
     * capability may later become available.
     */
    private String decryptAndExtractText(SourceRow row) {
        try {
            var plaintext = payloadProtector.open(
                    new PayloadProtector.SourceMaterialBinding(
                            row.subjectHash(), row.eventId(), row.schemaVersion(),
                            INTERACTION_EVENT_TABLE, KeyPurpose.INTERACTION_SOURCE),
                    row.payloadCiphertext());
            return strictText(plaintext);
        } catch (RuntimeException unavailableKeyOrCiphertext) {
            throw new RetryableSourceMaterialException();
        }
    }

    private SourceRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SourceRow(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("schema_version"),
                resultSet.getString("event_type"),
                resultSet.getString("source_kind"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getString("privacy_level"),
                resultSet.getLong("consent_revision"),
                resultSet.getBytes("payload_ciphertext"),
                resultSet.getString("payload_digest"),
                resultSet.getString("outbox_payload_digest"),
                resultSet.getString("trace_id"),
                resultSet.getString("subject_hash"),
                resultSet.getString("subject_status"),
                resultSet.getObject("policy_revision", Long.class),
                resultSet.getString("policy_status"),
                resultSet.getString("allowed_categories"),
                resultSet.getTimestamp("valid_from") == null ? null : resultSet.getTimestamp("valid_from").toInstant(),
                resultSet.getTimestamp("valid_until") == null ? null : resultSet.getTimestamp("valid_until").toInstant(),
                resultSet.getBoolean("has_source_envelope"),
                resultSet.getBoolean("source_erased"));
    }

    private boolean withinLocalDemoRetention(Instant occurredAt, Instant now) {
        if (occurredAt == null || occurredAt.isAfter(now)) {
            return false;
        }
        Duration maximumAge = properties.sourceMaterial().localDemoMaximumAge();
        return !occurredAt.isBefore(now.minus(maximumAge));
    }

    private static String strictText(byte[] plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(plaintext);
            if (root == null || !root.isObject() || root.size() == 0 || !root.has("text")) {
                return null;
            }
            for (var fields = root.fields(); fields.hasNext();) {
                var field = fields.next();
                if (!ALLOWED_PAYLOAD_FIELDS.contains(field.getKey())
                        || !field.getValue().isTextual()
                        || field.getValue().textValue() == null) {
                    return null;
                }
                if (!"text".equals(field.getKey()) && !field.getValue().textValue().matches("[0-9a-f]{64}")) {
                    return null;
                }
            }
            var text = root.get("text").textValue();
            return text.isBlank() ? null : text;
        } catch (Exception malformed) {
            return null;
        }
    }

    private static EnumSet<MemoryCategory> parseCategories(String json) {
        if (json == null) {
            return EnumSet.noneOf(MemoryCategory.class);
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray()) {
                return EnumSet.noneOf(MemoryCategory.class);
            }
            var categories = EnumSet.noneOf(MemoryCategory.class);
            for (JsonNode element : root) {
                if (!element.isTextual()) {
                    return EnumSet.noneOf(MemoryCategory.class);
                }
                categories.add(MemoryCategory.valueOf(element.textValue()));
            }
            return categories;
        } catch (Exception malformed) {
            return EnumSet.noneOf(MemoryCategory.class);
        }
    }

    private static boolean validSourceKind(MemoryCategory category, SourceKind sourceKind) {
        return switch (sourceKind) {
            case GENERAL -> true;
            case EXPLICIT_DECLARATION -> category == MemoryCategory.PREFERENCE;
            case EPISODE -> category == MemoryCategory.REFLECTION;
            case SCORED_ASSESSMENT -> category == MemoryCategory.MASTERY;
        };
    }

    private static Set<MemoryType> allowedTypesFor(MemoryCategory category) {
        return switch (category) {
            case PREFERENCE -> Set.of(MemoryType.PREFERENCE);
            case MASTERY -> Set.of(MemoryType.MASTERY);
            case MISCONCEPTION -> Set.of(MemoryType.MISCONCEPTION);
            case REFLECTION -> Set.of(MemoryType.REFLECTION);
        };
    }

    private static boolean matchesPayloadDigest(byte[] payloadCiphertext, String declaredDigest) {
        if (payloadCiphertext == null || declaredDigest == null || !declaredDigest.matches("[0-9a-f]{64}")) {
            return false;
        }
        var actualDigest = sha256(payloadCiphertext);
        return MessageDigest.isEqual(
                declaredDigest.getBytes(StandardCharsets.US_ASCII),
                actualDigest.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isSme1Frame(byte[] payloadCiphertext) {
        if (payloadCiphertext == null || payloadCiphertext.length < SME1_MAGIC.length) {
            return false;
        }
        for (var index = 0; index < SME1_MAGIC.length; index++) {
            if (payloadCiphertext[index] != SME1_MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesOutboxPayloadDigest(String eventPayloadDigest, String outboxPayloadDigest) {
        if (eventPayloadDigest == null || outboxPayloadDigest == null
                || !outboxPayloadDigest.matches("[0-9a-f]{64}")) {
            return false;
        }
        return MessageDigest.isEqual(
                eventPayloadDigest.getBytes(StandardCharsets.US_ASCII),
                outboxPayloadDigest.getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(String sourceText) {
        return sha256(sourceText.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private record SourceRow(
            UUID eventId,
            String schemaVersion,
            String eventType,
            String sourceKind,
            Instant occurredAt,
            String privacyLevel,
            long consentRevision,
            byte[] payloadCiphertext,
            String payloadDigest,
            String outboxPayloadDigest,
            String traceId,
            String subjectHash,
            String subjectStatus,
            Long policyRevision,
            String policyStatus,
            String allowedCategories,
            Instant policyValidFrom,
            Instant policyValidUntil,
            boolean hasSourceEnvelope,
            boolean sourceErased) {
    }

    private static final class RetryableSourceMaterialException extends RuntimeException {
        private RetryableSourceMaterialException() {
            super(null, null, false, false);
        }
    }
}
