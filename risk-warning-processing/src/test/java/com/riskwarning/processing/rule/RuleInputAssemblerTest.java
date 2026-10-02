package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.po.analysis.AnalysisResult;
import com.riskwarning.common.enums.analysis.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class RuleInputAssemblerTest {
    private RuleInputAssembler assembler() { return new RuleInputAssembler(load(rule("binary"))); }
    private RuleInputAssembler.PreparedInputs prepare(List<AnalysisResult> rows) { return assembler().prepare(SCOPE,"binary",rows,source("binary"),Collections.emptyMap()); }
    @Test void typedInputPreservesFalseAndStableHashAcrossInputOrder() {
        AnalysisResult a = result("a"), b = result("b"); b.setConditionMet(true);
        RuleInputAssembler.PreparedInputs p = prepare(Arrays.asList(b,a));
        assertEquals(false, p.getInputs().get(0).getConditionMet());
        assertEquals(p.getInputHash(), prepare(Arrays.asList(a,b)).getInputHash());
        b.setConditionMet(false); assertNotEquals(p.getInputHash(), prepare(Arrays.asList(a,b)).getInputHash());
    }
    @Test void scopeDuplicateMissingAndUndecidedInputsFail() {
        AnalysisResult a = result("a"); a.setAnalysisRunId("foreign"); assertThrows(RuleContractException.class, () -> prepare(Collections.singletonList(a)));
        a.setAnalysisRunId("run"); assertThrows(RuleContractException.class, () -> prepare(Arrays.asList(a,a)));
        a.setConditionMet(null); assertThrows(RuleContractException.class, () -> prepare(Collections.singletonList(a)));
        a.setComplianceStatus(ComplianceStatus.NEEDS_REVIEW);
        assertEquals("NEEDS_REVIEW", assertThrows(RuleContractException.class, () -> prepare(Collections.singletonList(a))).getCode());
    }
    @Test void observationsUseActualValueAndRejectWrongUnitsNonfiniteAndMixedFields() {
        RuleInputAssembler assembler = new RuleInputAssembler(load(range()));
        AnalysisResult a = result("a"); a.setIndicatorId("range"); a.setConditionMet(null); a.setObservedValue(0.0); a.setObservedUnit("day"); a.setGapValue(2.0);
        assertEquals(0.0, assembler.prepare(SCOPE,"range",Collections.singletonList(a),source("range"),null).getInputs().get(0).getObservedValue());
        a.setObservedUnit("hour"); assertThrows(RuleContractException.class, () -> assembler.prepare(SCOPE,"range",Collections.singletonList(a),source("range"),null));
        a.setObservedUnit("day"); a.setObservedValue(Double.NaN); assertThrows(RuleContractException.class, () -> assembler.prepare(SCOPE,"range",Collections.singletonList(a),source("range"),null));
        a.setObservedValue(0.0); a.setConditionMet(false); assertThrows(RuleContractException.class, () -> assembler.prepare(SCOPE,"range",Collections.singletonList(a),source("range"),null));
    }
    @Test void latestRequiresFactTimeAndDoesNotUseAnalysisTime() {
        ObjectNode r = rule("binary"); r.put("aggregationOperator","LATEST");
        RuleInputAssembler assembler = new RuleInputAssembler(load(r)); AnalysisResult a = result("a");
        assertThrows(RuleContractException.class, () -> assembler.prepare(SCOPE,"binary",Collections.singletonList(a),source("binary"),null));
        LocalDateTime time = LocalDateTime.of(2026,1,1,0,0);
        assertEquals(time, assembler.prepare(SCOPE,"binary",Collections.singletonList(a),source("binary"),Collections.singletonMap("a",time)).getInputs().get(0).getFactObservedAt());
    }
    @Test void hashIncludesVersionsAndExplicitAggregation() {
        List<AnalysisResult> rows = Collections.singletonList(result("a")); String before = prepare(rows).getInputHash();
        ObjectNode r = rule("binary"); r.put("ruleVersion","rule-v2");
        assertNotEquals(before, new RuleInputAssembler(load(r)).prepare(SCOPE,"binary",rows,source("binary"),null).getInputHash());
        r = rule("binary"); r.put("aggregationOperator","ALL");
        assertNotEquals(before, new RuleInputAssembler(load(r)).prepare(SCOPE,"binary",rows,source("binary"),null).getInputHash());
    }
    @Test void forgedOrMutatedPreparedHashIsRejected() {
        RuleInputAssembler assembler = assembler();
        RuleInputAssembler.PreparedInputs p = prepare(Collections.singletonList(result("a")));
        assembler.verify(SCOPE,p,source("binary"));
        RuleInputAssembler.PreparedInputs forged = new RuleInputAssembler.PreparedInputs(p.getRule(),p.getInputs(),"bad-hash");
        assertThrows(RuleContractException.class, () -> assembler.verify(SCOPE,forged,source("binary")));
        p.getInputs().get(0).setConditionMet(true);
        assertThrows(RuleContractException.class, () -> assembler.verify(SCOPE,p,source("binary")));
    }
}
