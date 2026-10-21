package com.riskwarning.processing.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactExtractionResponseValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void matchesAllFrozenP104Samples() throws Exception {
        FactExtractionResponseValidator validator = new FactExtractionResponseValidator(objectMapper);
        JsonNode samples = objectMapper.readTree(fixture("fact-extraction-samples.json").toFile());
        assertTrue(samples.isArray() && samples.size() > 0, "冻结样例必须为非空数组");

        for (JsonNode sample : samples) {
            Set<String> allowed = new HashSet<>();
            sample.path("allowedEvidenceIds").forEach(item -> allowed.add(item.asText()));
            FactExtractionValidationResult result = validator.validate(
                    sample.path("rawResponse").asText(), allowed);

            assertEquals(sample.path("expectPass").asBoolean(), result.isValid(),
                    sample.path("id").asText() + ": " + result.errorCodes());
            for (JsonNode expectedCode : sample.path("expectCodes")) {
                assertTrue(result.errorCodes().contains(expectedCode.asText()),
                        sample.path("id").asText() + " 缺少错误码 " + expectedCode.asText());
            }
        }
    }

    private Path fixture(String... parts) {
        Path root = Paths.get("..", "test", "fixtures", "fact-extraction");
        if (!Files.exists(root)) {
            root = Paths.get("test", "fixtures", "fact-extraction");
        }
        for (String part : parts) {
            root = root.resolve(part);
        }
        return root;
    }
}
