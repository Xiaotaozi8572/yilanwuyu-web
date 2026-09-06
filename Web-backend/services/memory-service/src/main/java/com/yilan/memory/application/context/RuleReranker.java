package com.yilan.memory.application.context;

import com.yilan.memory.application.context.RetrievalChannel.RankedMemory;
import com.yilan.memory.application.context.RetrievalChannel.UseClass;
import com.yilan.memory.domain.event.InteractionEvent.PrivacyLevel;
import com.yilan.memory.domain.governance.MemoryCandidate.MemoryType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic post-fusion safety filter and reranker. It deliberately uses
 * no online model or cross-encoder; required classification is request policy,
 * not a property proposed by a channel.
 */
public final class RuleReranker {

    public List<RankedMemory> rerank(
            List<RankedMemory> fused,
            Set<MemoryType> requiredTypes,
            java.time.Instant asOf) {
        Objects.requireNonNull(fused, "fused");
        var required = Set.copyOf(Objects.requireNonNull(requiredTypes, "requiredTypes"));
        Objects.requireNonNull(asOf, "asOf");

        var ordered = fused.stream()
                .filter(item -> passesHardFilter(item, asOf))
                .map(item -> item.withUseClass(required.contains(item.memoryType())
                        ? UseClass.REQUIRED
                        : UseClass.OPTIONAL))
                .sorted(order())
                .toList();
        Map<String, RankedMemory> onePerConflictGroup = new LinkedHashMap<>();
        for (var item : ordered) {
            var group = item.conflictGroup() == null || item.conflictGroup().isBlank()
                    ? item.memoryId().toString()
                    : item.conflictGroup();
            onePerConflictGroup.putIfAbsent(group, item);
        }
        return List.copyOf(onePerConflictGroup.values());
    }

    private static boolean passesHardFilter(RankedMemory item, java.time.Instant asOf) {
        if (!item.sourceClosed() || item.privacyLevel() == PrivacyLevel.HIGH) {
            return false;
        }
        if (!"ACTIVE".equals(item.status()) || !"CONFIRMED".equals(item.confirmationStatus())) {
            return false;
        }
        if (item.memoryType() == MemoryType.AVIATION_FACT || item.memoryType() == MemoryType.OPTIMIZATION) {
            return false;
        }
        if (item.validFrom().isAfter(asOf) || (item.validUntil() != null && !item.validUntil().isAfter(asOf))) {
            return false;
        }
        return !item.recordedAt().isAfter(asOf)
                && (item.recordedUntil() == null || item.recordedUntil().isAfter(asOf));
    }

    private static Comparator<RankedMemory> order() {
        return Comparator.comparing((RankedMemory item) -> item.useClass() != UseClass.REQUIRED)
                .thenComparing(RankedMemory::score, Comparator.reverseOrder())
                .thenComparing(RankedMemory::confidence, Comparator.reverseOrder())
                .thenComparing(RankedMemory::stabilityScore, Comparator.reverseOrder())
                .thenComparing(RankedMemory::validFrom, Comparator.reverseOrder())
                .thenComparing(item -> item.memoryId().toString());
    }
}
