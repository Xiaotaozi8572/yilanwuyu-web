package com.yilan.memory.application.consent;

import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.security.MemoryRole;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void disabledByDefaultUntilExplicitConsentAndAppendCarriesControlMetadata() {
        var store = new RecordingStore();
        var evictions = new ArrayList<String>();
        var service = service(store, evictions);

        assertThat(service.current("learner-a").longTermEnabled()).isFalse();

        var view = service.replace("learner-a", new ConsentCommand(
                true, Set.of("PREFERENCE", "MASTERY"), 14), "0", MemoryRole.LEARNER);

        assertThat(view.version()).isEqualTo("1");
        assertThat(view.longTermEnabled()).isTrue();
        assertThat(view.allowedCategories()).containsExactlyInAnyOrder("PREFERENCE", "MASTERY");
        assertThat(store.appended()).singleElement().satisfies(append -> {
            assertThat(append.policy().purposeTextVersion()).isEqualTo("memory-consent-v1");
            assertThat(append.policy().sourceCaptureEnabled()).isTrue();
            assertThat(append.policy().retentionDays()).isEqualTo(14);
            assertThat(append.policy().actorRole()).isEqualTo(MemoryRole.LEARNER);
        });
        assertThat(evictions).containsExactly("learner-a");
    }

    @Test
    void rejectsUnknownAndAviationFactCategoriesAndBroaderThanServerRetention() {
        var service = service(new RecordingStore(), new ArrayList<>());

        assertThatThrownBy(() -> service.replace("learner-a", new ConsentCommand(
                true, Set.of("AVIATION_FACT"), 14), "0", MemoryRole.LEARNER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replace("learner-a", new ConsentCommand(
                true, Set.of("PREFERENCE"), 31), "0", MemoryRole.LEARNER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleIfMatchNeverEvictsOrWrites() {
        var store = new RecordingStore();
        store.current = Optional.of(new ConsentService.StoredConsent(
                4, true, Set.of(MemoryCategory.PREFERENCE), NOW));
        var evictions = new ArrayList<String>();
        var service = service(store, evictions);

        assertThatThrownBy(() -> service.replace("learner-a", new ConsentCommand(
                true, Set.of("PREFERENCE"), 7), "3", MemoryRole.LEARNER))
                .isInstanceOf(ConsentService.StaleConsentVersionException.class);

        assertThat(store.appended()).isEmpty();
        assertThat(evictions).isEmpty();
    }

    @Test
    void sameIdempotencyKeyReplaysOriginalViewAndDoesNotInvalidateAgain() {
        var store = new RecordingStore();
        var evictions = new ArrayList<String>();
        var service = service(store, evictions);
        var key = "consent-idempotency-key-000000000101";
        var command = new ConsentCommand(true, Set.of("PREFERENCE"), 7);

        var first = service.replace("learner-a", command, "0", key, MemoryRole.LEARNER);
        var replay = service.replace("learner-a", command, "0", key, MemoryRole.LEARNER);

        assertThat(replay).isEqualTo(first);
        assertThat(store.idempotencyKeys()).containsExactly(key, key);
        assertThat(store.appended()).hasSize(1);
        assertThat(evictions).containsExactly("learner-a");
    }

    @Test
    void sameIdempotencyKeyWithDifferentRequestIsAConflict() {
        var service = service(new RecordingStore(), new ArrayList<>());
        var key = "consent-idempotency-key-000000000102";

        service.replace("learner-a", new ConsentCommand(true, Set.of("PREFERENCE"), 7), "0", key, MemoryRole.LEARNER);

        assertThatThrownBy(() -> service.replace(
                "learner-a", new ConsentCommand(true, Set.of("MASTERY"), 7), "0", key, MemoryRole.LEARNER))
                .isInstanceOf(ConsentService.IdempotencyConflictException.class);
    }

    @Test
    void applicationServiceHasOneExplicitSpringWiringConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ConsentService.ConsentStore.class, RecordingStore::new);
            context.register(ConsentService.class, PolicyEpochService.class);
            context.refresh();

            assertThat(context.getBean(ConsentService.class).current("learner-a").longTermEnabled()).isFalse();
        }
    }

    private static ConsentService service(RecordingStore store, List<String> evictions) {
        return new ConsentService(
                store,
                new PolicyEpochService(evictions::add),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "memory-consent-v1",
                Duration.ofDays(30));
    }

    private static final class RecordingStore implements ConsentService.ConsentStore {
        private Optional<ConsentService.StoredConsent> current = Optional.empty();
        private final List<Append> appended = new ArrayList<>();

        @Override
        public Optional<ConsentService.StoredConsent> find(String subjectHash) {
            return current;
        }

        @Override
        public ConsentService.Commit append(String subjectHash, long expectedRevision, ConsentService.Policy policy) {
            var actual = current.map(ConsentService.StoredConsent::revision).orElse(0L);
            if (actual != expectedRevision) {
                throw new ConsentService.StaleConsentVersionException();
            }
            var revision = actual + 1;
            appended.add(new Append(subjectHash, expectedRevision, policy));
            current = Optional.of(new ConsentService.StoredConsent(
                    revision, policy.longTermEnabled(), policy.allowedCategories(), policy.effectiveAt()));
            return new ConsentService.Commit(revision, 1);
        }

        @Override
        public ConsentService.AppendResult append(
                String subjectHash,
                long expectedRevision,
                ConsentService.Policy policy,
                String idempotencyKey) {
            idempotencyKeys.add(idempotencyKey);
            var request = expectedRevision + "|" + policy.longTermEnabled() + "|"
                    + policy.allowedCategories().stream().map(Enum::name).sorted().toList() + "|" + policy.retentionDays();
            if (receiptKey != null && receiptKey.equals(idempotencyKey)) {
                if (!receiptRequest.equals(request)) {
                    throw new ConsentService.IdempotencyConflictException();
                }
                return new ConsentService.AppendResult(receiptResult, 0, true);
            }
            var commit = append(subjectHash, expectedRevision, policy);
            receiptKey = idempotencyKey;
            receiptRequest = request;
            receiptResult = current.orElseThrow();
            return new ConsentService.AppendResult(receiptResult, 1, false);
        }

        List<Append> appended() {
            return List.copyOf(appended);
        }

        List<String> idempotencyKeys() {
            return List.copyOf(idempotencyKeys);
        }

        private final List<String> idempotencyKeys = new ArrayList<>();
        private String receiptKey;
        private String receiptRequest;
        private ConsentService.StoredConsent receiptResult;
    }

    private record Append(String subjectHash, long expectedRevision, ConsentService.Policy policy) {
    }
}
