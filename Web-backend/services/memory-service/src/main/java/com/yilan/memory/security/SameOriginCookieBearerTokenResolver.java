package com.yilan.memory.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.URISyntaxException;

/** Extracts the sole gateway-issued access cookie for the relay filter. */
public final class SameOriginCookieBearerTokenResolver {

    private static final String ACCESS_COOKIE = "__Host-memory-access";

    public String resolveCookie(HttpServletRequest request) {
        if (!isSelfServicePath(request.getRequestURI())) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        String credential = null;
        for (Cookie cookie : cookies) {
            if (!ACCESS_COOKIE.equals(cookie.getName())) {
                continue;
            }
            if (credential != null || cookie.getValue() == null || cookie.getValue().isBlank()) {
                return null;
            }
            credential = cookie.getValue();
        }
        return credential;
    }

    private static boolean isSelfServicePath(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return false;
        }
        // Percent-encoded text could be decoded differently by a downstream component.
        if (requestUri.indexOf('%') >= 0) {
            return false;
        }
        // Matrix parameters and backslashes have container-specific path semantics.
        if (requestUri.indexOf(';') >= 0 || requestUri.indexOf('\\') >= 0) {
            return false;
        }
        // Do not accept alternate separator forms for an exact canonical route.
        if (requestUri.contains("//")) {
            return false;
        }

        try {
            var uri = new URI(requestUri);
            var rawPath = uri.getRawPath();
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || rawPath == null
                    || !requestUri.equals(rawPath)) {
                return false;
            }
            // Dot segments must not become a different route after URI normalization.
            if (!requestUri.equals(uri.normalize().getRawPath())) {
                return false;
            }
        } catch (URISyntaxException exception) {
            return false;
        }

        return "/v1/me".equals(requestUri) || requestUri.startsWith("/v1/me/");
    }
}
