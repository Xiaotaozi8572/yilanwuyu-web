package com.yilan.memory.domain.identity;

import com.yilan.memory.application.consent.ConsentQuery;
import com.yilan.memory.adapter.postgres.JdbcConsentRepository;
import com.yilan.memory.domain.consent.ConsentPolicy;
import com.yilan.memory.domain.consent.ConsentPolicy.ConsentDecision;
import com.yilan.memory.domain.consent.ConsentPolicy.DenialReason;
import com.yilan.memory.domain.consent.ConsentPolicy.MemoryCategory;
import com.yilan.memory.domain.consent.ConsentPolicy.PolicyStatus;
import com.yilan.memory.domain.consent.ConsentPolicy.SubjectStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityConsentTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
    private static final LearnerIdentity IDENTITY = new LearnerIdentity("subject-a", "session-1", 7);

    @Test
    void identityRequiresTrustedNonBlankBindingsAndNonNegativeRevision() {
        assertThatThrownBy(() -> new LearnerIdentity("", "session-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LearnerIdentity("subject-a", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LearnerIdentity("subject-a", "session-1", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absentOptInIsDenied() {
        var service = queryReturning(Optional.empty());

        assertDenied(service.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW), DenialReason.MISSING_POLICY);
    }

    @Test
    void disabledSubjectIsDeniedBeforeAnyPolicyEvaluation() {
        var service = queryReturning(Optional.of(ConsentPolicy.missing(SubjectStatus.DISABLED)));

        assertDenied(service.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW), DenialReason.SUBJECT_DISABLED);
    }

    @Test
    void staleIdentityRevisionIsDeniedRatherThanUsingHistoricalEventConsent() {
        var service = queryReturning(Optional.of(activePolicy(8, Set.of(MemoryCategory.PREFERENCE))));

        assertDenied(service.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW), DenialReason.STALE_REVISION);
    }

    @Test
    void categoryDenialAndTimeInvalidPoliciesAreDenied() {
        var categoryDenied = queryReturning(Optional.of(activePolicy(7, Set.of(MemoryCategory.MASTERY))));
        var notYetValid = queryReturning(Optional.of(new ConsentPolicy(
                SubjectStatus.ACTIVE,
                PolicyStatus.ACTIVE,
                7,
                Set.of(MemoryCategory.PREFERENCE),
                NOW.plusSeconds(1),
                null)));
        var expired = queryReturning(Optional.of(new ConsentPolicy(
                SubjectStatus.ACTIVE,
                PolicyStatus.ACTIVE,
                7,
                Set.of(MemoryCategory.PREFERENCE),
                NOW.minusSeconds(2),
                NOW.minusSeconds(1))));

        assertDenied(categoryDenied.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW), DenialReason.CATEGORY_DENIED);
        assertDenied(notYetValid.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW), DenialReason.NOT_YET_VALID);
        assertDenied(expired.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW), DenialReason.TIME_INVALID);
    }

    @Test
    void validCurrentOptInIsAllowed() {
        var service = queryReturning(Optional.of(activePolicy(7, Set.of(MemoryCategory.PREFERENCE))));

        assertThat(service.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW))
                .isEqualTo(new ConsentDecision(true, null));
    }

    @Test
    void inactivePersistedPolicyStatusesAreDenied() {
        var revoked = queryReturning(Optional.of(new ConsentPolicy(
                SubjectStatus.ACTIVE,
                PolicyStatus.REVOKED,
                7,
                Set.of(MemoryCategory.PREFERENCE),
                NOW.minusSeconds(1),
                NOW.plusSeconds(1))));
        var expiredStatus = queryReturning(Optional.of(new ConsentPolicy(
                SubjectStatus.ACTIVE,
                PolicyStatus.EXPIRED,
                7,
                Set.of(MemoryCategory.PREFERENCE),
                NOW.minusSeconds(1),
                NOW.plusSeconds(1))));

        assertDenied(revoked.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW), DenialReason.POLICY_INACTIVE);
        assertDenied(expiredStatus.evaluate(IDENTITY, MemoryCategory.PREFERENCE, NOW), DenialReason.POLICY_INACTIVE);
    }

    @Test
    void persistedConsentCategoriesRequireAnExactTopLevelStringArray() {
        assertThat(parseCategories("[\"PREFERENCE\", \"MASTERY\"]"))
                .containsExactlyInAnyOrder(MemoryCategory.PREFERENCE, MemoryCategory.MASTERY);
        assertThat(parseCategories("{\"note\":\"PREFERENCE\"}")).isEmpty();
        assertThat(parseCategories("[{\"category\":\"PREFERENCE\"}]")).isEmpty();
        assertThat(parseCategories("[\"PREFERENCE\",\"UNKNOWN\"]")).isEmpty();
        assertThat(parseCategories("[\"UNKNOWN\"]")).isEmpty();
        assertThat(parseCategories("[\"PREF\\u0045RENCE\"]")).isEmpty();
        assertThat(parseCategories("[\"PREFERENCE\"")).isEmpty();
        var verticalTab = (char) 0x0B;
        assertThat(parseCategories("[" + verticalTab + "\"PREFERENCE\"" + verticalTab + "]")).isEmpty();
    }

    private static ConsentQuery queryReturning(Optional<ConsentPolicy> policy) {
        return new ConsentQuery(subjectHash -> policy);
    }

    private static ConsentPolicy activePolicy(long revision, Set<MemoryCategory> allowedCategories) {
        return new ConsentPolicy(
                SubjectStatus.ACTIVE,
                PolicyStatus.ACTIVE,
                revision,
                allowedCategories,
                NOW.minusSeconds(1),
                NOW.plusSeconds(1));
    }

    private static void assertDenied(ConsentDecision decision, DenialReason reason) {
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(reason);
    }

    @SuppressWarnings("unchecked")
    private static Set<MemoryCategory> parseCategories(String json) {
        try {
            var parser = JdbcConsentRepository.class.getDeclaredMethod("parseCategories", String.class);
            parser.setAccessible(true);
            return (Set<MemoryCategory>) parser.invoke(null, json);
        } catch (NoSuchMethodException | IllegalAccessException error) {
            throw new AssertionError(error);
        } catch (InvocationTargetException error) {
            throw new AssertionError(error.getCause());
        }
    }
}
