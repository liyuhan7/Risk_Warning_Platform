package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.po.analysis.AnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

/** 历史规则兼容回归；旧数据样本固定于测试资源，不对当前指标恢复过时阈值。 */
class KnownRangeIndicatorTest {
    private static final String ID = "4a469621-daba-4ef1-bc0e-5c7983ebc010";

    @Test void currentIndicatorDoesNotInheritTheHistoricalScoringParameters() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("documents/data/indicator.json"))) root = root.getParent();
        assertNotNull(root);
        JsonNode indicator = null;
        for (JsonNode row : M.readTree(root.resolve("documents/data/indicator.json").toFile()))
            if (ID.equals(row.path("id").asText())) indicator = row;
        assertNotNull(indicator);
        assertFalse(indicator.has("maxScore")); assertFalse(indicator.has("calculationRule")); assertFalse(indicator.has("riskRule"));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,0.7", "3,0.3"})
    void scoresConfirmedComplaintResponseSamples(double days, double expected) throws Exception {
        assertEquals(expected, calculate(days).getCalculatedScore());
    }

    @ParameterizedTest
    @CsvSource({"1,false", "2,false", "3,true"})
    void confirmedRatioThresholdProducesCompleteDecisions(double days, boolean risk) throws Exception {
        DeterministicRuleEngine.Evaluation evaluation = evaluate(days);
        assertEquals(risk, evaluation.getDecision().getRiskTriggered());
        assertEquals(risk ? "MEDIUM_RISK" : "NO_RISK", evaluation.getDecision().getRiskLevel().name());
        RuleEvaluationContracts.validate(evaluation.getDecision());
    }

    @Test
    void legacyOutOfMaxScoreDoesNotBecomeStructuredRangeFallback() throws Exception {
        ObjectNode r = registeredRule();
        assertEquals(0.0, r.path("sourceRule").path("calculationRule").path("rangeRule").path("outOfMaxScore").asDouble());
        DeterministicRuleEngine.Evaluation evaluation = evaluate(4);
        assertEquals("INVALID_INPUT", evaluation.getDecision().getStatus().name());
        assertNull(evaluation.getCalculation());
        assertNull(evaluation.getDecision().getCalculatedScore());
    }

    private static ScoreCalculation calculate(double days) throws Exception {
        return evaluate(days).getCalculation();
    }

    private static DeterministicRuleEngine.Evaluation evaluate(double days) throws Exception {
        ObjectNode r = registeredRule(); RuleInputAssembler assembler = new RuleInputAssembler(load(r));
        AnalysisResult a = result("a"); a.setIndicatorId(ID); a.setConditionMet(null);
        a.setObservedValue(days); a.setObservedUnit("day");
        if (days > 2) a.setComplianceStatus(com.riskwarning.common.enums.analysis.ComplianceStatus.NON_COMPLIANT);
        RuleInputAssembler.PreparedInputs p = assembler.prepare(SCOPE, ID, Collections.singletonList(a), r.path("sourceRule"), null);
        return new DeterministicRuleEngine(assembler, new BinaryRuleCalculator(assembler), new RangeRuleCalculator(assembler),
                new RiskThresholdEvaluator()).evaluate(SCOPE, p, r.path("sourceRule"));
    }

    private static ObjectNode registeredRule() throws Exception {
        JsonNode indicator;
        // 来源提交 0fcc2e8；只验证历史 v1 计算契约，不是当前业务的法规依据。
        try (java.io.InputStream input = KnownRangeIndicatorTest.class.getResourceAsStream("/rules/legacy-complaint-indicator.json")) {
            assertNotNull(input); indicator = M.readTree(input);
        }
        assertEquals(1.0, indicator.path("maxScore").asDouble());
        assertEquals("RANGE", indicator.path("calculationRule").path("ruleType").asText());
        assertEquals(0.0, indicator.path("calculationRule").path("rangeRule").path("minValue").asDouble());
        assertEquals(3.0, indicator.path("calculationRule").path("rangeRule").path("maxValue").asDouble());
        assertEquals("x <= 1 ? 1.0 : x <= 2 ? 0.7 : x <= 3 ? 0.3 : 0",
                indicator.path("calculationRule").path("rangeRule").path("calculationMethod").asText());
        ObjectNode source = M.createObjectNode(); source.put("indicatorId", ID);
        source.set("maxScore", indicator.path("maxScore")); source.set("calculationRule", indicator.path("calculationRule"));
        source.set("riskRule", indicator.path("riskRule"));
        ObjectNode r = range(); r.put("indicatorId", ID); r.set("sourceRule", source);
        r.put("sourceRuleHash", RuleCanonicalJson.hash(source)); r.put("maxScore", 1);
        r.put("aggregationOperator", "AVG"); r.put("observation", "用户隐私投诉响应时间");
        ArrayNode segments = r.putArray("segments");
        segment(segments, 0, 1, true, 1); segment(segments, 1, 2, false, 0.7); segment(segments, 2, 3, false, 0.3);
        return r;
    }

    private static void segment(ArrayNode segments, double lower, double upper, boolean lowerInclusive, double score) {
        ObjectNode s = segments.addObject(); s.put("lower", lower); s.put("upper", upper);
        s.put("lowerInclusive", lowerInclusive); s.put("upperInclusive", true); s.put("score", score);
    }
}
