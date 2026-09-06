package com.yilan.memory.adapter.postgres;

import com.yilan.memory.application.event.SubmitMemoryEventsUseCase.OutboxEvent;
import com.yilan.memory.application.event.SubmitMemoryEventsUseCase.TransactionalOutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * Writes only the event hand-off record. Its caller owns the transaction that
 * also inserts the authoritative interaction event.
 */
@Repository
public class JdbcTransactionalOutboxRepository implements TransactionalOutboxRepository {

    private final JdbcClient jdbcClient;

    public JdbcTransactionalOutboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public void insert(OutboxEvent event) {
        jdbcClient.sql("""
                        INSERT INTO transactional_outbox (outbox_id, aggregate_id, event_type, payload, created_at)
                        VALUES (:outboxId, :aggregateId, :eventType, CAST(:payloadJson AS jsonb), :createdAt)
                        """)
                .param("outboxId", event.outboxId())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType())
                .param("payloadJson", event.payloadJson())
                .param("createdAt", Timestamp.from(event.createdAt()))
                .update();
    }
}
