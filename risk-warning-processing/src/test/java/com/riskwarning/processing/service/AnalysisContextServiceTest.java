package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.AnalysisTrace;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.processing.controller.AnalysisContextController;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisContextServiceTest {
    private final AssessmentRepository assessments = mock(AssessmentRepository.class);
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final RetrievalAuditRepository audits = mock(RetrievalAuditRepository.class);
    private final BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
    private final AnalysisResultRepository results = mock(AnalysisResultRepository.class);
    private final IndicatorResultRepository indicatorResults = mock(IndicatorResultRepository.class);
    private final AnalysisContextService service = new AnalysisContextService(
            assessments, runs, audits, behaviors, results, indicatorResults);
    private final LocalDateTime start = LocalDateTime.of(2026, 9, 18, 10, 0);
    private AnalysisRun run;
    private Behavior behavior;

    @BeforeEach
    void setup() {
        when(assessments.findById(86L)).thenReturn(Optional.of(
                Assessment.builder().id(86L).projectId(7L).assessmentDate(start).build()));
        run = AnalysisRun.start(new AnalysisScope(7L, 86L, "run-1"), start);
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 86L, 7L)).thenReturn(Optional.of(run));
        when(runs.findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(86L, 7L))
                .thenReturn(Optional.of(run));
        behavior = Behavior.builder().id("b").projectId(7L).assessmentId(86L).analysisRunId("run-1")
                .sourceDocumentId(71L).description("公司留存采购审批意见").subject("公司").action("留存")
                .object("审批意见").evidenceIds(Collections.singletonList("evidence-1")).build();
        when(behaviors.findByScope(7L, 86L, "run-1")).thenReturn(Collections.singletonList(behavior));
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "run-1")).thenReturn(Collections.emptyList());
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "run-1")).thenReturn(Collections.emptyList());
        when(indicatorResults.findByAssessmentIdAndAnalysisRunId(86L, "run-1")).thenReturn(Collections.emptyList());
    }

    @Test void rejectsForeignScopeBeforeReadingArtifacts() {
        assertThrows(IllegalArgumentException.class, () -> service.context(86L, null, "run-1"));
        assertThrows(SecurityException.class, () -> service.context(86L, 8L, "run-1"));
        assertThrows(NoSuchElementException.class, () -> service.context(86L, 7L, "other-run"));
        verifyNoInteractions(audits, behaviors, results, indicatorResults);
    }

    @Test void runWithoutArtifactsReportsNotStartedWithoutInventingReasons() {
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 86L, 7L)).thenReturn(Optional.empty());
        when(runs.findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(86L, 7L))
                .thenReturn(Optional.empty());
        AnalysisContextVO context = service.context(86L, 7L, null);
        assertEquals("NOT_STARTED", context.getRunStatus());
        assertEquals(AnalysisModeResolver.NO_ARTIFACTS, context.getAnalysisMode());
        assertEquals(0, context.getBehaviorCount());
        assertTrue(context.getItems().isEmpty());
    }

    @Test void successfulRetrievalWaitsForAnalysisWithBothCandidateTypes() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_P3,
                Arrays.asList(candidate(RetrievalCandidateType.INDICATOR, "indicator-1", "采购审批指标", 1),
                        candidate(RetrievalCandidateType.REGULATION, "regulation-1", "采购管理办法", 1)));

        AnalysisContextVO context = service.context(86L, 7L, "run-1");
        assertEquals(AnalysisContextService.CONTEXT_VERSION, context.getContextVersion());
        assertEquals("run-1", context.getAnalysisRunId());
        assertEquals(AnalysisModeResolver.P2_RETRIEVAL, context.getAnalysisMode());
        assertEquals(1, context.getBehaviorCount());
        assertEquals(1, context.getReadyForAnalysisCount());
        assertEquals(0, context.getBlockedCount());
        assertTrue(context.getBlockedReasons().isEmpty());
        BehaviorAnalysisContextVO item = context.getItems().get(0);
        assertEquals("b", item.getBehaviorId());
        assertEquals(Collections.singletonList("evidence-1"), item.getEvidenceIds());
        assertTrue(item.isEvidenceComplete());
        assertTrue(item.isSnapshotAvailable());
        assertTrue(item.isReadyForAnalysis());
        assertNull(item.getBlockedReason());
        assertEquals("留存", item.getAction());
        assertEquals("BAAI/bge-m3", item.getEmbeddingModel());
        assertEquals("v1", item.getRetrievalVersion());
        assertEquals(1, item.getIndicatorCandidates().size());
        assertEquals(1, item.getRegulationCandidates().size());
        assertEquals("indicator-1", item.getIndicatorCandidates().get(0).getCandidateId());
        assertEquals("regulation-1", item.getRegulationCandidates().get(0).getCandidateId());
        assertNull(item.getCalculationDetails());
    }

    @Test void degradedRetrievalNeverBecomesAnalyzableInput() {
        assertBlocked("RETRIEVAL_FAILED", RetrievalAuditStatus.FAILED, AnalysisAuditStatus.NOT_ATTEMPTED);
        assertBlocked("NO_CANDIDATES", RetrievalAuditStatus.NO_CANDIDATES, AnalysisAuditStatus.NOT_ATTEMPTED);
        assertBlocked("SNAPSHOT_UNAVAILABLE", RetrievalAuditStatus.SNAPSHOT_UNAVAILABLE, AnalysisAuditStatus.NOT_ATTEMPTED);
        assertBlocked("INSUFFICIENT_EVIDENCE", RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.INSUFFICIENT_EVIDENCE);
        assertBlocked("NOT_P2_RUN", RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.NOT_DEMO_INPUT);
        assertBlocked("NOT_ATTEMPTED", RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.NOT_ATTEMPTED);
    }

    @Test void missingAuditOrEvidenceBlocksAnalysisWithExplicitReason() {
        AnalysisContextVO withoutAudit = service.context(86L, 7L, "run-1");
        assertEquals("NO_RETRIEVAL_AUDIT", withoutAudit.getItems().get(0).getBlockedReason());
        assertEquals(1, withoutAudit.getBlockedReasons().get("NO_RETRIEVAL_AUDIT"));

        behavior.setEvidenceIds(Collections.emptyList());
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_P3,
                Collections.singletonList(candidate(RetrievalCandidateType.INDICATOR, "indicator-1", "采购审批指标", 1)));
        AnalysisContextVO withoutEvidence = service.context(86L, 7L, "run-1");
        assertEquals("INSUFFICIENT_EVIDENCE", withoutEvidence.getItems().get(0).getBlockedReason());
        assertFalse(withoutEvidence.getItems().get(0).isEvidenceComplete());
        assertEquals(0, withoutEvidence.getReadyForAnalysisCount());
    }

    @Test void nonRecoverableCandidateSnapshotBlocksAnalysis() {
        RetrievalCandidateSnapshot snapshot = candidate(RetrievalCandidateType.INDICATOR, "indicator-1", null, 1);
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_P3, Collections.singletonList(snapshot));
        BehaviorAnalysisContextVO item = service.context(86L, 7L, "run-1").getItems().get(0);
        assertEquals("SNAPSHOT_UNAVAILABLE", item.getBlockedReason());
        assertFalse(item.isReadyForAnalysis());
    }

    @Test void existingDecisionBlocksReprocessing() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_P3,
                Collections.singletonList(candidate(RetrievalCandidateType.INDICATOR, "indicator-1", "采购审批指标", 1)));
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "run-1")).thenReturn(Collections.singletonList(
                AnalysisResult.builder().id("result").behaviorId("b").indicatorId("indicator-1")
                        .complianceStatus(ComplianceStatus.NON_COMPLIANT).modelVersion("llm-v1").build()));

        AnalysisContextVO context = service.context(86L, 7L, "run-1");
        assertEquals("DECISION_PRESENT", context.getItems().get(0).getBlockedReason());
        assertEquals(AnalysisModeResolver.DECISION_PRESENT, context.getAnalysisMode());
        assertEquals(0, context.getReadyForAnalysisCount());
    }

    @Test void legacyRunKeepsCalculationDetailsReadableForTheSameBehavior() {
        AnalysisTrace trace = AnalysisTrace.builder().behaviorId("b").queryText("旧链查询")
                .candidates(Collections.singletonList(candidate(RetrievalCandidateType.INDICATOR, "indicator-1", "旧指标", 1)))
                .build();
        when(indicatorResults.findByAssessmentIdAndAnalysisRunId(86L, "run-1")).thenReturn(Collections.singletonList(
                IndicatorResult.builder().assessmentId(86L).projectId(7L).analysisRunId("run-1")
                        .indicatorEsId("indicator-1")
                        .calculationDetails(IndicatorResultDetail.builder().traces(Collections.singletonList(trace)).build())
                        .build()));

        BehaviorAnalysisContextVO item = service.context(86L, 7L, "run-1").getItems().get(0);

        assertEquals(trace, item.getCalculationDetails());
        assertEquals("NO_RETRIEVAL_AUDIT", item.getBlockedReason());
    }

    @Test void contextKeyNamesAreFrozen() throws Exception {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_P3,
                Collections.singletonList(candidate(RetrievalCandidateType.INDICATOR, "indicator-1", "采购审批指标", 1)));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode root = mapper.readTree(mapper.writeValueAsString(service.context(86L, 7L, "run-1")));

        assertEquals(new TreeSet<>(Arrays.asList("contextVersion", "assessmentId", "projectId", "analysisRunId",
                        "runStatus", "analysisMode", "generatedAt", "behaviorCount", "readyForAnalysisCount",
                        "blockedCount", "blockedReasons", "items")),
                new TreeSet<>(fieldNames(root)));
        assertEquals(new TreeSet<>(Arrays.asList("behaviorId", "description", "subject", "action", "object", "type",
                        "dimension", "quantitativeData", "quantitativeUnit", "confidence", "sourceDocumentId",
                        "evidenceIds", "queryText", "queryTemplateVersion", "filterVersion", "filterEnabled",
                        "filterApplied", "filterExpression", "retrievalStatus", "analysisStatus", "embeddingModel",
                        "retrievalVersion", "errorCode", "errorMessage", "snapshotAvailable", "evidenceComplete",
                        "readyForAnalysis", "blockedReason", "indicatorCandidates", "regulationCandidates",
                        "calculationDetails")),
                new TreeSet<>(fieldNames(root.get("items").get(0))));
    }

    @Test void controllerMapsScopeAndFailureCodes() {
        AnalysisContextController controller = new AnalysisContextController(service);
        assertEquals(400, controller.context(86L, null, "run-1").getCode());
        assertEquals(403, controller.context(86L, 8L, "run-1").getCode());
        assertEquals(404, controller.context(86L, 7L, "other-run").getCode());
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "run-1")).thenThrow(new IllegalStateException("DB down"));
        assertEquals(500, controller.context(86L, 7L, "run-1").getCode());
    }

    private void assertBlocked(String reason, RetrievalAuditStatus retrieval, AnalysisAuditStatus analysis) {
        audit(retrieval, analysis, Collections.singletonList(
                candidate(RetrievalCandidateType.INDICATOR, "indicator-1", "采购审批指标", 1)));
        AnalysisContextVO context = service.context(86L, 7L, "run-1");
        BehaviorAnalysisContextVO item = context.getItems().get(0);
        assertEquals(reason, item.getBlockedReason());
        assertFalse(item.isReadyForAnalysis());
        assertEquals(0, context.getReadyForAnalysisCount());
        assertEquals(1, context.getBlockedReasons().get(reason));
    }

    private void audit(RetrievalAuditStatus retrieval, AnalysisAuditStatus analysis,
                       List<RetrievalCandidateSnapshot> candidates) {
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "run-1")).thenReturn(Collections.singletonList(
                RetrievalAudit.builder().projectId(7L).assessmentId(86L).analysisRunId("run-1").behaviorId("b")
                        .queryText("公司留存采购审批意见 留存 审批意见").queryTemplateVersion("query-v1")
                        .filterVersion("filter-v1").filterEnabled(false).filterApplied(false).filterExpression("[]")
                        .embeddingModel("BAAI/bge-m3").embeddingVersion("v1")
                        .retrievalStatus(retrieval).analysisStatus(analysis).candidates(candidates).build()));
    }

    private RetrievalCandidateSnapshot candidate(RetrievalCandidateType type, String id, String name, int rank) {
        return RetrievalCandidateSnapshot.builder().name(name).retrievalTextHash("hash")
                .result(RetrievalResult.builder().candidateType(type).candidateId(id).rank(rank).score(0.8)
                        .scoreType(ScoreType.COSINE_SIMILARITY).embeddingModel("BAAI/bge-m3").embeddingVersion("v1")
                        .behaviorId("b").assessmentId(86L).analysisRunId("run-1").build()).build();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
