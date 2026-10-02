package com.riskwarning.report.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.analysis.AnalysisResult;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.po.indicator.*;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.report.repository.*;
import com.riskwarning.report.service.ReportService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReasoningReportProjectionTest {
    private final AssessmentServiceImpl service = new AssessmentServiceImpl();
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final IndicatorResultRepository indicators = mock(IndicatorResultRepository.class);
    private final AssessmentRepository assessments = mock(AssessmentRepository.class);
    private final ElasticsearchClient es = mock(ElasticsearchClient.class);
    private final ReportService reports = mock(ReportService.class);
    private AnalysisRun run;
    private Assessment assessment;
    private IndicatorResult result;

    @BeforeEach void setup() throws Exception {
        run = AnalysisRun.start(new AnalysisScope(1L, 2L, "run"), LocalDateTime.now(), AnalysisExecutionMode.REASONING);
        run.freezeBehaviors(Arrays.asList("b"));
        run.prepareReport(new ObjectMapper().createObjectNode().put("completionKind", "FULL").put("decisionCount", 1));
        assessment = Assessment.builder().id(2L).projectId(1L).build();
        String hash = String.join("", Collections.nCopies(64, "a"));
        RuleEvaluationResult decision = RuleEvaluationResult.builder().indicatorId("i").status(RuleEvaluationStatus.DECIDED)
                .inputHash(hash).ruleVersion("r").thresholdVersion("t").calculatedScore(0.9).maxPossibleScore(1d)
                .riskTriggered(true).riskLevel(RiskLevelEnum.HIGH_RISK).conflict(false).build();
        RuleCalculationDetail detail = RuleCalculationDetail.builder().inputHash(hash).ruleVersion("r").thresholdVersion("t")
                .conflict(false).finalDecision(decision).ruleInputs(Arrays.asList(RuleInput.builder().projectId(1L).assessmentId(2L)
                        .analysisRunId("run").indicatorId("i").behaviorId("b").build())).build();
        result = IndicatorResult.builder().projectId(1L).assessmentId(2L).analysisRunId("run").indicatorEsId("i").indicatorName("indicator")
                .calculatedScore(0.9).maxPossibleScore(1d).riskTriggered(true).riskLevel(RiskLevelEnum.HIGH_RISK)
                .riskStatus(IndicatorRiskStatus.EVALUATED).calculationDetails(IndicatorResultDetail.builder().schemaVersion("1.1")
                        .analysisMode("REASONING_RULES_V1").ruleCalculation(detail).build()).build();
        RuleInput input = detail.getRuleInputs().get(0); input.setAnalysisResultId("semantic");
        input.setEvidenceIds(Arrays.asList("e")); input.setConditionMet(true); input.setComplianceStatus(ComplianceStatus.COMPLIANT);
        AnalysisResult semantic = AnalysisResult.builder().id("semantic").schemaVersion("1.1").assessmentId(2L).analysisRunId("run")
                .behaviorId("b").indicatorId("i").evidenceIds(Arrays.asList("e")).conditionMet(true).complianceStatus(ComplianceStatus.COMPLIANT)
                .enterpriseFact("冻结事实").reasoning("冻结判断").regulationIds(Arrays.asList("law")).requirement("冻结要求").build();
        RetrievalCandidateSnapshot law = RetrievalCandidateSnapshot.builder().name("冻结法规").content("冻结原文")
                .contentHash(EvidenceChunk.sha256("冻结原文")).result(RetrievalResult.builder().candidateType(RetrievalCandidateType.REGULATION)
                        .candidateId("law").behaviorId("b").assessmentId(2L).analysisRunId("run").build()).build();
        result.getCalculationDetails().setTraces(Arrays.asList(AnalysisTrace.builder().behaviorId("b").evidenceIds(Arrays.asList("e"))
                .analysisResult(semantic).candidates(Arrays.asList(law)).build()));
        result.setCalculatedAt(LocalDateTime.now());
        ReflectionTestUtils.setField(service, "analysisRunRepository", runs);
        ReflectionTestUtils.setField(service, "indicatorResultRepository", indicators);
        ReflectionTestUtils.setField(service, "assessmentRepository", assessments);
        ReflectionTestUtils.setField(service, "elasticsearchClient", es);
        ReflectionTestUtils.setField(service, "reportService", reports);
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.of(run));
        when(assessments.findById(2L)).thenReturn(Optional.of(assessment));
        when(indicators.findByAssessmentIdAndAnalysisRunId(2L, "run")).thenReturn(Arrays.asList(result));
        when(es.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.errors(false).took(1).items(Collections.emptyList())));
    }

    @Test void highScoreStillProjectsExplicitRiskWithoutUpdatingDecision() throws Exception {
        service.aggregateInformation(3L, 1L, 2L, "run");
        verify(es).bulk(any(BulkRequest.class));
        verify(indicators, never()).saveAll(any());
        assertTrue(result.getRiskTriggered()); assertEquals(RiskLevelEnum.HIGH_RISK, result.getRiskLevel());
        verify(reports).assembleGeneral(eq(assessment), argThat(risks -> risks.size() == 1 && risks.get(0).getRiskLevel() == RiskLevelEnum.HIGH_RISK));
    }
    @Test void lowScoreWithExplicitNoRiskDoesNotCreateRisk() throws Exception {
        result.setCalculatedScore(0.1); result.setRiskTriggered(false); result.setRiskLevel(RiskLevelEnum.NO_RISK);
        RuleEvaluationResult decision = result.getCalculationDetails().getRuleCalculation().getFinalDecision();
        decision.setCalculatedScore(0.1); decision.setRiskTriggered(false); decision.setRiskLevel(RiskLevelEnum.NO_RISK);
        service.aggregateInformation(3L, 1L, 2L, "run");
        verifyNoInteractions(es); verify(indicators, never()).saveAll(any());
        assertEquals(RiskLevelEnum.NO_RISK, assessment.getOverallRiskLevel());
    }
    @Test void mismatchedDecisionRejectsBeforeProjectionOrPublication() {
        result.setRiskTriggered(false);
        assertThrows(IllegalStateException.class, () -> service.aggregateInformation(3L, 1L, 2L, "run"));
        verifyNoInteractions(es, reports); verify(assessments, never()).save(any());
    }
    @Test void wrongScopeAndUnpreparedSummaryReject() {
        result.setAnalysisRunId("other");
        assertThrows(IllegalStateException.class, () -> ReasoningReportProjection.validate(run, Arrays.asList(result)));
        AnalysisRun unprepared = AnalysisRun.start(new AnalysisScope(1L, 2L, "run"), LocalDateTime.now(), AnalysisExecutionMode.REASONING);
        unprepared.freezeBehaviors(Arrays.asList("b"));
        assertThrows(IllegalStateException.class, () -> ReasoningReportProjection.validate(unprepared, Arrays.asList(result)));
    }
    @Test void otherModeCannotConsumeReasoningMarker() {
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.of(
                AnalysisRun.start(new AnalysisScope(1L, 2L, "run"), LocalDateTime.now(), null)));
        assertThrows(IllegalStateException.class, () -> service.aggregateInformation(3L, 1L, 2L, "run"));
        verifyNoInteractions(es, reports);
    }
    @Test void bulkFailureDoesNotSaveAssessment() throws Exception {
        when(es.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.errors(true).took(1).items(Collections.emptyList())));
        assertThrows(RuntimeException.class, () -> service.aggregateInformation(3L, 1L, 2L, "run"));
        verify(assessments, never()).save(any()); verifyNoInteractions(reports);
    }
    @Test void reportUsesFrozenFactAndOnlyExplicitlyReferencedRegulations() throws Exception {
        AnalysisTrace trace = result.getCalculationDetails().getTraces().get(0);
        RetrievalCandidateSnapshot unused = RetrievalCandidateSnapshot.builder().name("未引用法规").result(RetrievalResult.builder()
                .candidateType(RetrievalCandidateType.REGULATION).candidateId("unused").behaviorId("b").assessmentId(2L).analysisRunId("run").build()).build();
        trace.setCandidates(Arrays.asList(trace.getCandidates().get(0), unused));
        service.aggregateInformation(3L, 1L, 2L, "run");
        verify(reports).assembleGeneral(eq(assessment), argThat(risks -> {
            com.riskwarning.common.po.risk.Risk risk = risks.get(0);
            com.riskwarning.common.po.risk.RelatedBehavior behavior = risk.getRelatedIndicators().get(0).getRelatedBehaviors().get(0);
            return "冻结事实".equals(behavior.getDescription()) && risk.getDescription().contains("冻结判断")
                    && risk.getCreatedAt().equals(result.getCalculatedAt()) && behavior.getRelatedRegulations().size() == 1
                    && "law".equals(behavior.getRelatedRegulations().get(0).getRegulationId())
                    && "冻结法规".equals(behavior.getRelatedRegulations().get(0).getRegulationName());
        }));
    }
    @Test void missingOrTamperedRegulationRejectsBeforeEs() {
        AnalysisTrace trace = result.getCalculationDetails().getTraces().get(0);
        trace.getCandidates().get(0).setContent("变化原文");
        assertThrows(IllegalStateException.class, () -> service.aggregateInformation(3L, 1L, 2L, "run"));
        trace.setCandidates(Collections.emptyList());
        assertThrows(IllegalStateException.class, () -> service.aggregateInformation(3L, 1L, 2L, "run"));
        verifyNoInteractions(es, reports);
    }
    @Test void crossRunFactAndChangedBridgeReject() {
        AnalysisResult semantic = result.getCalculationDetails().getTraces().get(0).getAnalysisResult();
        semantic.setAnalysisRunId("other");
        assertThrows(IllegalStateException.class, () -> service.aggregateInformation(3L, 1L, 2L, "run"));
        semantic.setAnalysisRunId("run"); semantic.setConditionMet(false);
        assertThrows(IllegalStateException.class, () -> service.aggregateInformation(3L, 1L, 2L, "run"));
        verifyNoInteractions(es, reports);
    }
    @Test void noRegulationReferenceDoesNotInventAssociation() {
        result.getCalculationDetails().getTraces().get(0).getAnalysisResult().setRegulationIds(Collections.emptyList());
        assertTrue(FrozenReportEvidence.project(result).get(0).getRelatedBehaviors().get(0).getRelatedRegulations().isEmpty());
    }
    @Test void missingModeCannotEnterReportOrRecalculateRisk() throws Exception {
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.of(
                AnalysisRun.start(new AnalysisScope(1L, 2L, "run"), LocalDateTime.now(), null)));
        result.setCalculationDetails(IndicatorResultDetail.builder().schemaVersion("1.0").relatedIndicators(Collections.emptyList()).build());
        result.setCalculatedScore(0.1); result.setRiskTriggered(false); result.setUsedCalculationRuleType("BINARY");
        assertThrows(IllegalStateException.class, () -> service.aggregateInformation(3L, 1L, 2L, "run"));
        assertFalse(result.getRiskTriggered());
        verifyNoInteractions(es, reports);
        verify(indicators, never()).saveAll(any());
    }
}
