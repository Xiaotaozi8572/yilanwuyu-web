package com.yilan.memory.application.privacy;

import com.yilan.memory.application.event.SubmitMemoryEventsUseCase;
import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.event.InteractionEvent;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.event.InteractionEvent.SourceKind;
import com.yilan.memory.domain.identity.LearnerIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoPlaintextLeakTest {

    private static final LearnerIdentity IDENTITY = new LearnerIdentity("p".repeat(43), "privacy-session", 1);

    @Test
    void sensitiveIngressWithoutAnExternalKeyFailsBeforeEventOrOutboxPersistence() {
        var eventWrites = new AtomicInteger();
        var outboxWrites = new AtomicInteger();
        var useCase = new SubmitMemoryEventsUseCase(
                (identity, event) -> {
                    eventWrites.incrementAndGet();
                    assertThat(new String(event.payloadCiphertext(), StandardCharsets.UTF_8))
                            .doesNotContain("sensitive ingress sentinel");
                    return SubmitMemoryEventsUseCase.InsertOutcome.INSERTED;
                },
                event -> outboxWrites.incrementAndGet(),
                ignored -> Optional.of(activePreferencePolicy()),
                PayloadProtector.disabled());

        assertThatThrownBy(() -> useCase.submit(IDENTITY, List.of(new SubmitMemoryEventsUseCase.InteractionEventCommand(
                java.util.UUID.randomUUID(), "v1", "PREFERENCE", MemoryCategory.PREFERENCE, SourceKind.GENERAL,
                IDENTITY.subjectHash(), IDENTITY.consentRevision(), Instant.now(), PrivacyLevel.SENSITIVE,
                "sensitive ingress sentinel".getBytes(StandardCharsets.UTF_8), "privacy-trace"))))
                .isInstanceOf(SecurityException.class);

        assertThat(eventWrites).hasValue(0);
        assertThat(outboxWrites).hasValue(0);
    }

    private static ConsentPolicy activePreferencePolicy() {
        return new ConsentPolicy(
                ConsentPolicy.SubjectStatus.ACTIVE,
                ConsentPolicy.PolicyStatus.ACTIVE,
                1,
                java.util.Set.of(MemoryCategory.PREFERENCE),
                Instant.EPOCH,
                null);
    }
}
