package com.yilan.memory.application.async;

/**
 * Durable async stages. Every stage version has an independent checkpoint so
 * a future implementation can replay a new stage version without mutating
 * the historical result.
 */
public enum StageName {
    CANDIDATE_EXTRACTION(true),
    EMBEDDING(false),
    REFLECTION(true),
    PROJECTION(false);

    private final boolean learnerSerialized;

    StageName(boolean learnerSerialized) {
        this.learnerSerialized = learnerSerialized;
    }

    public boolean requiresLearnerAdvisoryLock() {
        return learnerSerialized;
    }
}
