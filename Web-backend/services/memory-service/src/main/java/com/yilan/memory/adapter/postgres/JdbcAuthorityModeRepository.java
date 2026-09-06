package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.migration.AuthorityMode;
import com.yilan.memory.application.migration.AuthorityModeService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;

/** PostgreSQL singleton state adapter; it locks the only row for every transition. */
@Repository
public class JdbcAuthorityModeRepository implements AuthorityModeService.AuthorityModeRepository {
    private final JdbcClient jdbc;

    public JdbcAuthorityModeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuthorityModeService.AuthorityState read() {
        return jdbc.sql("""
                        SELECT authority_mode, authority_epoch, python_outbox_watermark
                        FROM memory_authority_cutover_state WHERE control_id = 1
                        """)
                .query(this::map).single();
    }

    @Override
    @Transactional
    public AuthorityModeService.AuthorityState compareAndSet(
            AuthorityModeService.AuthorityState expected,
            AuthorityModeService.AuthorityState next) {
        var current = jdbc.sql("""
                        SELECT authority_mode, authority_epoch, python_outbox_watermark
                        FROM memory_authority_cutover_state WHERE control_id = 1 FOR UPDATE
                        """)
                .query(this::map).single();
        if (!current.equals(expected)) throw new IllegalStateException("authority state changed");
        jdbc.sql("""
                        UPDATE memory_authority_cutover_state
                        SET authority_mode = :mode, authority_epoch = :epoch,
                            python_outbox_watermark = :watermark, changed_at = CURRENT_TIMESTAMP
                        WHERE control_id = 1
                        """)
                .param("mode", next.mode().name())
                .param("epoch", next.epoch())
                .param("watermark", next.watermark())
                .update();
        return next;
    }

    private AuthorityModeService.AuthorityState map(ResultSet row, int ignored) throws java.sql.SQLException {
        return new AuthorityModeService.AuthorityState(
                AuthorityMode.valueOf(row.getString("authority_mode")),
                row.getLong("authority_epoch"), row.getLong("python_outbox_watermark"));
    }
}
