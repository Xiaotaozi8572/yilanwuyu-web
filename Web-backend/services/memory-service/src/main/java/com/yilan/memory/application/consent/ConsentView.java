package com.yilan.memory.application.consent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Stable OpenAPI projection of the current self-service consent version. */
public record ConsentView(
        String version,
        @JsonProperty("long_term_enabled") boolean longTermEnabled,
        @JsonProperty("allowed_categories") Set<String> allowedCategories,
        @JsonProperty("effective_at") Instant effectiveAt) {

    public ConsentView {
        if (version == null || !version.matches("[0-9]+")) {
            throw new IllegalArgumentException("version");
        }
        allowedCategories = Set.copyOf(Objects.requireNonNull(allowedCategories, "allowedCategories"));
        Objects.requireNonNull(effectiveAt, "effectiveAt");
    }
}
