package com.yilan.memory.application.consent;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Input accepted by the self-service API; it contains no subject or role. */
public record ConsentCommand(boolean longTermEnabled, Set<String> allowedCategories, Integer retentionDays) {

    public ConsentCommand {
        allowedCategories = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(allowedCategories, "allowedCategories")));
        if (retentionDays != null && retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays");
        }
    }
}
