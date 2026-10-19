package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.processing.rule.RuleContractException;
import org.junit.jupiter.api.Test;
import static com.riskwarning.processing.rule.v2.ScoringTestData.*;
import static org.junit.jupiter.api.Assertions.*;

class ScoringRuleRegistryTest {
    @Test void acceptsFullSourceAndUnboundedSegmentsAndExplicitNoRiskWithoutChangingV1() {
        ScoringSourceBundle source = bundle("r");
        ScoringRuleRegistry registry = ScoringRuleRegistry.fromJson(registry(source, numeric("r")).toString(), true);
        assertEquals(3, registry.require("i", source).getRequirements().get(0).getGrading().getSegments().size());
        assertThrows(RuleContractException.class, () -> ScoringRuleRegistry.fromJson(registry(source, numeric("r")).toString(), false));
    }

    @Test void sourceDriftMissingRelationsAndMutableCopiesCannotBypassChecks() {
        ScoringSourceBundle source = bundle("r");
        ScoringRuleRegistry registry = ScoringRuleRegistry.fromJson(registry(source, requirement("r", "FORBIDDEN")).toString(), true);
        registry.require("i", source).getRequirements().clear();
        assertEquals(1, registry.require("i", source).getRequirements().size());
        ((ObjectNode)source.getRelations().get(0).getRegulation()).put("fullText", "已变化");
        assertEquals("SOURCE_BUNDLE_HASH_MISMATCH", assertThrows(RuleContractException.class, () -> registry.require("i", source)).getCode());
        assertThrows(RuleContractException.class, () -> registry.require("i", null));
    }

    @Test void sourceRelationMustExistEvenIfAttackerRecomputesBundleHash() {
        ScoringSourceBundle source = bundle("r");
        ObjectNode root = registry(source, requirement("r", "REQUIRED"));
        source.getRelations().clear();
        ((ObjectNode)root.path("rules").path("i")).put("sourceBundleHash", source.bundleHash());
        ScoringRuleRegistry registry = ScoringRuleRegistry.fromJson(root.toString(), true);
        assertThrows(RuleContractException.class, () -> registry.require("i", source));
    }

    @Test void rejectsOverlapHoleWrongWeightUnknownFieldsCoercionAndDuplicateKeys() {
        for (String variant : new String[]{"overlap", "hole", "weight", "unknown", "coercion", "max", "optionalFloor"}) {
            ObjectNode req = numeric("r");
            if ("overlap".equals(variant)) ((ObjectNode)req.path("grading").path("segments").get(0)).put("upperInclusive", true);
            if ("hole".equals(variant)) ((ObjectNode)req.path("grading").path("segments").get(1)).put("lower", 4.5);
            if ("weight".equals(variant)) req.put("weight", 0);
            if ("unknown".equals(variant)) req.put("script", "eval()");
            if ("coercion".equals(variant)) req.put("weight", "1");
            if ("optionalFloor".equals(variant)) req.put("minimumRiskLevel", "HIGH_RISK");
            ObjectNode root = registry(bundle("r"), req);
            if ("max".equals(variant)) ((ObjectNode)root.path("rules").path("i")).put("maxScore", 2);
            assertThrows(RuleContractException.class, () -> ScoringRuleRegistry.fromJson(root.toString(), true), variant);
        }
        assertThrows(RuleContractException.class, () -> ScoringRuleRegistry.fromJson("{\"schemaVersion\":\"2.0\",\"schemaVersion\":\"2.0\"}", true));
        assertThrows(RuleContractException.class, () -> ScoringRuleRegistry.fromJson(registry(bundle("r"), numeric("r")) + " {}", true));
    }

    @Test void compositeNodesRequireDistinctLeafIdentitiesAndClosedStructure() {
        ObjectNode req = requirement("r", "REQUIRED");
        ObjectNode leaf = ((ObjectNode)req.get("condition")).deepCopy();
        ObjectNode tree = M.createObjectNode(); tree.putArray("allOf").add(leaf).add(leaf.deepCopy());
        req.set("condition", tree);
        assertThrows(RuleContractException.class, () -> ScoringRuleRegistry.fromJson(registry(bundle("r"), req).toString(), true));
        ((ObjectNode)tree.path("allOf").get(1)).put("conditionId", "second");
        assertTrue(ScoringRuleRegistry.fromJson(registry(bundle("r"), req).toString(), true).isEnabled("i"));
        tree.put("operator", "IS_TRUE");
        assertThrows(RuleContractException.class, () -> ScoringRuleRegistry.fromJson(registry(bundle("r"), req).toString(), true));
    }
}
