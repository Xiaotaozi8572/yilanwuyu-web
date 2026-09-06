package com.yilan.memory.adapter.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Adds browser-control-center response headers and realizes its deferred CSRF token. */
public final class ControlCenterHeadersFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return !"/memory-control".equals(path) && !path.startsWith("/memory-control/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
                        + "form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=(), usb=()");

        var csrfToken = request.getAttribute(CsrfToken.class.getName());
        if (csrfToken instanceof CsrfToken token) {
            token.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
