package com.yilan.memory.adapter.rest;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ControlCenterSmokeTest {

    private static final Pattern INLINE_EVENT_HANDLER_ATTRIBUTE =
            Pattern.compile("(?i)(?:^|\\s)on[a-z][a-z0-9_-]*\\s*=");

    @Test
    void staticControlCenterIsLocalAndKeepsBrowserStateEphemeral() throws Exception {
        var resources = List.of("index.html", "app.css", "app.js", "api.js", "state.js");
        for (var resource : resources) {
            var file = new ClassPathResource("static/memory-control/" + resource);
            assertThat(file.exists()).as(resource).isTrue();
            var content = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).as(resource).doesNotContain("http://", "https://", "localStorage", "sessionStorage",
                    "serviceWorker", "analytics", "Authorization", "Bearer ");
            assertThat(INLINE_EVENT_HANDLER_ATTRIBUTE.matcher(content).find())
                    .as("%s must not contain any HTML inline event-handler attribute", resource)
                    .isFalse();
        }
    }

    @Test
    void controlCenterSourceKeepsConsentAndMemoryInteractionsWithinFrozenContracts() throws Exception {
        var app = source("app.js");
        var api = source("api.js");
        var page = source("index.html");

        assertThat(app).contains("PREFERENCE", "MASTERY", "MISCONCEPTION", "REFLECTION");
        assertThat(app).contains("allowedCategories.includes(box.value)");
        assertThat(page).contains("Static purpose:", "Retention is selected only when you save enabled consent.");
        assertThat(app).contains("long_term_enabled: false", "allowed_categories: []", "retention_days: null");
        assertThat(app).contains("positive whole number", "long_term_enabled: true");
        assertThat(app).contains("type-filter", "status-filter");
        assertThat(api).contains("new URLSearchParams", "params.set('type'", "params.set('status'");
        assertThat(app).contains("leaveDetail();", "receipt.state === 'BLOCKED'", "await load();");
        assertThat(page).contains("PREFERENCE", "MASTERY", "MISCONCEPTION", "REFLECTION");
        assertThat(app).containsPattern("if \\(error instanceof StaleVersionError\\) \\{\\s*status\\('This record changed\\. Reload before trying again\\.'\\);");
        assertThat(app).containsPattern("else if \\(error instanceof ReauthenticationRequiredError\\) \\{\\s*status\\('Reauthentication is required before this action can continue\\.'\\);");
    }

    private static String source(String name) throws Exception {
        var file = new ClassPathResource("static/memory-control/" + name);
        return new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
