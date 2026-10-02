package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.indicator.*;
import com.riskwarning.processing.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleEvaluationAuditWriterTest {
    private final AnalysisScope scope = new AnalysisScope(1L, 2L, "run");
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final RuleEvaluationAuditRepository audits = mock(RuleEvaluationAuditRepository.class);
    private final IndicatorResultRepository results = mock(IndicatorResultRepository.class);
    private final RuleEvaluationAuditWriter writer = new RuleEvaluationAuditWriter(runs, audits, results);
    private AnalysisRun run;
    private RuleEvaluationAudit audit;
    private IndicatorResult result;

    @BeforeEach
    void setup() {
        run = AnalysisRun.start(scope, LocalDateTime.now(), AnalysisExecutionMode.REASONING);
        run.freezeBehaviors(Arrays.asList("b"));
        when(runs.lockScope("run", 2L, 1L)).thenReturn(Optional.of(run));
        String hash = String.join("", Collections.nCopies(64, "a"));
        RuleEvaluationResult decision = RuleEvaluationResult.builder().indicatorId("i").ruleType(RuleTypeEnum.BINARY)
                .status(RuleEvaluationStatus.DECIDED).inputHash(hash).ruleVersion("v1").thresholdVersion("t1")
                .aggregationOperator(AggregationOperator.ALL).calculatedScore(1d).maxPossibleScore(1d)
                .riskTriggered(false).riskLevel(RiskLevelEnum.NO_RISK).decision("NO_RISK").conflict(false).build();
        RuleInput input = RuleInput.builder().projectId(1L).assessmentId(2L).analysisRunId("run")
                .behaviorId("b").indicatorId("i").build();
        RuleCalculationDetail ruleCalculation = RuleCalculationDetail.builder().inputHash(hash).ruleVersion("v1").thresholdVersion("t1")
                .aggregationOperator("ALL").conflict(false).finalDecision(decision).ruleInputs(Arrays.asList(input)).build();
        IndicatorResultDetail detail = IndicatorResultDetail.builder().schemaVersion("1.1").analysisMode("REASONING_RULES_V1").ruleCalculation(ruleCalculation).build();
        audit = RuleEvaluationAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").indicatorId("i")
                .inputHash(hash).ruleVersion("v1").thresholdVersion("t1").aggregationOperator("ALL")
                .status(RuleEvaluationStatus.DECIDED).conflict(false).calculationDetails(detail).build();
        result = IndicatorResult.builder().projectId(1L).assessmentId(2L).analysisRunId("run").indicatorEsId("i")
                .calculatedScore(1d).maxPossibleScore(1d).riskTriggered(false).riskLevel(RiskLevelEnum.NO_RISK)
                .usedCalculationRuleType("BINARY").riskStatus(IndicatorRiskStatus.EVALUATED).calculationDetails(detail).build();
    }

    @Test
    void persistsMatchingAuditAndDecision() {
        assertTrue(writer.save(scope, audit, result));
        verify(audits).saveAndFlush(audit); verify(results).saveAndFlush(result);
    }

    @Test
    void skipsIdenticalReplayButRejectsDrift() {
        when(audits.findByAnalysisRunIdAndIndicatorId("run", "i")).thenReturn(Optional.of(audit));
        when(results.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(2L, "run", "i")).thenReturn(Optional.of(result));
        assertFalse(writer.save(scope, audit, result));
        RuleEvaluationAudit other = RuleEvaluationAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run")
                .indicatorId("i").inputHash("different").status(audit.getStatus()).calculationDetails(audit.getCalculationDetails()).build();
        assertThrows(IllegalStateException.class, () -> writer.save(scope, other, result));
        verify(audits, never()).saveAndFlush(any()); verify(results, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDifferentResultAndFrozenScopeDrift() {
        result.setCalculatedScore(0d);
        assertThrows(IllegalStateException.class, () -> writer.save(scope, audit, result));
        result.setCalculatedScore(1d);
        audit.getCalculationDetails().getRuleCalculation().getRuleInputs().get(0).setBehaviorId("other");
        assertThrows(IllegalStateException.class, () -> writer.save(scope, audit, result));
        verify(audits, never()).saveAndFlush(any());
    }

    @Test
    void savesUndecidedAuditWithoutPlaceholderResult() {
        RuleEvaluationResult decision = audit.getCalculationDetails().getRuleCalculation().getFinalDecision();
        decision.setStatus(RuleEvaluationStatus.INVALID_INPUT); decision.setCalculatedScore(null); decision.setMaxPossibleScore(null);
        decision.setRiskTriggered(null); decision.setRiskLevel(null); decision.setDecision(null); decision.setErrors(Arrays.asList("域外值"));
        audit.setStatus(RuleEvaluationStatus.INVALID_INPUT);
        assertThrows(IllegalStateException.class, () -> writer.save(scope, audit, result));
        assertTrue(writer.save(scope, audit, null));
        verify(audits).saveAndFlush(audit); verify(results, never()).saveAndFlush(any());
    }

    @Test
    void rejectsOrphanResultAndPreservesTerminalRun() {
        when(results.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(2L, "run", "i")).thenReturn(Optional.of(result));
        assertThrows(IllegalStateException.class, () -> writer.save(scope, audit, result));
        run.completeWithoutDecision(LocalDateTime.now());
        assertFalse(writer.save(scope, audit, result));
        verify(audits, never()).saveAndFlush(any()); verify(results, never()).saveAndFlush(any());
    }
}
