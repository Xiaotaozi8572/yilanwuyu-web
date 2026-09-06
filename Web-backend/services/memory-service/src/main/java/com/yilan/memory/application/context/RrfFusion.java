package com.yilan.memory.application.context;

import com.yilan.memory.config.MemoryProperties;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Deterministic weighted reciprocal-rank fusion without model invocation. */
public final class RrfFusion {

    private final MemoryProperties properties;

    public RrfFusion(MemoryProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public List<RetrievalChannel.RankedMemory> fuse(List<ChannelResult> channelResults) {
        Objects.requireNonNull(channelResults, "channelResults");
        Map<UUID, Accumulator> accumulated = new LinkedHashMap<>();
        for (var channelResult : channelResults) {
            var weight = weightFor(channelResult.channelName());
            var rank = 0;
            for (var item : channelResult.items()) {
                rank++;
                var contribution = weight.divide(
                        BigDecimal.valueOf((long) properties.rrfK() + rank), MathContext.DECIMAL128);
                accumulated.compute(item.memoryId(), (ignored, current) -> current == null
                        ? new Accumulator(item, contribution)
                        : current.add(contribution));
            }
        }
        return accumulated.values().stream()
                .map(Accumulator::asRankedMemory)
                .sorted(Comparator.comparing(RetrievalChannel.RankedMemory::score).reversed()
                        .thenComparing(item -> item.memoryId().toString()))
                .toList();
    }

    private BigDecimal weightFor(String name) {
        return switch (Objects.requireNonNull(name, "channelName")) {
            case "structured" -> properties.retrievalWeights().structured();
            case "keyword" -> properties.retrievalWeights().keyword();
            case "vector" -> properties.retrievalWeights().vector();
            case "recent_episode" -> properties.retrievalWeights().recentEpisode();
            default -> throw new IllegalArgumentException("unsupported retrieval channel");
        };
    }

    public record ChannelResult(String channelName, List<RetrievalChannel.RankedMemory> items) {
        public ChannelResult {
            if (channelName == null || channelName.isBlank()) {
                throw new IllegalArgumentException("channelName");
            }
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    private record Accumulator(RetrievalChannel.RankedMemory item, BigDecimal score) {
        private Accumulator add(BigDecimal contribution) {
            return new Accumulator(item, score.add(contribution));
        }

        private RetrievalChannel.RankedMemory asRankedMemory() {
            return item.withScore(score);
        }
    }
}
