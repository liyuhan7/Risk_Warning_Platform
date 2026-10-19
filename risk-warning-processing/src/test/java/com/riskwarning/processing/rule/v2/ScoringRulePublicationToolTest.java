package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.dto.scoring.ScoringDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.v2.ScoringTestData.M;

class ScoringRulePublicationToolTest {
    @TempDir Path temporary;

    @Test void reviewedRulesProduceVersionedTestRegistryWithSourceCoverage() throws Exception {
        Path dataset = dataset();
        ObjectNode document = ScoringRuleDraftTool.draft(new ScoringDataset(dataset.toString()), "劳务合规风险", 1);
        document.put("registryVersion", "reviewed-engineering-fixture-v1");
        ObjectNode rule = (ObjectNode)document.path("rules").elements().next();
        rule.put("enabled", true).put("verifiedBy", "工程测试，非业务签认").put("verifiedAt", "2026-10-15T20:00:00+08:00").put("basis", "测试发布流程，不启用业务环境");
        Path input = temporary.resolve("reviewed.json"); Files.write(input, M.writeValueAsBytes(document));
        Path output = temporary.resolve("published-v1");
        Map<String, Object> result = ScoringRulePublicationTool.publish(input, dataset, output);
        assertEquals(1, result.get("enabledIndicators")); assertEquals(1, result.get("enabledRelations"));
        assertEquals("TEST", result.get("purpose")); assertEquals(false, result.get("runtimeEnabled"));
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output.resolve("rules.json")));
        assertTrue(Files.size(output.resolve("validation-report.json")) > 0);
    }

    @Test void draftCannotBePublishedAsVerifiedBusinessRules() throws Exception {
        Path input = temporary.resolve("draft.json");
        Files.write(input, M.writeValueAsBytes(ScoringRuleDraftTool.draft(new ScoringDataset(dataset().toString()), "劳务合规风险", 1)));
        Path output = temporary.resolve("invalid");
        assertThrows(IllegalArgumentException.class, () -> ScoringRulePublicationTool.publish(input, dataset(), output));
        assertFalse(Files.exists(output));
    }

    @Test void configurableSeverityMatrixDeterminesDraftWeight() throws Exception {
        ObjectNode defaults = M.valueToTree(ScoringRuleDraftDefaults.firstVersion());
        ((ObjectNode)defaults.path("defaultSeverity")).put("REQUIRED", "HIGH");
        ((ObjectNode)defaults.path("weights").path("REQUIRED")).put("HIGH", 2.7);
        Path file = temporary.resolve("defaults.json"); Files.write(file, M.writeValueAsBytes(defaults));
        ObjectNode draft = ScoringRuleDraftTool.draft(new ScoringDataset(dataset().toString()), "劳务合规风险", 1, ScoringRuleDraftDefaults.load(file));
        com.fasterxml.jackson.databind.JsonNode requirement = draft.path("rules").elements().next().path("requirements").get(0);
        assertEquals("HIGH", requirement.path("severity").asText()); assertEquals(2.7, requirement.path("weight").asDouble());
    }

    private static Path dataset() { return Files.exists(Paths.get("documents/data")) ? Paths.get("documents/data") : Paths.get("../documents/data"); }
}
