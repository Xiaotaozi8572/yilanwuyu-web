package com.yilan.memory.application.context;

import com.yilan.memory.application.context.RetrievalChannel.RankedMemory;
import com.yilan.memory.application.context.RetrievalChannel.UseClass;
import com.yilan.memory.config.MemoryProperties.Budgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies typed item/token limits after hard filtering and deterministic ordering. */
public final class ContextBudgeter {

    public List<RankedMemory> apply(List<RankedMemory> ranked, Budgets budgets) {
        Objects.requireNonNull(ranked, "ranked");
        Objects.requireNonNull(budgets, "budgets");
        var selected = new ArrayList<RankedMemory>();
        var estimatedTokens = 0;
        for (var useClass : List.of(UseClass.REQUIRED, UseClass.OPTIONAL)) {
            for (var item : ranked) {
                if (item.useClass() != useClass || selected.size() >= budgets.maxItems()) {
                    continue;
                }
                if (item.estimatedTokens() > budgets.maxEstimatedTokens() - estimatedTokens) {
                    continue;
                }
                selected.add(item);
                estimatedTokens += item.estimatedTokens();
            }
        }
        return List.copyOf(selected);
    }
}
