package com.yilan.memory.contract;

import com.yilan.memory.contract.v1.MemoryApplicationStatus;
import com.yilan.memory.contract.v1.ResolveMemoryContextResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenContractTest {

    @Test
    void parsesPythonGoldenResolveResponse() throws Exception {
        var bytes = Files.readAllBytes(Path.of(
                "..", "..", "tests", "fixtures", "memory_contract", "v1", "resolve_applied.bin"));
        var response = ResolveMemoryContextResponse.parseFrom(bytes);

        assertThat(response.getRequestId()).isEqualTo("resolve-golden-0001");
        assertThat(response.getSchemaVersion()).isEqualTo("1.0");
        assertThat(response.getStatus()).isEqualTo(MemoryApplicationStatus.APPLIED);
        assertThat(response.getIdentityBindingDigest()).isEqualTo("binding-golden-0001");
        assertThat(response.getDiagnosticCode()).isEmpty();
        assertThat(response.getContext().getMemoryBoundary()).isEqualTo("personalization-only");
        assertThat(response.getContext().getPolicyEpoch()).isEqualTo(7L);
        assertThat(response.getContext().getMemoryEpoch()).isEqualTo(11L);
        assertThat(response.getContext().getGeneratedAt()).isEqualTo("2026-07-19T08:00:00Z");
        assertThat(response.getContext().getItemsCount()).isEqualTo(1);

        var item = response.getContext().getItems(0);
        assertThat(item.getMemoryId()).isEqualTo("memory-golden-0001");
        assertThat(item.getVersionId()).isEqualTo("version-golden-0001");
        assertThat(item.getMemoryType()).isEqualTo("PREFERENCE");
        assertThat(item.getUseClass()).isEqualTo("OPTIONAL");
        assertThat(item.getValueJson()).isEqualTo("{\"answer_style\":\"concise\"}");
        assertThat(item.getConfidence()).isEqualTo(0.95d);
        assertThat(item.getValidFrom()).isEqualTo("2026-07-19T08:00:00Z");
        assertThat(item.getValidTo()).isEqualTo("2027-07-19T08:00:00Z");
        assertThat(item.getConfirmationStatus()).isEqualTo("CONFIRMED");
        assertThat(item.getSourceIdsList()).containsExactly("event-1");
    }
}
