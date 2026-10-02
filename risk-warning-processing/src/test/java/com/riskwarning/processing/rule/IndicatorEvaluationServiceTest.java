package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.processing.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.riskwarning.processing.rule.RuleTestData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndicatorEvaluationServiceTest {
    private final RuleEvaluationAuditWriter writer = mock(RuleEvaluationAuditWriter.class);
    private IndicatorEvaluationService service(ObjectNode rule) {
        RuleInputAssembler assembler = new RuleInputAssembler(load(rule));
        return new IndicatorEvaluationService(assembler, new DeterministicRuleEngine(assembler,
                new BinaryRuleCalculator(assembler), new RangeRuleCalculator(assembler), new RiskThresholdEvaluator()), writer);
    }
    private Behavior behavior(String id) {
        return Behavior.builder().id(id).projectId(1L).assessmentId(2L).analysisRunId("run").build();
    }
    private RetrievalAudit audit(String behavior, String indicator) {
        return RetrievalAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").behaviorId(behavior)
                .retrievalStatus(RetrievalAuditStatus.SUCCESS).analysisStatus(AnalysisAuditStatus.ANALYZED)
                .candidates(Collections.singletonList(RetrievalCandidateSnapshot.builder().name("指标").indicatorLevel(3)
                        .result(RetrievalResult.builder().candidateId(indicator).candidateType(RetrievalCandidateType.INDICATOR).build()).build())).build();
    }

    @Test
    void aggregatesTwoFactsIntoOneAuthoritativeResultWithTrace() {
        service(rule("binary")).evaluate(SCOPE, Arrays.asList(behavior("a"), behavior("b")), Arrays.asList(result("b"), result("a")),
                Arrays.asList(audit("a", "binary"), audit("b", "binary")), Collections.singletonMap("binary", source("binary")));
        ArgumentCaptor<RuleEvaluationAudit> audit = ArgumentCaptor.forClass(RuleEvaluationAudit.class);
        ArgumentCaptor<IndicatorResult> result = ArgumentCaptor.forClass(IndicatorResult.class);
        verify(writer).save(eq(SCOPE), audit.capture(), result.capture());
        assertEquals(RuleEvaluationStatus.DECIDED, audit.getValue().getStatus());
        assertEquals(2d, result.getValue().getCalculatedScore()); assertFalse(result.getValue().getRiskTriggered());
        assertEquals(2, result.getValue().getCalculationDetails().getTraces().size());
        assertEquals("a", result.getValue().getCalculationDetails().getRuleCalculation().getRuleInputs().get(0).getBehaviorId());
        assertEquals("REASONING_RULES_V1", result.getValue().getCalculationDetails().getAnalysisMode());
    }

    @Test
    void keepsConflictDecision() {
        AnalysisResult result = result("a"); result.setConditionMet(true);
        service(rule("binary")).evaluate(SCOPE, Arrays.asList(behavior("a")), Arrays.asList(result),
                Arrays.asList(audit("a", "binary")), Collections.singletonMap("binary", source("binary")));
        ArgumentCaptor<RuleEvaluationAudit> audit = ArgumentCaptor.forClass(RuleEvaluationAudit.class);
        verify(writer).save(eq(SCOPE), audit.capture(), any(IndicatorResult.class));
        assertEquals(RuleEvaluationStatus.DECIDED_WITH_CONFLICT, audit.getValue().getStatus());
        assertEquals(Arrays.asList("result-a"), audit.getValue().getCalculationDetails().getRuleCalculation().getAggregationDetails().get("conflictingResultIds"));
    }

    @Test
    void unsupportedIndicatorKeepsAuditWithoutPlaceholderScore() {
        AnalysisResult result = result("a"); result.setIndicatorId("unregistered");
        service(rule("binary")).evaluate(SCOPE, Arrays.asList(behavior("a")), Arrays.asList(result),
                Arrays.asList(audit("a", "unregistered")), Collections.emptyMap());
        ArgumentCaptor<RuleEvaluationAudit> audit = ArgumentCaptor.forClass(RuleEvaluationAudit.class);
        verify(writer).save(eq(SCOPE), audit.capture(), isNull());
        assertEquals(RuleEvaluationStatus.NEEDS_REVIEW, audit.getValue().getStatus());
        assertNull(audit.getValue().getCalculationDetails().getRuleCalculation().getFinalDecision().getCalculatedScore());
    }

    @Test
    void rangeOutsideDomainIsUndecided() {
        AnalysisResult result = result("a"); result.setIndicatorId("range"); result.setConditionMet(null);
        result.setObservedValue(4d); result.setObservedUnit("day");
        service(range()).evaluate(SCOPE, Arrays.asList(behavior("a")), Arrays.asList(result),
                Arrays.asList(audit("a", "range")), Collections.singletonMap("range", source("range")));
        ArgumentCaptor<RuleEvaluationAudit> audit = ArgumentCaptor.forClass(RuleEvaluationAudit.class);
        verify(writer).save(eq(SCOPE), audit.capture(), isNull());
        assertEquals(RuleEvaluationStatus.INVALID_INPUT, audit.getValue().getStatus());
    }

    @Test
    void latestMissingObservationDoesNotUseAnalysisTime() {
        ObjectNode rule = rule("binary"); rule.put("aggregationOperator", "LATEST");
        service(rule).evaluate(SCOPE, Arrays.asList(behavior("a")), Arrays.asList(result("a")),
                Arrays.asList(audit("a", "binary")), Collections.singletonMap("binary", source("binary")));
        ArgumentCaptor<RuleEvaluationAudit> audit = ArgumentCaptor.forClass(RuleEvaluationAudit.class);
        verify(writer).save(eq(SCOPE), audit.capture(), isNull());
        assertEquals(RuleEvaluationStatus.NEEDS_REVIEW, audit.getValue().getStatus());
    }

    @Test
    void rejectsCrossRunAndCandidateDriftBeforeWriting() {
        IndicatorEvaluationService service = service(rule("binary"));
        AnalysisResult bad = result("a"); bad.setAnalysisRunId("other");
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(SCOPE, Arrays.asList(behavior("a")), Arrays.asList(bad),
                Arrays.asList(audit("a", "binary")), Collections.singletonMap("binary", source("binary"))));
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(SCOPE, Arrays.asList(behavior("a")), Arrays.asList(result("a")),
                Arrays.asList(audit("a", "other")), Collections.singletonMap("binary", source("binary"))));
        ObjectNode drift = source("binary"); drift.put("maxScore", 99);
        assertThrows(RuleContractException.class, () -> service.evaluate(SCOPE, Arrays.asList(behavior("a")), Arrays.asList(result("a")),
                Arrays.asList(audit("a", "binary")), Collections.singletonMap("binary", drift)));
        verifyNoInteractions(writer);
    }
}
