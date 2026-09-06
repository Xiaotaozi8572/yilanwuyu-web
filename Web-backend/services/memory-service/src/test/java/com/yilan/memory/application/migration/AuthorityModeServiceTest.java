package com.yilan.memory.application.migration;

import com.yilan.memory.adapter.postgres.JdbcAuthorityModeRepository;
import com.yilan.memory.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorityModeServiceTest extends PostgresIntegrationTest {
    private static final String TEST_KEY = "test-only-authority-hmac-key";

    @Autowired
    private JdbcClient jdbc;

    @Test
    void only_the_legal_one_way_transition_sequence_is_accepted_and_survives_a_new_service() {
        var repository = new DurableTestRepository(new AuthorityModeService.AuthorityState(AuthorityMode.LOCAL, 0, 0));
        var service = new AuthorityModeService(repository, TEST_KEY);

        assertThat(service.enterShadow().mode()).isEqualTo(AuthorityMode.SHADOW);
        assertThat(service.prepare(7).mode()).isEqualTo(AuthorityMode.CUTOVER_PREPARED);
        assertThat(service.abortPrepared().mode()).isEqualTo(AuthorityMode.SHADOW);
        service.prepare(9);
        assertThat(service.activateRemote(9)).isEqualTo(
                new AuthorityModeService.AuthorityState(AuthorityMode.REMOTE, 1, 9));

        var restarted = new AuthorityModeService(repository, TEST_KEY);
        assertThat(restarted.state()).isEqualTo(new AuthorityModeService.AuthorityState(AuthorityMode.REMOTE, 1, 9));
        assertThatThrownBy(restarted::enterShadow).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> restarted.prepare(9)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void remote_activation_requires_a_configured_signer_and_the_exact_prepared_watermark() {
        var repository = new DurableTestRepository(new AuthorityModeService.AuthorityState(AuthorityMode.SHADOW, 0, 0));
        var unsigned = new AuthorityModeService(repository, "");
        unsigned.prepare(3);

        assertThatThrownBy(() -> unsigned.activateRemote(3)).isInstanceOf(IllegalStateException.class);
        assertThat(unsigned.capability()).isEmpty();

        var signed = new AuthorityModeService(repository, TEST_KEY);
        assertThatThrownBy(() -> signed.activateRemote(2)).isInstanceOf(IllegalStateException.class);
        assertThat(signed.activateRemote(3).epoch()).isOne();
        var token = signed.capability().orElseThrow();
        assertThat(AuthorityCapability.verify(token, TEST_KEY)).contains(
                new AuthorityCapability("v1", AuthorityMode.REMOTE, 1, 3));
        assertThat(AuthorityCapability.verify(token + "0", TEST_KEY)).isEmpty();
    }

    @Test
    void v14_singleton_persists_across_fresh_jdbc_repository_and_service_instances() {
        jdbc.sql("""
                        UPDATE memory_authority_cutover_state
                        SET authority_mode = 'LOCAL', authority_epoch = 0,
                            python_outbox_watermark = 0
                        WHERE control_id = 1
                        """).update();
        var first = new AuthorityModeService(new JdbcAuthorityModeRepository(jdbc), TEST_KEY);
        first.enterShadow();
        first.prepare(12);
        first.activateRemote(12);

        var restarted = new AuthorityModeService(new JdbcAuthorityModeRepository(jdbc), TEST_KEY);

        assertThat(restarted.state()).isEqualTo(
                new AuthorityModeService.AuthorityState(AuthorityMode.REMOTE, 1, 12));
        assertThat(restarted.capability()).isPresent();
    }

    @Test
    void signer_key_bounds_are_measured_in_utf8_bytes() {
        var exactAscii = "a".repeat(512);
        var exactUnicode = "你".repeat(170) + "ab";
        var unicodeOverByteLimit = "你".repeat(171);

        assertThat(AuthorityCapability.signerAvailable(exactAscii)).isTrue();
        assertThat(AuthorityCapability.signerAvailable(exactUnicode)).isTrue();
        assertThat(AuthorityCapability.signerAvailable(unicodeOverByteLimit)).isFalse();
    }

    private static final class DurableTestRepository implements AuthorityModeService.AuthorityModeRepository {
        private AuthorityModeService.AuthorityState state;
        private DurableTestRepository(AuthorityModeService.AuthorityState state) { this.state = state; }
        @Override public AuthorityModeService.AuthorityState read() { return state; }
        @Override public AuthorityModeService.AuthorityState compareAndSet(
                AuthorityModeService.AuthorityState expected, AuthorityModeService.AuthorityState next) {
            if (!state.equals(expected)) throw new IllegalStateException("changed");
            state = next;
            return state;
        }
    }
}
