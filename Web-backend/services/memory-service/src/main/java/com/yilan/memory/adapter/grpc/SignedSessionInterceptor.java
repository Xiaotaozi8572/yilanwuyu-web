package com.yilan.memory.adapter.grpc;

import com.yilan.memory.domain.identity.LearnerIdentity;
import com.yilan.memory.security.AuthenticatedSubject;
import com.yilan.memory.security.MemoryRole;
import com.yilan.memory.security.SubjectAuthenticationConverter;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.Objects;
import java.util.List;

/**
 * Establishes the only trusted learner identity from an already verified signed
 * authorization credential. Request messages are intentionally not inspected.
 */
public final class SignedSessionInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of(
            "authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<LearnerIdentity> LEARNER_IDENTITY = Context.key("memory-learner-identity");
    private static final Context.Key<AuthenticatedSubject> AUTHENTICATED_SUBJECT =
            Context.key("memory-authenticated-subject");

    private final SessionVerifier verifier;
    private final SubjectAuthenticationConverter subjectConverter;

    public SignedSessionInterceptor(SessionVerifier verifier, SubjectAuthenticationConverter subjectConverter) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.subjectConverter = Objects.requireNonNull(subjectConverter, "subjectConverter");
    }

    public static LearnerIdentity requireIdentity(Context context) {
        Objects.requireNonNull(context, "context");
        var identity = LEARNER_IDENTITY.get(context);
        if (identity == null) {
            throw Status.UNAUTHENTICATED.withDescription("unauthenticated session").asRuntimeException();
        }
        return identity;
    }

    public static AuthenticatedSubject requireAuthenticatedSubject(Context context) {
        Objects.requireNonNull(context, "context");
        var subject = AUTHENTICATED_SUBJECT.get(context);
        if (subject == null) {
            throw Status.UNAUTHENTICATED.withDescription("unauthenticated session").asRuntimeException();
        }
        return subject;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        var authorization = headers.get(AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return closeUnauthenticated(call);
        }

        final LearnerIdentity identity;
        final AuthenticatedSubject subject;
        try {
            var verifiedSession = Objects.requireNonNull(verifier.verify(authorization), "verified session");
            subject = subjectConverter.fromVerifiedClaims(
                    verifiedSession.immutableSubject(), verifiedSession.roles());
            if (!subject.hasRole(MemoryRole.LEARNER)) {
                return closeForbidden(call);
            }
            identity = new LearnerIdentity(
                    subject.subjectHash(), verifiedSession.sessionId(), verifiedSession.consentRevision());
        } catch (RuntimeException ignored) {
            return closeUnauthenticated(call);
        }

        var authenticatedContext = Context.current()
                .withValue(LEARNER_IDENTITY, identity)
                .withValue(AUTHENTICATED_SUBJECT, subject);
        return Contexts.interceptCall(authenticatedContext, call, headers, next);
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> closeUnauthenticated(ServerCall<ReqT, RespT> call) {
        call.close(Status.UNAUTHENTICATED.withDescription("unauthenticated session"), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> closeForbidden(ServerCall<ReqT, RespT> call) {
        call.close(Status.PERMISSION_DENIED.withDescription("learner role required"), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }

    @FunctionalInterface
    public interface SessionVerifier {
        VerifiedSession verify(String authorization) throws InvalidSessionException;
    }

    /** Verified transport claims retained only until the opaque context binding is established. */
    public record VerifiedSession(String immutableSubject, String sessionId, long consentRevision, List<String> roles) {

        public VerifiedSession {
            roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
        }
    }

    public static final class InvalidSessionException extends RuntimeException {

        public InvalidSessionException() {
            super();
        }
    }
}
