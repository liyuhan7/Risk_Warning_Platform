package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.po.analysis.AnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class RangeRuleCalculatorTest {
    @Test
    void singleScoresObservationAndRefusesMultipleFactsAndOutsideDomain() {
        ObjectNode r = range(); r.put("aggregationOperator", "SINGLE");
        assertEquals(2, calculate(r, Collections.singletonList(fact("a", 1)), null).getCalculatedScore());
        assertEquals(0, calculate(r, Collections.singletonList(fact("a", 3)), null).getCalculatedScore());
        assertCode("NEEDS_REVIEW", () -> calculate(r, Arrays.asList(fact("a", 1), fact("b", 1)), null));
        assertCode("NEEDS_REVIEW", () -> calculate(r, Arrays.asList(fact("a", 1), fact("b", 2)), null));
        assertCode("INVALID_INPUT", () -> calculate(r, Collections.singletonList(fact("a", 4)), null));
    }

    @ParameterizedTest
    @CsvSource({"0,2", "1,2", "1.0000000000000002,0", "3,0", "-0.0,2"})
    void matchesOpenClosedBoundariesAndZero(double value, double score) {
        ScoreCalculation result = calculate(range(), Collections.singletonList(fact("a", value)), null);
        assertEquals(score, result.getCalculatedScore());
        assertEquals(value == 0 ? 0.0 : value, result.getObservedValue());
        assertNull(result.getConditionMet());
    }

    @ParameterizedTest
    @CsvSource({"MIN,0.5,2", "MAX,2.5,0", "SUM,3,0", "AVG,1.5,0"})
    void aggregatesObservationsRatherThanScores(String op, double expected, double score) {
        ObjectNode r = range(); r.put("aggregationOperator", op);
        ScoreCalculation result = calculate(r, Arrays.asList(fact("b", 2.5), fact("a", 0.5)), null);
        assertEquals(expected, result.getObservedValue());
        assertEquals(score, result.getCalculatedScore());
        assertEquals(Arrays.asList("result-a", "result-b"), result.getContributingResultIds());
    }

    @Test
    void doesNotRoundOrClampOutsideDomainAndHandlesOpenOuterBounds() {
        assertCode("INVALID_INPUT", () -> calculate(range(), Collections.singletonList(fact("a", -0.1)), null));
        assertCode("INVALID_INPUT", () -> calculate(range(), Collections.singletonList(fact("a", Math.nextUp(3.0))), null));
        ObjectNode r = range(); r.put("aggregationOperator", "SUM");
        assertCode("INVALID_INPUT", () -> calculate(r, Arrays.asList(fact("a", 2), fact("b", 2)), null));
        ObjectNode open = range(); ((ObjectNode) open.path("segments").get(0)).put("lowerInclusive", false);
        assertCode("INVALID_INPUT", () -> calculate(open, Collections.singletonList(fact("a", 0)), null));
    }

    @ParameterizedTest
    @CsvSource({"MIN", "MAX", "SUM", "AVG"})
    void aggregationCannotHideOutOfDomainFacts(String op) {
        ObjectNode r = range(); r.put("aggregationOperator", op);
        assertCode("INVALID_INPUT", () -> calculate(r, Arrays.asList(fact("a", -1), fact("b", 2)), null));
        assertCode("INVALID_INPUT", () -> calculate(r, Arrays.asList(fact("a", 0), fact("b", 4)), null));
    }

    @Test
    void decimalSumAndAverageMatchBoundaryWithoutEpsilon() {
        ObjectNode r = range(); r.put("aggregationOperator", "SUM");
        ((ObjectNode) r.path("segments").get(0)).put("upper", 0.3);
        ((ObjectNode) r.path("segments").get(1)).put("lower", 0.3);
        assertEquals(2, calculate(r, Arrays.asList(fact("a", 0.1), fact("b", 0.2)), null).getCalculatedScore());
        r.put("aggregationOperator", "AVG");
        assertEquals(2, calculate(r, Arrays.asList(fact("a", 0.2), fact("b", 0.4)), null).getCalculatedScore());
    }

    @Test
    void averageAvoidsIntermediateOverflowAndSumRejectsNonfiniteResult() {
        ObjectNode r = range();
        ArrayNode segments = r.putArray("segments");
        ObjectNode segment = segments.addObject(); segment.put("lower", 0); segment.put("upper", Double.MAX_VALUE);
        segment.put("lowerInclusive", true); segment.put("upperInclusive", true); segment.put("score", 2);
        List<AnalysisResult> facts = Arrays.asList(fact("a", 1e308), fact("b", 1e308));
        assertEquals(1e308, calculate(r, facts, null).getObservedValue());
        r.put("aggregationOperator", "SUM");
        assertCode("INVALID_INPUT", () -> calculate(r, facts, null));
    }

    @Test
    void latestPreservesEqualTiesButRejectsDifferentObservationsEvenInSameSegment() {
        ObjectNode r = range(); r.put("aggregationOperator", "LATEST");
        Map<String, LocalDateTime> times = new HashMap<>();
        times.put("a", LocalDateTime.of(2026, 9, 29, 10, 0));
        times.put("b", LocalDateTime.of(2026, 9, 30, 10, 0)); times.put("c", times.get("b"));
        AnalysisResult old = fact("a", 0.5); old.setAnalyzedAt(times.get("b").plusYears(1));
        ScoreCalculation score = calculate(r, Arrays.asList(fact("c", 2), old, fact("b", 2)), times);
        assertEquals(2, score.getObservedValue());
        assertEquals(Arrays.asList("result-b", "result-c"), score.getContributingResultIds());
        assertCode("NEEDS_REVIEW", () -> calculate(r, Arrays.asList(fact("b", 2), fact("c", 2.5)), times));
        assertCode("NEEDS_REVIEW", () -> calculate(r, Collections.singletonList(old), null));
    }

    @Test
    void assemblyRejectsMissingNonfiniteWrongUnitAndMixedFields() {
        for (Double number : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            AnalysisResult a = fact("a", 0); a.setObservedValue(number);
            assertCode("INVALID_INPUT", () -> calculate(range(), Collections.singletonList(a), null));
        }
        AnalysisResult wrongUnit = fact("a", 24); wrongUnit.setObservedUnit("hour");
        assertCode("INVALID_INPUT", () -> calculate(range(), Collections.singletonList(wrongUnit), null));
        AnalysisResult mixed = fact("a", 1); mixed.setConditionMet(true);
        assertCode("INVALID_INPUT", () -> calculate(range(), Collections.singletonList(mixed), null));
        assertCode("INVALID_INPUT", () -> calculate(range(), Collections.emptyList(), null));
    }

    @Test
    void verifiesInputAndSourceAndRejectsBinary() {
        RuleInputAssembler assembler = new RuleInputAssembler(load(range()));
        RuleInputAssembler.PreparedInputs p = assembler.prepare(SCOPE, "range", Collections.singletonList(fact("a", 1)), source("range"), null);
        p.getInputs().get(0).setObservedValue(2.0);
        assertCode("INPUT_HASH_MISMATCH", () -> new RangeRuleCalculator(assembler).calculate(SCOPE, p, source("range")));
        ObjectNode drift = source("range"); drift.put("maxScore", 3);
        assertCode("SOURCE_RULE_HASH_MISMATCH", () -> new RangeRuleCalculator(assembler).calculate(SCOPE, p, drift));
        RuleInputAssembler binary = new RuleInputAssembler(load(rule("binary")));
        RuleInputAssembler.PreparedInputs b = binary.prepare(SCOPE, "binary", Collections.singletonList(result("a")), source("binary"), null);
        assertCode("UNSUPPORTED_RULE", () -> new RangeRuleCalculator(binary).calculate(SCOPE, b, source("binary")));
    }

    @Test
    void orderIsStableAndObservationNotGapIsScored() {
        AnalysisResult a = fact("a", 0.5); a.setGapValue(999.0);
        List<AnalysisResult> facts = Arrays.asList(a, fact("b", 1.5));
        ScoreCalculation score = calculate(range(), facts, null);
        assertEquals(1, score.getObservedValue()); assertEquals(2, score.getCalculatedScore());
        Collections.reverse(facts);
        assertEquals(score, calculate(range(), facts, null));
    }

    private static AnalysisResult fact(String id, double value) {
        AnalysisResult a = result(id); a.setIndicatorId("range"); a.setConditionMet(null);
        a.setObservedValue(value); a.setObservedUnit("day"); return a;
    }
    private static ScoreCalculation calculate(ObjectNode r, List<AnalysisResult> facts, Map<String, LocalDateTime> times) {
        RuleInputAssembler assembler = new RuleInputAssembler(load(r));
        return new RangeRuleCalculator(assembler).calculate(SCOPE,
                assembler.prepare(SCOPE, "range", facts, source("range"), times), source("range"));
    }
    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(RuleContractException.class, action).getCode());
    }
}
