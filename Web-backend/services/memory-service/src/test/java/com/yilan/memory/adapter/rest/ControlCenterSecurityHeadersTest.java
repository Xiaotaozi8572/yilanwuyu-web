package com.yilan.memory.adapter.rest;

import com.yilan.memory.security.SecurityConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class ControlCenterSecurityHeadersTest {

    private AnnotationConfigWebApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("security-test", java.util.Map.of(
                "memory.security.subject-hmac-key", "test-only-subject-hmac-key-that-is-long-enough")));
        context.register(TestJwtConfiguration.class, TestWebConfiguration.class, SecurityConfiguration.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void controlCenterResponseHasStrictStaticHeadersAndDistinctCsrfCookie() throws Exception {
        var dispatcher = new DispatcherServlet(context);
        dispatcher.init(new MockServletConfig(context.getServletContext()));
        for (var path : java.util.List.of("/memory-control", "/memory-control/")) {
            var response = getThroughSecurity(dispatcher, path);

            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(response.getContentAsString()).as(path).contains("<!doctype html>");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'self'");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeader("Permissions-Policy")).contains("geolocation=()");
            var csrfCookie = response.getCookie("__Host-memory-csrf");
            assertThat(csrfCookie).isNotNull();
            assertThat(csrfCookie.getSecure()).isTrue();
            assertThat(csrfCookie.getValue()).doesNotContain("Bearer", "eyJ");
        }
    }

    @Test
    void controlCenterEntryUsesRootLocalAssetsThatAreServedThroughSecurityAndMvc() throws Exception {
        var dispatcher = new DispatcherServlet(context);
        dispatcher.init(new MockServletConfig(context.getServletContext()));

        var entry = getThroughSecurity(dispatcher, "/memory-control");

        assertThat(entry.getStatus()).isEqualTo(200);
        assertThat(entry.getContentAsString())
                .contains("href=\"/memory-control/app.css\"", "src=\"/memory-control/app.js\"");

        var stylesheet = getThroughSecurity(dispatcher, "/memory-control/app.css");
        assertThat(stylesheet.getStatus()).isEqualTo(200);
        assertThat(stylesheet.getContentAsString()).contains(":root");

        var application = getThroughSecurity(dispatcher, "/memory-control/app.js");
        assertThat(application.getStatus()).isEqualTo(200);
        assertThat(application.getContentAsString()).contains("CONSENT_CATEGORIES");

        var api = getThroughSecurity(dispatcher, "/memory-control/api.js");
        assertThat(api.getStatus()).isEqualTo(200);
        assertThat(api.getContentAsString()).contains("function csrfToken", "export const api");

        var state = getThroughSecurity(dispatcher, "/memory-control/state.js");
        assertThat(state.getStatus()).isEqualTo(200);
        assertThat(state.getContentAsString()).contains("export function snapshot", "export function leaveDetail");
    }

    private MockHttpServletResponse getThroughSecurity(DispatcherServlet dispatcher, String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        var response = new MockHttpServletResponse();

        context.getBean(FilterChainProxy.class).doFilter(request, response, (actualRequest, actualResponse) ->
                dispatcher.service(
                        (jakarta.servlet.http.HttpServletRequest) actualRequest,
                        (jakarta.servlet.http.HttpServletResponse) actualResponse));
        return response;
    }

    @Configuration(proxyBeanMethods = false)
    static class TestJwtConfiguration {
        @Bean
        JwtDecoder testJwtDecoder() {
            return token -> { throw new JwtException("not used by static control-center test"); };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ControlCenterPageController.class)
    @ImportAutoConfiguration(WebMvcAutoConfiguration.class)
    static class TestWebConfiguration { }
}
