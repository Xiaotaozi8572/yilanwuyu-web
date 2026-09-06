package com.yilan.memory.adapter.grpc;

import com.yilan.memory.domain.identity.LearnerIdentity;
import com.yilan.memory.security.MemoryRole;
import com.yilan.memory.security.SubjectAuthenticationConverter;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignedSessionInterceptorTest {

    private static final String SUBJECT_HMAC_KEY = "test-only-subject-hmac-key-that-is-long-enough";

    @Test
    void validSignedMetadataEstablishesTheSamePseudonymousSubjectAndRolesAsRest() {
        var converter = new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY);
        var restSubject = converter.fromVerifiedClaims("learner-a", List.of("LEARNER"));
        var interceptor = new SignedSessionInterceptor(token -> {
            if (!"Bearer valid".equals(token)) {
                throw new SignedSessionInterceptor.InvalidSessionException();
            }
            return new SignedSessionInterceptor.VerifiedSession(
                    "learner-a", "session-1", 4, List.of("LEARNER"));
        }, converter);
        var call = call();
        var headers = authorization("Bearer valid");
        var body = new UntrustedRequestBody("subject-B");
        var handler = new ServerCallHandler<Object, Object>() {
            @Override
            public ServerCall.Listener<Object> startCall(ServerCall<Object, Object> ignored, Metadata ignoredHeaders) {
                var identity = SignedSessionInterceptor.requireIdentity(Context.current());
                assertThat(identity.subjectHash()).isEqualTo(restSubject.subjectHash());
                assertThat(identity.subjectHash()).doesNotContain("learner-a");
                assertThat(identity.sessionId()).isEqualTo("session-1");
                assertThat(identity.consentRevision()).isEqualTo(4);
                assertThat(SignedSessionInterceptor.requireAuthenticatedSubject(Context.current()).roles())
                        .containsExactly(MemoryRole.LEARNER);
                assertThat(body.subjectHash()).isEqualTo("subject-B");
                return new ServerCall.Listener<>() {
                };
            }
        };

        interceptor.interceptCall(call, headers, handler);
    }

    @Test
    void absentOrInvalidAuthorizationIsUnauthenticated() {
        var interceptor = new SignedSessionInterceptor(token -> {
            throw new SignedSessionInterceptor.InvalidSessionException();
        }, new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY));

        assertUnauthenticated(interceptor, new Metadata());
        assertUnauthenticated(interceptor, authorization("Bearer invalid"));
    }

    @Test
    void unexpectedVerifierRuntimeFailureIsUnauthenticated() {
        var interceptor = new SignedSessionInterceptor(token -> {
            throw new IllegalStateException("unexpected verifier state");
        }, new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY));

        assertUnauthenticated(interceptor, authorization("Bearer signed-unavailable"));
    }

    @Test
    void requireIdentityRejectsUnauthenticatedContexts() {
        assertThatThrownBy(() -> SignedSessionInterceptor.requireIdentity(Context.ROOT))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .extracting(error -> ((io.grpc.StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThatThrownBy(() -> SignedSessionInterceptor.requireAuthenticatedSubject(Context.ROOT))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .extracting(error -> ((io.grpc.StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void missingInvalidOrUnknownRolesFailClosedBeforeTheHandler() {
        var converter = new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY);
        for (var roles : List.<List<String>>of(
                List.of(), List.of(""), List.of("SUPERUSER"), List.of("LEARNER", "SUPERUSER"))) {
            var interceptor = new SignedSessionInterceptor(token -> new SignedSessionInterceptor.VerifiedSession(
                    "learner-a", "session-1", 4, roles), converter);

            assertUnauthenticated(interceptor, authorization("Bearer invalid-role"));
        }
    }

    @Test
    void verifiedNonLearnerRolesAreForbiddenBeforeTheHandler() {
        var converter = new SubjectAuthenticationConverter(SUBJECT_HMAC_KEY);
        for (var role : List.of("ADMIN", "AUDITOR")) {
            var interceptor = new SignedSessionInterceptor(token -> new SignedSessionInterceptor.VerifiedSession(
                    "staff-a", "session-1", 4, List.of(role)), converter);
            var call = call();
            @SuppressWarnings("unchecked")
            var handler = mock(ServerCallHandler.class);

            interceptor.interceptCall(call, authorization("Bearer " + role.toLowerCase()), handler);

            var status = ArgumentCaptor.forClass(Status.class);
            verify(call).close(status.capture(), org.mockito.ArgumentMatchers.any(Metadata.class));
            assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
            org.mockito.Mockito.verifyNoInteractions(handler);
        }
    }

    private static void assertUnauthenticated(ServerInterceptor interceptor, Metadata headers) {
        var call = call();
        @SuppressWarnings("unchecked")
        var handler = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, headers, handler);

        var status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), org.mockito.ArgumentMatchers.any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<Object, Object> call() {
        return mock(ServerCall.class);
    }

    private static Metadata authorization(String value) {
        var headers = new Metadata();
        headers.put(SignedSessionInterceptor.AUTHORIZATION, value);
        return headers;
    }

    private record UntrustedRequestBody(String subjectHash) {
    }
}
