package com.yilan.memory.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Converts a verified JWT into the opaque subject binding used by this service. */
public final class SubjectAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final int MINIMUM_HMAC_KEY_BYTES = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] hmacKey;
    private final Duration maximumFreshAuthenticationAge;
    private final Clock clock;

    public SubjectAuthenticationConverter(String subjectHmacKey) {
        this(subjectHmacKey, null, Clock.systemUTC());
    }

    public SubjectAuthenticationConverter(
            String subjectHmacKey, Duration maximumFreshAuthenticationAge, Clock clock) {
        if (subjectHmacKey == null || subjectHmacKey.isBlank()) {
            throw new IllegalArgumentException("memory.security.subject-hmac-key must be externally configured");
        }
        hmacKey = subjectHmacKey.getBytes(StandardCharsets.UTF_8);
        if (hmacKey.length < MINIMUM_HMAC_KEY_BYTES) {
            throw new IllegalArgumentException("memory.security.subject-hmac-key is too short");
        }
        this.maximumFreshAuthenticationAge = maximumFreshAuthenticationAge == null
                || maximumFreshAuthenticationAge.isNegative()
                || maximumFreshAuthenticationAge.isZero()
                ? null : maximumFreshAuthenticationAge;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt");
        var bound = fromVerifiedClaims(jwt.getSubject(), jwt.getClaim("roles"));
        var subject = new AuthenticatedSubject(
                bound.subjectHash(), bound.roles(), hasFreshAuthentication(jwt.getClaim("auth_time")));
        var authorities = subject.roles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return new SubjectAuthenticationToken(subject, authorities);
    }

    /**
     * Applies the same opaque subject binding to claims already verified by a
     * non-HTTP transport. Raw claims never leave the transport boundary.
     */
    public AuthenticatedSubject fromVerifiedClaims(String immutableSubject, Object rawRoles) {
        if (immutableSubject == null || immutableSubject.isBlank() || immutableSubject.length() > 256) {
            throw new BadCredentialsException("invalid subject claim");
        }
        return new AuthenticatedSubject(pseudonymize(immutableSubject), rolesFrom(rawRoles));
    }

    private boolean hasFreshAuthentication(Object rawAuthTime) {
        if (maximumFreshAuthenticationAge == null) {
            return false;
        }
        if (!(rawAuthTime instanceof Long || rawAuthTime instanceof Integer)) {
            return false;
        }
        final Instant authenticatedAt;
        try {
            authenticatedAt = Instant.ofEpochSecond(((Number) rawAuthTime).longValue());
        } catch (RuntimeException ignored) {
            return false;
        }
        var now = Instant.now(clock);
        if (authenticatedAt.isAfter(now)) {
            return false;
        }
        return !authenticatedAt.isBefore(now.minus(maximumFreshAuthenticationAge));
    }

    private EnumSet<MemoryRole> rolesFrom(Object rawRoles) {
        var values = new ArrayList<String>();
        if (rawRoles instanceof Collection<?> collection) {
            for (var value : collection) {
                if (!(value instanceof String role) || role.isBlank()) {
                    throw new BadCredentialsException("invalid role claim");
                }
                values.add(role);
            }
        } else if (rawRoles instanceof String roles) {
            for (var role : roles.split("\\s+")) {
                if (!role.isBlank()) {
                    values.add(role);
                }
            }
        } else {
            throw new BadCredentialsException("missing role claim");
        }
        if (values.isEmpty()) {
            throw new BadCredentialsException("missing role claim");
        }

        var parsed = EnumSet.noneOf(MemoryRole.class);
        for (var role : values) {
            try {
                parsed.add(MemoryRole.valueOf(role));
            } catch (IllegalArgumentException ignored) {
                throw new BadCredentialsException("invalid role claim");
            }
        }
        return parsed;
    }

    private String pseudonymize(String immutableSubject) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(immutableSubject.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("subject pseudonymization unavailable", exception);
        }
    }

    private static final class SubjectAuthenticationToken extends AbstractAuthenticationToken {

        private final AuthenticatedSubject principal;

        private SubjectAuthenticationToken(AuthenticatedSubject principal, List<GrantedAuthority> authorities) {
            super(authorities);
            this.principal = Objects.requireNonNull(principal, "principal");
            setAuthenticated(true);
        }

        @Override
        public AuthenticatedSubject getPrincipal() {
            return principal;
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public String getName() {
            return principal.subjectHash();
        }
    }
}
