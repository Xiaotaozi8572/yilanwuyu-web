package com.yilan.memory.application.migration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** Java-owned durable cutover state; Python can only observe its signed capability. */
@Service
public final class AuthorityModeService {
    public static final String SCHEMA_VERSION = "v1";

    private final AuthorityModeRepository repository;
    private final String signingKey;

    public AuthorityModeService(
            AuthorityModeRepository repository,
            @Value("${memory.authority-cutover.hmac-key:}") String signingKey) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.signingKey = signingKey == null ? "" : signingKey;
    }

    public AuthorityState state() {
        return repository.read();
    }

    public AuthorityState enterShadow() {
        var state = state();
        require(state.mode() == AuthorityMode.LOCAL, "LOCAL -> SHADOW only");
        return repository.compareAndSet(state, new AuthorityState(AuthorityMode.SHADOW, state.epoch(), state.watermark()));
    }

    public AuthorityState prepare(long watermark) {
        var state = state();
        require(state.mode() == AuthorityMode.SHADOW && watermark >= 0, "SHADOW -> PREPARED only");
        return repository.compareAndSet(state, new AuthorityState(AuthorityMode.CUTOVER_PREPARED, state.epoch(), watermark));
    }

    public AuthorityState abortPrepared() {
        var state = state();
        require(state.mode() == AuthorityMode.CUTOVER_PREPARED, "PREPARED -> SHADOW only");
        return repository.compareAndSet(state, new AuthorityState(AuthorityMode.SHADOW, state.epoch(), state.watermark()));
    }

    public AuthorityState activateRemote(long acknowledgedWatermark) {
        var state = state();
        require(AuthorityCapability.signerAvailable(signingKey), "authority signer unavailable");
        require(state.mode() == AuthorityMode.CUTOVER_PREPARED
                && acknowledgedWatermark == state.watermark(), "PREPARED proof required");
        return repository.compareAndSet(state,
                new AuthorityState(AuthorityMode.REMOTE, Math.addExact(state.epoch(), 1), state.watermark()));
    }

    public Optional<String> capability() {
        var state = state();
        if (!AuthorityCapability.signerAvailable(signingKey)) return Optional.empty();
        return Optional.of(new AuthorityCapability(SCHEMA_VERSION, state.mode(), state.epoch(), state.watermark())
                .issue(signingKey));
    }

    /**
     * Fail-closed authority used by direct adapter construction when no durable
     * authority repository has been injected. It cannot issue a capability.
     */
    public static AuthorityModeService disabled() {
        return new AuthorityModeService(new InMemoryRepository(
                new AuthorityState(AuthorityMode.LOCAL, 0, 0)), "");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    public record AuthorityState(AuthorityMode mode, long epoch, long watermark) {
        public AuthorityState {
            if (mode == null || epoch < 0 || watermark < 0) throw new IllegalArgumentException("authority state");
        }
    }

    public interface AuthorityModeRepository {
        AuthorityState read();
        AuthorityState compareAndSet(AuthorityState expected, AuthorityState next);
    }

    private static final class InMemoryRepository implements AuthorityModeRepository {
        private AuthorityState state;
        private InMemoryRepository(AuthorityState state) { this.state = state; }
        @Override public synchronized AuthorityState read() { return state; }
        @Override public synchronized AuthorityState compareAndSet(AuthorityState expected, AuthorityState next) {
            if (!state.equals(expected)) throw new IllegalStateException("authority state changed");
            state = next;
            return state;
        }
    }
}
