package com.yilan.memory.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossLearnerIsolationTest {

    private static final String ISSUER = "https://issuer.test.memory";
    private static final String SUBJECT_HMAC_KEY = "test-only-subject-hmac-key-that-is-long-enough";
    private static final SecretKey JWT_KEY = new SecretKeySpec(
            "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");

    @Test
    void verifiedSubjectsArePseudonymousAndCannotCrossLearnerBoundaries() throws Exception {
        var converter = new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY);
        var learnerA = (AuthenticatedSubject) converter.convert(decoder().decode(signed("learner-a", "LEARNER"))).getPrincipal();
        var learnerB = (AuthenticatedSubject) converter.convert(decoder().decode(signed("learner-b", "LEARNER"))).getPrincipal();

        assertThat(learnerA.subjectHash()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(learnerB.subjectHash());
        assertThat(learnerA.subjectHash()).doesNotContain("learner-a", "learner-b");
        assertThat(learnerA.roles()).containsExactly(MemoryRole.LEARNER);
    }

    @Test
    void tamperedOrUnrecognizedRoleTokensCannotEstablishASubject() throws Exception {
        var valid = signed("learner-a", "LEARNER");
        assertThatThrownBy(() -> decoder().decode(valid + "x")).isInstanceOf(RuntimeException.class);

        var converter = new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY);
        assertThatThrownBy(() -> converter.convert(decoder().decode(signed("learner-a", "SUPERUSER"))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void onlyVerifiedRecentNumericAuthTimeCreatesTheFreshAuthenticationMarker() throws Exception {
        var now = Instant.parse("2026-07-22T00:00:00Z");
        var converter = new SubjectAuthenticationConverter(
                SUBJECT_HMAC_KEY, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));

        var fresh = (AuthenticatedSubject) converter.convert(decoder().decode(
                signed("learner-a", "LEARNER", now.minusSeconds(60).getEpochSecond()))).getPrincipal();
        var expired = (AuthenticatedSubject) converter.convert(decoder().decode(
                signed("learner-a", "LEARNER", now.minusSeconds(301).getEpochSecond()))).getPrincipal();
        var future = (AuthenticatedSubject) converter.convert(decoder().decode(
                signed("learner-a", "LEARNER", now.plusSeconds(1).getEpochSecond()))).getPrincipal();
        var malformed = (AuthenticatedSubject) converter.convert(decoder().decode(
                signed("learner-a", "LEARNER", "not-an-epoch"))).getPrincipal();

        assertThat(fresh.freshAuthentication()).isTrue();
        assertThat(expired.freshAuthentication()).isFalse();
        assertThat(future.freshAuthentication()).isFalse();
        assertThat(malformed.freshAuthentication()).isFalse();
        assertThat(converter.fromVerifiedClaims("learner-a", List.of("LEARNER")).freshAuthentication()).isFalse();
    }

    @Test
    void missingFreshAuthenticationAgeFailsClosedWhileAnExplicitPositiveAgeCanPermitIt() throws Exception {
        var now = Instant.parse("2026-07-22T00:00:00Z");
        var token = decoder().decode(signed("learner-a", "LEARNER", now.minusSeconds(60).getEpochSecond()));

        var missing = new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY);
        var blankEquivalent = new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY, null, Clock.fixed(now, ZoneOffset.UTC));
        var configured = new SubjectAuthenticationConverter(
                SUBJECT_HMAC_KEY, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(((AuthenticatedSubject) missing.convert(token).getPrincipal()).freshAuthentication()).isFalse();
        assertThat(((AuthenticatedSubject) blankEquivalent.convert(token).getPrincipal()).freshAuthentication()).isFalse();
        assertThat(((AuthenticatedSubject) configured.convert(token).getPrincipal()).freshAuthentication()).isTrue();
    }

    private static NimbusJwtDecoder decoder() {
        var decoder = NimbusJwtDecoder.withSecretKey(JWT_KEY).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    private static String signed(String subject, String role) throws Exception {
        return signed(subject, role, null);
    }

    private static String signed(String subject, String role, Object authTime) throws Exception {
        var now = Instant.now();
        var builder = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience("memory-service-test")
                .subject(subject)
                .claim("roles", List.of(role))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)));
        if (authTime != null) {
            builder.claim("auth_time", authTime);
        }
        var claims = builder.build();
        var signed = new SignedJWT(new com.nimbusds.jose.JWSHeader(JWSAlgorithm.HS256), claims);
        signed.sign(new MACSigner(JWT_KEY.getEncoded()));
        return signed.serialize();
    }
}
