package com.yilan.memory.application.privacy;

import java.util.Objects;
import java.util.UUID;

/** Verifies authority closure without reading or emitting payload bytes. */
public final class DeletionVerifier {
    private final ForgetRepository repository;
    public DeletionVerifier(ForgetRepository repository) { this.repository = Objects.requireNonNull(repository, "repository"); }
    public Verification verify(UUID requestId) { return repository.verify(requestId); }
    public enum Scope { FULL, ASSERTION }

    public record Verification(
            UUID requestId,
            Scope scope,
            long payloadReferencesRemaining,
            long retainedNonProtectedPayloadReferences,
            boolean cryptoShredded,
            boolean closed) { }
}
