package com.yilan.memory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;

/** Relays a same-origin access cookie only after CSRF processing has completed. */
public final class SameOriginCookieBearerTokenRelayFilter extends OncePerRequestFilter {

    private final SameOriginCookieBearerTokenResolver cookieResolver;

    public SameOriginCookieBearerTokenRelayFilter(SameOriginCookieBearerTokenResolver cookieResolver) {
        this.cookieResolver = cookieResolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (hasAuthorizationHeader(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        var credential = cookieResolver.resolveCookie(request);
        if (credential == null) {
            filterChain.doFilter(request, response);
            return;
        }

        filterChain.doFilter(new BearerRequestWrapper(request, credential), response);
    }

    private static boolean hasAuthorizationHeader(HttpServletRequest request) {
        return request.getHeaders(HttpHeaders.AUTHORIZATION).hasMoreElements();
    }

    private static final class BearerRequestWrapper extends HttpServletRequestWrapper {

        private final String authorization;

        private BearerRequestWrapper(HttpServletRequest request, String credential) {
            super(request);
            this.authorization = "Bearer " + credential;
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? authorization : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                    ? Collections.enumeration(Collections.singleton(authorization))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            var names = new LinkedHashSet<String>();
            var originalNames = super.getHeaderNames();
            while (originalNames.hasMoreElements()) {
                names.add(originalNames.nextElement());
            }
            names.add(HttpHeaders.AUTHORIZATION);
            return Collections.enumeration(names);
        }
    }
}
