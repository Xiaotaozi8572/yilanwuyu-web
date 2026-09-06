package com.yilan.memory.security;

import java.util.Objects;
import java.util.Set;

/**
 * The only REST subject representation available after JWT verification.
 * It deliberately contains an HMAC pseudonym rather than a raw identity claim.
 */
public record AuthenticatedSubject(String subjectHash, Set<MemoryRole> roles, boolean freshAuthentication) {

    /** Preserves the non-HTTP and existing two-argument binding as fail-closed. */
    public AuthenticatedSubject(String subjectHash, Set<MemoryRole> roles) {
        this(subjectHash, roles, false);
    }

    public AuthenticatedSubject {
        if (subjectHash == null || !subjectHash.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("subjectHash");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles");
        }
    }

    public boolean hasRole(MemoryRole role) {
        return roles.contains(Objects.requireNonNull(role, "role"));
    }
}
