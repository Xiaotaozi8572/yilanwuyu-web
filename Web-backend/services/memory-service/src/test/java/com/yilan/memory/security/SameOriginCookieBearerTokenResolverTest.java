package com.yilan.memory.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class SameOriginCookieBearerTokenResolverTest {

    private final SameOriginCookieBearerTokenResolver resolver = new SameOriginCookieBearerTokenResolver();
    private final SameOriginCookieBearerTokenRelayFilter relayFilter =
            new SameOriginCookieBearerTokenRelayFilter(resolver);

    @Test
    void resolvesSoleNonblankAccessCookieOnlyForCanonicalSelfServicePaths() {
        assertAll(List.of("/v1/me", "/v1/me/memories").stream()
                .map(path -> (org.junit.jupiter.api.function.Executable) () -> {
                    var request = request(path);
                    request.setCookies(new Cookie("__Host-memory-access", "jwt-from-gateway"));

                    assertThat(resolver.resolveCookie(request)).isEqualTo("jwt-from-gateway");
                })
                .toList());
    }

    @Test
    void authorizationHeaderTakesPrecedenceOverCookie() throws Exception {
        var request = request("/v1/me/memories");
        request.addHeader("Authorization", "Bearer header-jwt");
        request.setCookies(new Cookie("__Host-memory-access", "cookie-jwt"));
        var downstreamRequest = new AtomicReference<jakarta.servlet.http.HttpServletRequest>();

        relayFilter.doFilter(request, new MockHttpServletResponse(), (actualRequest, ignoredResponse) ->
                downstreamRequest.set((jakarta.servlet.http.HttpServletRequest) actualRequest));

        assertThat(downstreamRequest.get().getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer header-jwt");
    }

    @Test
    void doesNotFallBackToCookieAfterMalformedAuthorizationHeader() throws Exception {
        var request = request("/v1/me/memories");
        request.addHeader("Authorization", "Bearer invalid token");
        request.setCookies(new Cookie("__Host-memory-access", "cookie-jwt"));
        var downstreamRequest = new AtomicReference<jakarta.servlet.http.HttpServletRequest>();

        relayFilter.doFilter(request, new MockHttpServletResponse(), (actualRequest, ignoredResponse) ->
                downstreamRequest.set((jakarta.servlet.http.HttpServletRequest) actualRequest));

        assertThat(downstreamRequest.get().getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer invalid token");
    }

    @Test
    void doesNotFallBackToCookieAfterDuplicateAuthorizationHeaders() throws Exception {
        var request = request("/v1/me/memories");
        request.addHeader("Authorization", "Bearer first-header-jwt");
        request.addHeader("Authorization", "Bearer second-header-jwt");
        request.setCookies(new Cookie("__Host-memory-access", "cookie-jwt"));
        var downstreamRequest = new AtomicReference<jakarta.servlet.http.HttpServletRequest>();

        relayFilter.doFilter(request, new MockHttpServletResponse(), (actualRequest, ignoredResponse) ->
                downstreamRequest.set((jakarta.servlet.http.HttpServletRequest) actualRequest));

        assertThat(Collections.list(downstreamRequest.get().getHeaders(HttpHeaders.AUTHORIZATION)))
                .containsExactly("Bearer first-header-jwt", "Bearer second-header-jwt");
    }

    @Test
    void rejectsDuplicateEligibleCookies() {
        var request = request("/v1/me/memories");
        request.setCookies(
                new Cookie("__Host-memory-access", "first-jwt"),
                new Cookie("__Host-memory-access", "second-jwt"));

        assertThat(resolver.resolveCookie(request)).isNull();
    }

    @Test
    void rejectsBlankAccessCookies() {
        var request = request("/v1/me/memories");
        request.setCookies(new Cookie("__Host-memory-access", " "));

        assertThat(resolver.resolveCookie(request)).isNull();
    }

    @Test
    void neverResolvesCookieForAdminRoutes() {
        var request = request("/v1/admin/audit");
        request.setCookies(new Cookie("__Host-memory-access", "jwt-from-gateway"));

        assertThat(resolver.resolveCookie(request)).isNull();
    }

    @Test
    void rejectsNonCanonicalSelfServicePathsWithoutSynthesizingAuthorization() {
        var nonCanonicalPaths = List.of(
                "/v1/me/../admin/audit",
                "/v1/me/./memories",
                "/v1/me/%2e%2e/admin/audit",
                "/v1/me/%252e%252e/admin/audit",
                "/v1/me/memories;v=1",
                "/v1/me/..\\admin/audit",
                "/v1/me//memories",
                "/v1/me/%",
                "/v1/me/[malformed");

        assertAll(nonCanonicalPaths.stream()
                .map(path -> (org.junit.jupiter.api.function.Executable) () -> assertNoCookieRelay(path))
                .toList());
    }

    private void assertNoCookieRelay(String path) throws Exception {
        var request = request(path);
        request.setCookies(new Cookie("__Host-memory-access", "cookie-jwt"));
        var downstreamRequest = new AtomicReference<jakarta.servlet.http.HttpServletRequest>();

        assertThat(resolver.resolveCookie(request)).as("resolver must reject %s", path).isNull();

        relayFilter.doFilter(request, new MockHttpServletResponse(), (actualRequest, ignoredResponse) ->
                downstreamRequest.set((jakarta.servlet.http.HttpServletRequest) actualRequest));

        assertThat(downstreamRequest.get()).as("filter must continue for %s", path).isNotNull();
        assertThat(downstreamRequest.get().getHeader(HttpHeaders.AUTHORIZATION))
                .as("filter must not synthesize Authorization for %s", path)
                .isNull();
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }
}
