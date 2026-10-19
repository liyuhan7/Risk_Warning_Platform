package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.dto.scoring.ScoringDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ScoringRuleDraftToolTest {
    @TempDir Path output;

    @Test void producesFortyRealLaborIndicatorDraftsWithSourcesWithoutClaimingHumanVerification() throws Exception {
        Path directory = Files.exists(Paths.get("documents/data")) ? Paths.get("documents/data") : Paths.get("../documents/data");
        ScoringDataset dataset = new ScoringDataset(directory.toString());
        ObjectNode draft = ScoringRuleDraftTool.draft(dataset, "劳务合规风险", 40);
        assertEquals(40, draft.path("rules").size());
        draft.path("rules").forEach(rule -> { assertFalse(rule.path("enabled").asBoolean()); assertFalse(rule.hasNonNull("verifiedBy")); assertTrue(rule.path("requirements").size() > 0); });
        ScoringRuleRegistry.fromJson(draft.toString(), true);
        String requested = System.getProperty("scoring.draft.output");
        Path target = requested == null ? output.resolve("draft") : Paths.get(requested).toAbsolutePath();
        ScoringRuleDraftTool.writeReviewPackage(dataset, draft, target, null);
        assertTrue(Files.size(target.resolve("review-items.json")) > 0);
        assertEquals(Boolean.TRUE, ScoringRegistryValidator.validate(target.resolve("rules-draft.json"), directory, true).get("passed"));
    }

    @Test void everyRelatedIndicatorHasAnExplicitPendingReviewEntry() throws Exception {
        Path directory = Files.exists(Paths.get("documents/data")) ? Paths.get("documents/data") : Paths.get("../documents/data");
        ScoringDataset dataset = new ScoringDataset(directory.toString());
        ObjectNode draft = ScoringRuleDraftTool.draft(dataset, "ALL", Integer.MAX_VALUE);
        assertEquals(651, draft.path("rules").size());
        draft.path("rules").forEach(rule -> rule.path("requirements").forEach(requirement -> {
            assertFalse(rule.path("enabled").asBoolean()); assertFalse(requirement.path("reviewReason").asText().trim().isEmpty());
        }));
        ScoringRuleRegistry.fromJson(draft.toString(), true);
        String requested = System.getProperty("scoring.inventory.output");
        Path target = requested == null ? output.resolve("inventory") : Paths.get(requested).toAbsolutePath();
        ScoringRuleDraftTool.writeReviewPackage(dataset, draft, target, null);
    }
}
