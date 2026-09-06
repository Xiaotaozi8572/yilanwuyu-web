package com.yilan.memory.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubjectAuthorizationTest {

    private static final String ISSUER = "https://issuer.test.memory";
    private static final String AUDIENCE = "memory-service-test";
    private static final SecretKey JWT_KEY = new SecretKeySpec(
            "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TestEndpoints.memoryControllerCalls.set(0);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("security-test", Map.of(
                "memory.security.subject-hmac-key", "test-only-subject-hmac-key-that-is-long-enough")));
        context.register(FixtureConfiguration.class, SecurityConfiguration.class, TestEndpoints.class);
        context.refresh();
        mvc = MockMvcBuilders.standaloneSetup(new TestEndpoints())
                .apply(springSecurity(context.getBean(org.springframework.security.web.FilterChainProxy.class)))
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void learnerCannotSelectSubjectThroughBodyPathOrQuery() throws Exception {
        mvc.perform(get("/v1/me/memories?learner_id=learner-b").header("Authorization", bearer("learner-a", "LEARNER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/learners/learner-b/memories").header("Authorization", bearer("learner-a", "LEARNER")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/me/memories")
                        .header("Authorization", bearer("learner-a", "LEARNER"))
                        .contentType("application/json")
                        .content("{\"subject_id\":\"learner-b\"}"))
                .andExpect(status().isBadRequest());

        assertThat(TestEndpoints.memoryControllerCalls).hasValue(0);
    }

    @Test
    void learnerCannotSelectSubjectThroughJsonEscapedIdentityKeyBeforeControllerLogic() throws Exception {
        mvc.perform(get("/v1/me/memories")
                        .header("Authorization", bearer("learner-a", "LEARNER"))
                        .contentType("application/json")
                        .content("{\"subject\\u005fid\":\"learner-b\"}"))
                .andExpect(status().isBadRequest());

        assertThat(TestEndpoints.memoryControllerCalls).hasValue(0);
    }

    @Test
    void learnerCannotBypassBodyIdentityRejectionWithUtf16Json() throws Exception {
        mvc.perform(post("/v1/me/memories")
                        .header("Authorization", bearer("learner-a", "LEARNER"))
                        .contentType("application/json;charset=UTF-16LE")
                        .characterEncoding("UTF-16LE")
                        .content("\ufeff{\"subject_id\":\"learner-b\"}".getBytes(java.nio.charset.StandardCharsets.UTF_16LE)))
                .andExpect(status().isBadRequest());

        assertThat(TestEndpoints.memoryControllerCalls).hasValue(0);
    }

    @Test
    void learnerCannotSelectSubjectThroughFormEncodedIdentityKeyBeforeControllerLogic() throws Exception {
        mvc.perform(get("/v1/me/memories")
                        .header("Authorization", bearer("learner-a", "LEARNER"))
                        .contentType("application/x-www-form-urlencoded")
                        .content("subject%5fid=learner-b"))
                .andExpect(status().isBadRequest());

        assertThat(TestEndpoints.memoryControllerCalls).hasValue(0);
    }

    @Test
    void identitySelectorsInBodiesAreRejectedEvenWhenTheMimeTypeLies() throws Exception {
        var authorization = bearer("learner-a", "LEARNER");

        mvc.perform(post("/v1/me/memories")
                        .header("Authorization", authorization)
                        .contentType("text/plain")
                        .content("{\"subject\\u005fid\":\"learner-b\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/me/memories")
                        .header("Authorization", authorization)
                        .contentType("text/plain")
                        .content("subject%5fid=learner-b"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/me/memories")
                        .header("Authorization", authorization)
                        .contentType("text/plain")
                        .content("--boundary\r\nContent-Disposition: form-data; name=\"subject_id\"\r\n\r\nlearner-b\r\n--boundary--\r\n"))
                .andExpect(status().isBadRequest());

        assertThat(TestEndpoints.memoryControllerCalls).hasValue(0);
    }

    @Test
    void percentEncodedAndMatrixIdentitySelectorsInPathsAreRejectedBeforeControllerLogic() throws Exception {
        var authorization = bearer("learner-a", "LEARNER");

        mvc.perform(get("/v1/me/memories%3Bsubject%5fid=learner-b")
                        .header("Authorization", authorization))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/me/memories;subject_id=learner-b")
                        .header("Authorization", authorization))
                .andExpect(status().isBadRequest());

        assertThat(TestEndpoints.memoryControllerCalls).hasValue(0);
    }

    @Test
    void filterRejectsEncodedAndMatrixPathSelectorsBeforeAnyDownstreamFilter() throws Exception {
        var filter = new SubjectBindingFilter(1_024, 512);
        for (var requestUri : List.of(
                "/v1/me/memories%3Bsubject%5fid=learner-b",
                "/v1/me/memories;subject_id=learner-b")) {
            var request = new MockHttpServletRequest("GET", requestUri);
            request.setRequestURI(requestUri);
            var response = new MockHttpServletResponse();
            var downstreamCalls = new AtomicInteger();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> downstreamCalls.incrementAndGet());

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(downstreamCalls).hasValue(0);
        }
    }

    @Test
    void replayedRequestKeepsReaderAndFormParametersAvailableToTheController() throws Exception {
        var response = mvc.perform(post("/v1/me/memories")
                        .header("Authorization", bearer("learner-a", "LEARNER"))
                        .contentType("application/x-www-form-urlencoded")
                        .content("note=hello%20world"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("note=hello%20world", "hello world")
                .doesNotContain("drained");
        assertThat(TestEndpoints.memoryControllerCalls).hasValue(1);
    }

    @Test
    void auditorCanReadMinimizedAuditButCannotReadMemoryPayload() throws Exception {
        var audit = mvc.perform(get("/v1/admin/audit").header("Authorization", bearer("auditor-a", "AUDITOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/v1/me/memories").header("Authorization", bearer("auditor-a", "AUDITOR")))
                .andExpect(status().isForbidden());

        assertThat(audit).contains("event_id", "action", "occurred_at")
                .doesNotContain("payload", "memory", "subject_hash");
    }

    @Test
    void verifiedJwtIsBoundToPseudonymousSubjectBeforeControllerLogic() throws Exception {
        var response = mvc.perform(get("/v1/me/memories").header("Authorization", bearer("learner-a", "LEARNER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("learner-a").contains("subject_hash");
        assertThat(TestEndpoints.memoryControllerCalls).hasValue(1);
    }

    @Test
    void oversizeTraceIsRejectedBeforeControllerLogic() throws Exception {
        mvc.perform(get("/v1/me/memories")
                        .header("Authorization", bearer("learner-a", "LEARNER"))
                        .header("traceparent", "x".repeat(513)))
                .andExpect(status().isBadRequest());

        assertThat(TestEndpoints.memoryControllerCalls).hasValue(0);
    }

    @Test
    void cookieAuthenticatedLearnerCanReadSelfServiceMemories() throws Exception {
        mvc.perform(get("/v1/me/memories").cookie(accessCookie(rawToken("learner-a", "LEARNER"))))
                .andExpect(status().isOk());
    }

    @Test
    void cookieAuthenticatedUnsafeRequestRequiresCsrfButHeaderBearerRemainsCompatible() throws Exception {
        var access = accessCookie(rawToken("learner-a", "LEARNER"));

        var request = new MockHttpServletRequest("POST", "/v1/me/memories");
        request.setRequestURI("/v1/me/memories");
        request.setCookies(access);
        var response = new MockHttpServletResponse();
        var downstreamCalls = new AtomicInteger();
        context.getBean(org.springframework.security.web.FilterChainProxy.class)
                .doFilter(request, response, (ignoredRequest, ignoredResponse) -> downstreamCalls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(downstreamCalls).hasValue(0);
        mvc.perform(post("/v1/me/memories").header(HttpHeaders.AUTHORIZATION, bearer("learner-a", "LEARNER"))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void cookieAuthenticatedUnsafeRequestWithCsrfSucceeds() throws Exception {
        var csrf = mvc.perform(get("/memory-control/"))
                .andReturn().getResponse().getCookie("__Host-memory-csrf");

        mvc.perform(post("/v1/me/memories").cookie(accessCookie(rawToken("learner-a", "LEARNER")), csrf)
                        .header("X-Memory-CSRF", csrf.getValue())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void cookieCredentialCannotAccessAdminAudit() throws Exception {
        mvc.perform(get("/v1/admin/audit").cookie(accessCookie(rawToken("learner-a", "AUDITOR"))))
                .andExpect(status().isUnauthorized());
    }

    private static jakarta.servlet.http.Cookie accessCookie(String token) {
        return new jakarta.servlet.http.Cookie("__Host-memory-access", token);
    }

    private static String rawToken(String subject, String role) throws Exception {
        return bearer(subject, role).substring("Bearer ".length());
    }

    private static String bearer(String subject, String role) throws Exception {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(subject)
                .claim("roles", List.of(role))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .build();
        var signed = new SignedJWT(new com.nimbusds.jose.JWSHeader(JWSAlgorithm.HS256), claims);
        signed.sign(new MACSigner(JWT_KEY.getEncoded()));
        return "Bearer " + signed.serialize();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class FixtureConfiguration {

        @Bean
        JwtDecoder testJwtDecoder() {
            var decoder = NimbusJwtDecoder.withSecretKey(JWT_KEY).macAlgorithm(MacAlgorithm.HS256).build();
            decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }

    @RestController
    @RequestMapping
    static class TestEndpoints {
        private static final AtomicInteger memoryControllerCalls = new AtomicInteger();

        @GetMapping("/v1/me/memories")
        Map<String, String> memories(HttpServletRequest request) {
            memoryControllerCalls.incrementAndGet();
            return Map.of("subject_hash", SubjectBindingFilter.requireBoundSubject(request).subjectHash());
        }

        @GetMapping("/v1/admin/audit")
        Map<String, String> audit() {
            var value = new LinkedHashMap<String, String>();
            value.put("event_id", "audit-1");
            value.put("action", "READ_AUDIT");
            value.put("occurred_at", "2026-07-21T00:00:00Z");
            return value;
        }

        @PostMapping("/v1/me/memories")
        Map<String, String> postedMemory(HttpServletRequest request) throws java.io.IOException {
            memoryControllerCalls.incrementAndGet();
            var value = new LinkedHashMap<String, String>();
            value.put("subject_hash", SubjectBindingFilter.requireBoundSubject(request).subjectHash());
            value.put("reader", request.getReader().readLine());
            value.put("note", request.getParameter("note"));
            return value;
        }
    }
}
