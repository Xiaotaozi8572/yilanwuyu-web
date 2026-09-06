package com.yilan.memory.security;

import com.yilan.memory.adapter.rest.ControlCenterHeadersFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import java.time.Clock;
import java.time.Duration;

/** Fail-closed REST security policy for memory self-service and oversight routes. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SubjectAuthenticationConverter subjectAuthenticationConverter(
            @Value("${memory.security.subject-hmac-key:}") String subjectHmacKey,
            @Value("${memory.security.maximum-fresh-auth-age:}") String maximumFreshAuthenticationAge) {
        return new SubjectAuthenticationConverter(
                subjectHmacKey, parseFreshAuthenticationAge(maximumFreshAuthenticationAge), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    SubjectBindingFilter subjectBindingFilter(
            @Value("${memory.security.maximum-request-bytes:16384}") int maximumRequestBytes,
            @Value("${memory.security.maximum-trace-bytes:512}") int maximumTraceBytes) {
        return new SubjectBindingFilter(maximumRequestBytes, maximumTraceBytes);
    }

    @Bean
    SecurityFilterChain memorySecurityFilterChain(
            HttpSecurity http,
            SubjectAuthenticationConverter converter,
            SubjectBindingFilter subjectBindingFilter) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(memoryControlCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(SecurityConfiguration::requiresCookieCsrf))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/memory-control", "/memory-control/**").permitAll()
                        .requestMatchers("/v1/me", "/v1/me/**").hasRole(MemoryRole.LEARNER.name())
                        .requestMatchers("/v1/admin/audit", "/v1/admin/audit/**")
                        .hasAnyRole(MemoryRole.ADMIN.name(), MemoryRole.AUDITOR.name())
                        .requestMatchers("/v1/admin", "/v1/admin/**").hasRole(MemoryRole.ADMIN.name())
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .addFilterAfter(new ControlCenterHeadersFilter(), CsrfFilter.class)
                .addFilterAfter(new SameOriginCookieBearerTokenRelayFilter(new SameOriginCookieBearerTokenResolver()), CsrfFilter.class)
                .addFilterAfter(subjectBindingFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    private static CookieCsrfTokenRepository memoryControlCsrfTokenRepository() {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("__Host-memory-csrf");
        repository.setHeaderName("X-Memory-CSRF");
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict").path("/"));
        return repository;
    }

    private static boolean requiresCookieCsrf(jakarta.servlet.http.HttpServletRequest request) {
        var method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "TRACE".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        var authorization = request.getHeader("Authorization");
        return authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length());
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder memoryJwtDecoder(
            @Value("${memory.security.jwt.issuer:}") String issuer,
            @Value("${memory.security.jwt.audience:}") String audience,
            @Value("${memory.security.jwt.jwk-set-uri:}") String jwkSetUri) {
        requireExternalSetting(issuer, "issuer");
        requireExternalSetting(audience, "audience");
        requireExternalSetting(jwkSetUri, "jwk-set-uri");
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(audience)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private static void requireExternalSetting(String value, String setting) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("memory.security.jwt." + setting + " must be externally configured");
        }
    }

    private static Duration parseFreshAuthenticationAge(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            var duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative()) {
                return null;
            }
            return duration;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
