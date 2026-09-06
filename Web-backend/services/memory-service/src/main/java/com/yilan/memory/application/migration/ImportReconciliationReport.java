package com.yilan.memory.application.migration;

import java.util.Map;

/** Bounded migration outcome counts; it intentionally contains no payload or learner content. */
public record ImportReconciliationReport(
        int sourceTotal,
        int accepted,
        int rejected,
        int duplicate,
        int newlyImported,
        Map<String, Integer> rejectionReasons) {

    public ImportReconciliationReport {
        if (sourceTotal < 0 || accepted < 0 || rejected < 0 || duplicate < 0 || newlyImported < 0
                || accepted + rejected != sourceTotal || duplicate + newlyImported != sourceTotal) {
            throw new IllegalArgumentException("migration reconciliation counts are invalid");
        }
        rejectionReasons = Map.copyOf(rejectionReasons);
    }
}
