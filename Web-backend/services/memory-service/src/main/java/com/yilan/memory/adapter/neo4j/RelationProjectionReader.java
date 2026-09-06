package com.yilan.memory.adapter.neo4j;

import com.yilan.memory.application.context.ResolveMemoryContextUseCase;
import com.yilan.memory.application.context.RetrievalChannel;
import org.neo4j.driver.Driver;
import org.neo4j.driver.TransactionConfig;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, identifier-only graph hint reader. PostgreSQL remains the context authority. */
public final class RelationProjectionReader implements ResolveMemoryContextUseCase.GraphHintReader {

    private static final String READ_HINTS = """
            MATCH (from:MemoryProjection {learnerPartitionHash: $learnerPartitionHash})
                  -[relation:MEMORY_RELATION]->
                  (to:MemoryProjection {learnerPartitionHash: $learnerPartitionHash})
            WHERE from.status = 'ACTIVE' AND to.status = 'ACTIVE'
              AND from.effectiveFrom <= $asOf
              AND (from.effectiveUntil IS NULL OR $asOf < from.effectiveUntil)
              AND to.effectiveFrom <= $asOf
              AND (to.effectiveUntil IS NULL OR $asOf < to.effectiveUntil)
              AND relation.effectiveFrom <= $asOf
              AND (relation.effectiveUntil IS NULL OR $asOf < relation.effectiveUntil)
            RETURN relation.relationEventId AS relationEventId,
                   from.assertionId AS fromAssertionId,
                   from.versionId AS fromVersionId,
                   to.assertionId AS toAssertionId,
                   to.versionId AS toVersionId,
                   relation.effectiveFrom AS effectiveFrom,
                   relation.effectiveUntil AS effectiveUntil
            ORDER BY relation.effectiveFrom DESC, relation.relationEventId
            LIMIT $limit
            """;

    private final Driver driver;
    private final GraphCircuitBreaker circuitBreaker;

    public RelationProjectionReader(Driver driver, GraphCircuitBreaker circuitBreaker) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
    }

    @Override
    public ResolveMemoryContextUseCase.GraphReadResult read(
            RetrievalChannel.AuthorizedMemoryQuery query,
            Duration remainingJavaBudget) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(remainingJavaBudget, "remainingJavaBudget");
        if (remainingJavaBudget.isZero() || remainingJavaBudget.isNegative() || !circuitBreaker.allowRequest()) {
            return ResolveMemoryContextUseCase.GraphReadResult.unavailable();
        }
        try (var session = driver.session()) {
            var config = TransactionConfig.builder().withTimeout(remainingJavaBudget).build();
            var records = session.run(READ_HINTS, Map.of(
                    "learnerPartitionHash", query.subjectHash(),
                    "asOf", query.asOf().atOffset(ZoneOffset.UTC),
                    "limit", query.candidateLimit()), config).list();
            var hints = new ArrayList<ResolveMemoryContextUseCase.GraphHint>(records.size());
            for (var record : records) {
                hints.add(new ResolveMemoryContextUseCase.GraphHint(
                        UUID.fromString(record.get("relationEventId").asString()),
                        UUID.fromString(record.get("fromAssertionId").asString()),
                        UUID.fromString(record.get("fromVersionId").asString()),
                        UUID.fromString(record.get("toAssertionId").asString()),
                        UUID.fromString(record.get("toVersionId").asString()),
                        record.get("effectiveFrom").asZonedDateTime().toInstant(),
                        record.get("effectiveUntil").isNull()
                                ? null : record.get("effectiveUntil").asZonedDateTime().toInstant()));
            }
            circuitBreaker.recordSuccess();
            return ResolveMemoryContextUseCase.GraphReadResult.available(List.copyOf(hints));
        } catch (RuntimeException failure) {
            circuitBreaker.recordFailure();
            return ResolveMemoryContextUseCase.GraphReadResult.unavailable();
        }
    }
}
