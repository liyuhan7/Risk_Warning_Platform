package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.po.analysis.AnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class BinaryRuleCalculatorTest {
    @Test
    void singleScoresBothBranchesAndRefusesEvenAgreeingMultipleFacts() {
        ObjectNode r = rule("binary"); r.put("aggregationOperator", "SINGLE");
        assertEquals(0, calculate(r, Collections.singletonList(fact("a", true)), null).getCalculatedScore());
        assertEquals(2, calculate(r, Collections.singletonList(fact("a", false)), null).getCalculatedScore());
        assertCode("NEEDS_REVIEW", () -> calculate(r, Arrays.asList(fact("a", false), fact("b", false)), null));
        assertCode("NEEDS_REVIEW", () -> calculate(r, Arrays.asList(fact("a", false), fact("b", true)), null));
    }

    @ParameterizedTest
    @CsvSource({"ALL,true,true,true", "ALL,true,false,false", "ALL,false,false,false",
            "ANY,true,true,true", "ANY,true,false,true", "ANY,false,false,false"})
    void aggregatesConditionsBeforeSelectingScore(String op, boolean first, boolean second, boolean expected) {
        ObjectNode r = rule("binary"); r.put("aggregationOperator", op);
        ScoreCalculation result = calculate(r, Arrays.asList(fact("a", first), fact("b", second)), null);
        assertEquals(expected, result.getConditionMet());
        assertEquals(expected ? 0 : 2, result.getCalculatedScore());
        assertEquals(Arrays.asList("result-a", "result-b"), result.getContributingResultIds());
        assertNull(result.getObservedValue());
    }

    @ParameterizedTest
    @CsvSource({"true,2,0,2", "false,2,0,0", "true,0,2,0", "false,0,2,2", "true,1,1,1", "false,1,1,1"})
    void scoresForwardInverseAndEqualBranches(boolean condition, double trueScore, double falseScore, double expected) {
        ObjectNode r = rule("binary"); r.put("trueScore", trueScore); r.put("falseScore", falseScore);
        ScoreCalculation result = calculate(r, Collections.singletonList(fact("a", condition)), null);
        assertEquals(expected, result.getCalculatedScore());
        assertEquals(2, result.getMaxPossibleScore());
        assertTrue(result.getInputHash().matches("[a-f0-9]{64}"));
        assertThrows(UnsupportedOperationException.class, () -> result.getContributingResultIds().add("forged"));
    }

    @Test
    void latestUsesObservationTimeAndRetainsAllEqualLatestFacts() {
        ObjectNode r = rule("binary"); r.put("aggregationOperator", "LATEST");
        Map<String, LocalDateTime> times = times();
        AnalysisResult a = fact("a", true); a.setAnalyzedAt(times.get("b").plusYears(1));
        ScoreCalculation score = calculate(r, Arrays.asList(fact("c", false), a, fact("b", false)), times);
        assertFalse(score.getConditionMet());
        assertEquals(2, score.getCalculatedScore());
        assertEquals(Arrays.asList("result-b", "result-c"), score.getContributingResultIds());
    }

    @Test
    void latestRejectsContradictoryTiesEvenIfScoresEqualAndRequiresTime() {
        ObjectNode r = rule("binary"); r.put("aggregationOperator", "LATEST");
        r.put("trueScore", 1); r.put("falseScore", 1);
        assertCode("NEEDS_REVIEW", () -> calculate(r, Arrays.asList(fact("b", true), fact("c", false)), times()));
        assertCode("NEEDS_REVIEW", () -> calculate(r, Collections.singletonList(fact("a", false)), null));
    }

    @Test
    void orderingIsStableAndSemanticTextDoesNotOverrideCondition() {
        ObjectNode r = rule("binary");
        AnalysisResult a = fact("a", true); a.setReasoning("模型认为无风险，不能用于反推分数");
        ScoreCalculation first = calculate(r, Arrays.asList(a, fact("b", false)), null);
        ScoreCalculation second = calculate(r, Arrays.asList(fact("b", false), a), null);
        assertEquals(first, second);
        assertEquals(0, first.getCalculatedScore());
    }

    @Test
    void refusesDuplicateUncertainAndMutatedInputsAndSourceDrift() {
        ObjectNode r = rule("binary");
        assertCode("INVALID_INPUT", () -> calculate(r, Arrays.asList(fact("a", true), fact("a", false)), null));
        AnalysisResult unknown = fact("a", true); unknown.setConditionMet(null);
        assertCode("INVALID_INPUT", () -> calculate(r, Collections.singletonList(unknown), null));
        RuleInputAssembler assembler = new RuleInputAssembler(load(r));
        RuleInputAssembler.PreparedInputs inputs = assembler.prepare(SCOPE, "binary", Collections.singletonList(fact("a", true)), source("binary"), null);
        BinaryRuleCalculator calculator = new BinaryRuleCalculator(assembler);
        inputs.getInputs().get(0).setConditionMet(false);
        assertCode("INPUT_HASH_MISMATCH", () -> calculator.calculate(SCOPE, inputs, source("binary")));
        ObjectNode changed = source("binary"); changed.put("maxScore", 3);
        assertCode("SOURCE_RULE_HASH_MISMATCH", () -> calculator.calculate(SCOPE, inputs, changed));
    }

    @Test
    void doesNotAcceptRangeInputs() {
        ObjectNode r = range(); RuleInputAssembler assembler = new RuleInputAssembler(load(r));
        AnalysisResult a = fact("a", false); a.setIndicatorId("range"); a.setConditionMet(null); a.setObservedValue(0.0); a.setObservedUnit("day");
        RuleInputAssembler.PreparedInputs inputs = assembler.prepare(SCOPE, "range", Collections.singletonList(a), source("range"), null);
        assertCode("UNSUPPORTED_RULE", () -> new BinaryRuleCalculator(assembler).calculate(SCOPE, inputs, source("range")));
    }

    private static AnalysisResult fact(String id, boolean condition) {
        AnalysisResult result = result(id); result.setConditionMet(condition); return result;
    }
    private static ScoreCalculation calculate(ObjectNode r, List<AnalysisResult> facts, Map<String, LocalDateTime> times) {
        RuleInputAssembler assembler = new RuleInputAssembler(load(r));
        return new BinaryRuleCalculator(assembler).calculate(SCOPE,
                assembler.prepare(SCOPE, "binary", facts, source("binary"), times), source("binary"));
    }
    private static Map<String, LocalDateTime> times() {
        Map<String, LocalDateTime> times = new HashMap<>();
        times.put("a", LocalDateTime.of(2026, 9, 29, 10, 0));
        times.put("b", LocalDateTime.of(2026, 9, 30, 10, 0)); times.put("c", times.get("b")); return times;
    }
    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(RuleContractException.class, action).getCode());
    }
}
