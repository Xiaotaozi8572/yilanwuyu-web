package com.yilan.memory.application.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationTamperTest {

    private static final byte[] TEST_KEY = new byte[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
    };

    @Test
    void rejects_ciphertext_wrong_key_and_authenticated_header_tampering() throws Exception {
        var source = Files.createTempFile("migration", ".json");
        Files.write(source, Base64.getDecoder().decode(ShadowImportServiceTest.PythonBundleVector.BASE64));
        var reader = new MigrationBundleReader();

        assertThatThrownBy(() -> reader.read(source, new byte[32])).isInstanceOf(SecurityException.class);

        var json = new ObjectMapper().readTree(Files.readAllBytes(source));
        var ciphertext = Base64.getDecoder().decode(json.path("chunks").get(0).path("ciphertext").asText());
        ciphertext[ciphertext.length - 1] ^= 1;
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("chunks").get(0))
                .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.write(source, new ObjectMapper().writeValueAsBytes(json));
        assertThatThrownBy(() -> reader.read(source, TEST_KEY)).isInstanceOf(SecurityException.class);

        Files.write(source, Base64.getDecoder().decode(ShadowImportServiceTest.PythonBundleVector.BASE64));
        json = new ObjectMapper().readTree(Files.readAllBytes(source));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("header")).put("record_count", 2);
        Files.write(source, new ObjectMapper().writeValueAsBytes(json));
        assertThatThrownBy(() -> reader.read(source, TEST_KEY)).isInstanceOf(SecurityException.class);
    }

    @Test
    void rejects_non_sha256_schema_and_baseline_header_fingerprints_before_decryption() throws Exception {
        var header = JsonNodeFactory.instance.objectNode();
        header.set("accepted_counts", JsonNodeFactory.instance.objectNode().put("structured_memories", 0));
        header.put("chunk_count", 1);
        header.put("format", "legacy-memory-export/v1");
        header.put("record_count", 1);
        header.put("records_sha256", "0".repeat(64));
        header.set("rejection_counts", JsonNodeFactory.instance.objectNode().put("structured_memories", 1));
        header.put("schema_fingerprint", "not-a-sha256");
        header.put("source_baseline_digest", "1".repeat(64));
        header.set("source_counts", JsonNodeFactory.instance.objectNode().put("structured_memories", 1));
        var root = JsonNodeFactory.instance.objectNode();
        root.set("header", header);
        var chunks = root.putArray("chunks");
        chunks.addObject().put("ciphertext", "AQ==").put("nonce", "AAECAwQFBgcICQoL").put("sequence", 0);

        assertThatThrownBy(() -> new MigrationBundleReader().read(new ObjectMapper().writeValueAsBytes(root), TEST_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("migration bundle header values are invalid");
    }
}
