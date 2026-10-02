package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.AnalysisResult;
import java.time.LocalDateTime;
import java.util.Collections;

final class RuleTestData {
    static final ObjectMapper M = new ObjectMapper();
    static final AnalysisScope SCOPE = new AnalysisScope(1L, 2L, "run");
    static ObjectNode source(String id) {
        ObjectNode n = M.createObjectNode(); n.put("indicatorId", id); n.put("maxScore", 2);
        n.putObject("calculationRule").put("condition", "是否缺少许可").put("trueScore", 0).put("falseScore", 2);
        n.putNull("riskRule"); return n;
    }
    static ObjectNode rule(String id) {
        ObjectNode r = M.createObjectNode(); r.put("indicatorId", id); r.put("enabled", true);
        r.set("sourceRule", source(id)); r.put("sourceRuleHash", RuleCanonicalJson.hash(source(id)));
        r.put("ruleVersion", "rule-v1"); r.put("ruleType", "BINARY"); r.put("aggregationOperator", "ANY");
        r.put("condition", "是否缺少许可"); r.put("trueScore", 0); r.put("falseScore", 2); r.put("maxScore", 2);
        r.put("thresholdVersion", "threshold-v1");
        ObjectNode risk = r.putArray("riskIntervals").addObject();
        risk.put("lower", 0); risk.put("upper", 0.5); risk.put("lowerInclusive", true); risk.put("upperInclusive", false); risk.put("riskLevel", "MEDIUM_RISK");
        r.put("verifiedBy", "engineering-test"); r.put("verifiedAt", "2026-09-30T10:00:00"); r.put("basis", "独立工程测试，非生产业务依据");
        return r;
    }
    static ObjectNode registry(ObjectNode rule) {
        ObjectNode doc = M.createObjectNode(); doc.put("schemaVersion", "1.0"); doc.put("registryVersion", "test-v1"); doc.put("purpose", "TEST");
        doc.putObject("rules").set(rule.path("indicatorId").asText(), rule); return doc;
    }
    static VerifiedRuleRegistry load(ObjectNode rule) { return VerifiedRuleRegistry.fromJson(registry(rule).toString(), true); }
    static ObjectNode range() {
        ObjectNode r = rule("range"); r.put("ruleType", "RANGE"); r.put("aggregationOperator", "AVG");
        r.remove("condition"); r.remove("trueScore"); r.remove("falseScore");
        r.put("inputUnit", "day"); r.put("observation", "平均响应时间");
        ArrayNode segments = r.putArray("segments");
        ObjectNode a = segments.addObject(); a.put("lower", 0); a.put("upper", 1); a.put("lowerInclusive", true); a.put("upperInclusive", true); a.put("score", 2);
        ObjectNode b = segments.addObject(); b.put("lower", 1); b.put("upper", 3); b.put("lowerInclusive", false); b.put("upperInclusive", true); b.put("score", 0);
        return r;
    }
    static AnalysisResult result(String behavior) {
        return AnalysisResult.builder().id("result-"+behavior).schemaVersion("1.1").analysisRunId("run").assessmentId(2L)
                .behaviorId(behavior).indicatorId("binary").evidenceIds(Collections.singletonList("evidence"))
                .applicable(Applicability.APPLICABLE).complianceStatus(ComplianceStatus.COMPLIANT).conditionMet(false)
                .analyzedAt(LocalDateTime.of(2026,9,30,10,0)).build();
    }
    private RuleTestData() { }
}
