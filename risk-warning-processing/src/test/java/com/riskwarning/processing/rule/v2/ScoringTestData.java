package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.dto.scoring.*;
import java.util.*;

final class ScoringTestData {
    static final ObjectMapper M = new ObjectMapper();

    static ScoringSourceBundle bundle(String... requirements) {
        ScoringSourceBundle out = new ScoringSourceBundle();
        out.setDatasetVersion(ScoringJson.hash("dataset"));
        out.setIndicator(M.createObjectNode().put("id", "i").put("name", "测试指标").put("dimension", "劳务合规风险"));
        List<ScoringSourceBundle.Source> sources = new ArrayList<>();
        for (String id : requirements) {
            ScoringSourceBundle.Source source = new ScoringSourceBundle.Source();
            source.setRegulationId("law-" + id);
            source.setRegulation(M.createObjectNode().put("id", "law-" + id).put("fullText", "测试要求" + id));
            source.setRelation(M.createObjectNode().put("indicatorId", "i").put("regulationId", "law-" + id)
                    .put("supportingText", "测试要求" + id).put("applicability", "测试场景"));
            source.setReference(NullNode.instance);
            source.setFullTextHash(ScoringJson.sha256(("测试要求" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            source.setRelationHash(ScoringJson.hash(source.getRelation()));
            source.setReferenceSourcesHash(ScoringJson.hash(source.getReference()));
            sources.add(source);
        }
        out.setRelations(sources);
        return out;
    }

    static ObjectNode requirement(String id, String direction) {
        ObjectNode r = M.createObjectNode().put("requirementId", id).put("sourceRegulationId", "law-" + id)
                .put("sourceRelationHash", bundle(id).getRelations().get(0).getRelationHash())
                .put("direction", direction).put("valueType", "BOOLEAN").put("crossBehaviorAggregation", "SINGLE")
                .put("severity", "MEDIUM").put("weight", 1).put("expressible", true);
        r.set("condition", M.createObjectNode().put("conditionId", "c-" + id).put("operator", "FORBIDDEN".equals(direction) ? "IS_FALSE" : "IS_TRUE").put("promptHint", "测试事实"));
        r.set("applicabilityGate", M.createObjectNode().put("mode", "MODEL_JUDGED").put("text", "测试适用条件"));
        r.set("grading", M.createObjectNode().put("mode", "BINARY").put("satisfied", 1).put("violated", 0));
        return r;
    }

    static ObjectNode registry(ScoringSourceBundle bundle, ObjectNode... requirements) {
        ObjectNode rule = M.createObjectNode().put("enabled", true).put("ruleVersion", "test-rule-1")
                .put("sourceBundleHash", bundle.bundleHash()).put("maxScore", 100).put("thresholdVersion", "test-threshold-1")
                .put("verifiedBy", "工程测试").put("verifiedAt", "2026-10-15T12:00:00+08:00").put("basis", "独立测试依据，不是业务审核");
        ArrayNode items = rule.putArray("requirements");
        for (ObjectNode requirement : requirements) items.add(requirement);
        ArrayNode intervals = rule.putArray("riskIntervals");
        intervals.add(M.createObjectNode().put("lower", 0).put("upper", 0.3).put("lowerInclusive", true).put("upperInclusive", true).put("riskLevel", "HIGH_RISK"));
        intervals.add(M.createObjectNode().put("lower", 0.3).put("upper", 0.5).put("lowerInclusive", false).put("upperInclusive", true).put("riskLevel", "MEDIUM_RISK"));
        intervals.add(M.createObjectNode().put("lower", 0.5).put("upper", 0.7).put("lowerInclusive", false).put("upperInclusive", true).put("riskLevel", "LOW_RISK"));
        intervals.add(M.createObjectNode().put("lower", 0.7).put("upper", 1).put("lowerInclusive", false).put("upperInclusive", true).put("riskLevel", "NO_RISK"));
        ObjectNode root = M.createObjectNode().put("schemaVersion", "2.0").put("registryVersion", "test-registry-v2").put("purpose", "TEST");
        root.putObject("rules").set("i", rule);
        return root;
    }
    static ObjectNode numeric(String id) {
        ObjectNode requirement = requirement(id, "REQUIRED");
        requirement.put("valueType", "NUMBER");
        requirement.set("condition", M.createObjectNode().put("conditionId", "c-" + id).put("operator", "GE")
                .put("operand", 5).put("unit", "DAYS").put("promptHint", "实际天数"));
        ObjectNode grading = requirement.putObject("grading").put("mode", "SEGMENTED");
        ArrayNode segments = grading.putArray("segments");
        segments.add(M.createObjectNode().putNull("lower").put("upper", 4).put("lowerInclusive", false).put("upperInclusive", false).put("satisfaction", 0));
        segments.add(M.createObjectNode().put("lower", 4).put("upper", 5).put("lowerInclusive", true).put("upperInclusive", false).put("satisfaction", 0.6));
        segments.add(M.createObjectNode().put("lower", 5).putNull("upper").put("lowerInclusive", true).put("upperInclusive", false).put("satisfaction", 1));
        return requirement;
    }
    private ScoringTestData() { }
}
