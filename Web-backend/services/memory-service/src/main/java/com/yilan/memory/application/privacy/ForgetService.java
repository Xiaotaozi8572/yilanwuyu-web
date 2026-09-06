package com.yilan.memory.application.privacy;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import com.yilan.memory.adapter.cache.ContextCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Closes the authority gate before returning the frozen accepted receipt. */
@Service
public final class ForgetService {
    public interface L1Evictor { void evict(String subjectHash); }
    private final ForgetRepository repository;
    private final L1Evictor l1;
    private final Clock clock;
    private final DeletionProcessor deletionProcessor;
    public ForgetService(ForgetRepository repository, L1Evictor l1, Clock clock) {
        this(repository, l1, clock, new DeletionProcessor(repository, clock));
    }
    ForgetService(ForgetRepository repository, L1Evictor l1, Clock clock, DeletionProcessor deletionProcessor) {
        this.repository = Objects.requireNonNull(repository); this.l1 = Objects.requireNonNull(l1); this.clock = Objects.requireNonNull(clock);
        this.deletionProcessor = Objects.requireNonNull(deletionProcessor);
    }
    @Autowired
    public ForgetService(ForgetRepository repository, ObjectProvider<ContextCache> contextCache) {
        this(repository, subject -> contextCache.ifAvailable(cache -> cache.evictL1ForSubject(subject)), Clock.systemUTC());
    }
    public DeletionReceipt forgetAll(String subjectHash, String idempotencyKey) {
        var receipt = repository.requestFull(requireSubject(subjectHash), requireIdempotencyKey(idempotencyKey), clock.instant());
        l1.evict(subjectHash);
        deletionProcessor.tryComplete(receipt.requestId());
        return receipt;
    }
    public DeletionReceipt forgetOne(String subjectHash, UUID assertionId, String idempotencyKey) {
        var receipt = repository.requestSingle(requireSubject(subjectHash), Objects.requireNonNull(assertionId), requireIdempotencyKey(idempotencyKey), clock.instant());
        l1.evict(subjectHash);
        return receipt;
    }
    private static String requireSubject(String value) { if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("subjectHash"); return value; }
    private static String requireIdempotencyKey(String value) { if (value == null || value.length() < 16 || value.length() > 128) throw new IllegalArgumentException("idempotencyKey"); return value; }
}
