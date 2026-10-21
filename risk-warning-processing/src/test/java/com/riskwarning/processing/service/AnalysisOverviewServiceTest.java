package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.dto.scoring.ScoringDecision;
import com.riskwarning.common.dto.scoring.ScoringSourceBundle;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.processing.controller.AnalysisOverviewController;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisOverviewServiceTest {
    private final AssessmentRepository assessments = mock(AssessmentRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final AnalysisResultRepository results = mock(AnalysisResultRepository.class);
    private final RetrievalAuditRepository audits = mock(RetrievalAuditRepository.class);
    private final BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
    private final RuleEvaluationAuditRepository ruleAudits = mock(RuleEvaluationAuditRepository.class);
    private final ScoringRunSnapshotRepository snapshots = mock(ScoringRunSnapshotRepository.class);
    private final AnalysisRunSelector runSelector = new AnalysisRunSelector(runs);
    private final AnalysisOverviewService service = new AnalysisOverviewService(
            assessments, projects, runSelector, results, audits, behaviors, ruleAudits, snapshots);
    private final LocalDateTime start = LocalDateTime.of(2026, 9, 18, 10, 0);
    private AnalysisRun run;

    @BeforeEach
    void setup() {
        when(assessments.findById(86L)).thenReturn(Optional.of(Assessment.builder().id(86L).projectId(7L).assessmentDate(start).build()));
        when(projects.findById(7L)).thenReturn(Optional.empty());
        run = AnalysisRun.start(new AnalysisScope(7L, 86L, "latest"), start);
        when(runs.findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(86L, 7L)).thenReturn(Optional.of(run));
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.emptyList());
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.emptyList());
        when(behaviors.findByScope(7L, 86L, "latest")).thenReturn(Collections.emptyList());
        when(snapshots.findById("latest")).thenReturn(Optional.empty());
    }

    @Test void notStartedDoesNotReadArtifacts() {
        when(runs.findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(86L, 7L)).thenReturn(Optional.empty());
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals("NOT_STARTED", vo.getDisplayStatus()); assertNull(vo.getRun()); assertTrue(vo.getBehaviorGroups().isEmpty());
        verifyNoInteractions(results, audits, behaviors);
    }
    @Test void running() { assertEquals("RUNNING", service.overview(86L, 7L).getDisplayStatus()); }
    private void reasoningRun() {
        run = AnalysisRun.start(new AnalysisScope(7L, 86L, "latest"), start, com.riskwarning.common.enums.AnalysisExecutionMode.REASONING);
        run.freezeBehaviors(Collections.singletonList("b"));
        when(runs.findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(86L, 7L)).thenReturn(Optional.of(run));
    }
    @Test void reasoningSemanticResultDoesNotCountAsIndicatorDecision() {
        reasoningRun();
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                AnalysisResult.builder().id("result").schemaVersion("1.1").behaviorId("b").indicatorId("i")
                        .applicable(Applicability.APPLICABLE).complianceStatus(ComplianceStatus.COMPLIANT).build()));
        when(behaviors.findByScope(7L, 86L, "latest")).thenThrow(new RuntimeException("unavailable"));
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals(1, vo.getSummary().getBehaviorCount()); assertEquals(1, vo.getSummary().getConclusionCount());
        assertEquals(0, vo.getSummary().getDecisionCount()); assertNull(vo.getSummary().getCompletionKind());
        assertEquals(1, vo.getBehaviorGroups().size()); assertEquals("REASONING_RULES_V1", vo.getRun().getAnalysisMode());
    }
    @Test void preparedSummaryMustMatchPersistedDecisionCount() {
        reasoningRun();
        com.fasterxml.jackson.databind.node.ObjectNode summary = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        summary.put("behaviorCount", 1); summary.put("decisionCount", 1); summary.put("completionKind", "FULL");
        run.prepareReport(summary);
        assertThrows(IllegalStateException.class, () -> service.overview(86L, 7L));
    }
    @Test void noneSummarySurvivesMissingEsAndDoesNotBecomeNoRisk() {
        reasoningRun();
        AnalysisSummaryVO summary = new AnalysisSummaryVO(); summary.setBehaviorCount(1); summary.setCompletionKind(CompletionKind.NONE);
        run.prepareReport(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(summary)); run.completeWithoutDecision(start.plusMinutes(1));
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals(CompletionKind.NONE, vo.getSummary().getCompletionKind());
        assertEquals(0, vo.getSummary().getDecisionCount()); assertEquals("COMPLETED_WITHOUT_DECISION", vo.getDisplayStatus());
    }
    @Test void bridgeFieldsAreExposedWithoutInventingReasoningCoverage() {
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                AnalysisResult.builder().id("result").schemaVersion("1.1").behaviorId("b").indicatorId("i")
                        .applicable(Applicability.APPLICABLE).complianceStatus(ComplianceStatus.COMPLIANT)
                        .observedValue(0.0).observedUnit("day").modelVersion("model-v1").build()));
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        AnalysisConclusionVO c = vo.getBehaviorGroups().get(0).getConclusions().get(0);
        assertEquals("1.1", c.getSchemaVersion());
        assertEquals(0.0, c.getObservedValue());
        assertEquals("day", c.getObservedUnit());
        assertNull(c.getConditionMet());
        assertNull(vo.getSummary().getCompletionKind());
    }
    @Test void failedDoesNotBecomeNoCandidates() {
        run.fail(start.plusSeconds(1)); audit(RetrievalAuditStatus.NO_CANDIDATES, AnalysisAuditStatus.NOT_ATTEMPTED, Collections.emptyList());
        assertEquals("FAILED", service.overview(86L, 7L).getDisplayStatus());
    }
    @Test void semanticConclusionAloneDoesNotCreateIndicatorDecision() {
        run.succeed(start.plusSeconds(1)); conclusion(ComplianceStatus.NON_COMPLIANT);
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals("COMPLETED_WITH_DECISION", vo.getDisplayStatus()); assertEquals(0, vo.getSummary().getDecisionCount());
        assertFalse(vo.getBehaviorGroups().get(0).getConclusions().get(0).isMock());
    }
    @Test void insufficientEvidenceRetainedWithoutDecision() {
        run.completeWithoutDecision(start.plusSeconds(1)); conclusion(ComplianceStatus.INSUFFICIENT_EVIDENCE);
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals("COMPLETED_WITHOUT_DECISION", vo.getDisplayStatus()); assertEquals(0, vo.getSummary().getDecisionCount());
        assertEquals("INSUFFICIENT_EVIDENCE", vo.getBehaviorGroups().get(0).getConclusions().get(0).getComplianceStatus());
    }
    @Test void noCandidates() {
        run.completeWithoutDecision(start.plusSeconds(1)); audit(RetrievalAuditStatus.NO_CANDIDATES, AnalysisAuditStatus.NOT_ATTEMPTED, Collections.emptyList());
        assertEquals("NO_CANDIDATES", service.overview(86L, 7L).getDisplayStatus());
    }
    @Test void notDemoIsNotNoCandidates() {
        run.completeWithoutDecision(start.plusSeconds(1)); audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.NOT_DEMO_INPUT, Collections.emptyList());
        assertEquals("COMPLETED_WITHOUT_DECISION", service.overview(86L, 7L).getDisplayStatus());
    }
    @Test void mixedFailureCannotBeHiddenAsNoCandidates() {
        run.completeWithoutDecision(start.plusSeconds(1));
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Arrays.asList(
                RetrievalAudit.builder().behaviorId("empty").retrievalStatus(RetrievalAuditStatus.NO_CANDIDATES)
                        .candidates(Collections.emptyList()).build(),
                RetrievalAudit.builder().behaviorId("failed").retrievalStatus(RetrievalAuditStatus.FAILED).build()));
        assertNotEquals("NO_CANDIDATES", service.overview(86L, 7L).getDisplayStatus());
    }
    @Test void explicitRunNeverFallsBackToLatestAssessmentArtifacts() {
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("foreign", 86L, 7L)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> service.overview(86L, 7L, "foreign"));
        verifyNoInteractions(results, audits, behaviors);
    }
    @Test void behaviorWithNoAuditStillAppearsWithEvidenceReferences() {
        Behavior behavior = Behavior.builder().id("fact").subject("企业").action("留存")
                .object("审批意见").evidenceIds(Collections.singletonList("evidence")).confidence(0.95).build();
        when(behaviors.findByScope(7L, 86L, "latest")).thenReturn(Collections.singletonList(behavior));
        BehaviorAnalysisGroupVO group = service.overview(86L, 7L).getBehaviorGroups().get(0);
        assertEquals("企业", group.getSubject());
        assertEquals(Collections.singletonList("evidence"), group.getEvidenceIds());
        assertNull(group.getRetrievalAudit());
    }
    @Test void candidatePreservesRetrievalIdentityAndVersions() {
        Map<String, String> filters = Collections.singletonMap("enabled", "false");
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_ANALYSIS, Collections.singletonList(
                RetrievalCandidateSnapshot.builder().name("指标")
                        .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.INDICATOR)
                                .candidateId("indicator-1").behaviorId("b").assessmentId(86L)
                                .analysisRunId("latest").embeddingModel("bge-m3").embeddingVersion("v1")
                                .matchedFilters(filters).build()).build()));
        RetrievalCandidateVO candidate = service.overview(86L, 7L).getBehaviorGroups().get(0)
                .getRetrievalAudit().getCandidates().get(0);
        assertEquals("b", candidate.getBehaviorId());
        assertEquals(86L, candidate.getAssessmentId());
        assertEquals("latest", candidate.getAnalysisRunId());
        assertEquals("bge-m3", candidate.getEmbeddingModel());
        assertEquals("v1", candidate.getEmbeddingVersion());
        assertEquals(filters, candidate.getMatchedFilters());
    }
    @Test void nullSnapshotIsExplicit() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.NOT_ATTEMPTED, null);
        RetrievalAuditVO vo = service.overview(86L, 7L).getBehaviorGroups().get(0).getRetrievalAudit();
        assertFalse(vo.isSnapshotAvailable()); assertEquals(0, vo.getCandidateTotal()); assertTrue(vo.getCandidates().isEmpty());
    }
    @Test void auditOnlyBehaviorRemains() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.RECALL_GAP, Collections.emptyList());
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals(1, vo.getBehaviorGroups().size()); assertTrue(vo.getBehaviorGroups().get(0).getConclusions().isEmpty());
        assertEquals(1, vo.getSummary().getRecallGapCount());
    }
    @Test void indicatorWithoutDescriptionStillHasMetadataSnapshot() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.NOT_ATTEMPTED,
                Collections.singletonList(RetrievalCandidateSnapshot.builder().name("采购审批指标")
                        .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.INDICATOR)
                                .candidateId("indicator-1").build()).build()));
        RetrievalCandidateVO candidate = service.overview(86L, 7L).getBehaviorGroups().get(0)
                .getRetrievalAudit().getCandidates().get(0);
        assertTrue(candidate.isSnapshotAvailable());
        assertFalse(candidate.isContentAvailable());
    }
    @Test void contentAloneDoesNotMakeMissingCandidateMetadataAvailable() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.NOT_ATTEMPTED,
                Collections.singletonList(RetrievalCandidateSnapshot.builder().content("法规原文")
                        .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.REGULATION)
                                .candidateId("regulation-1").build()).build()));
        RetrievalCandidateVO candidate = service.overview(86L, 7L).getBehaviorGroups().get(0)
                .getRetrievalAudit().getCandidates().get(0);
        assertFalse(candidate.isSnapshotAvailable());
        assertTrue(candidate.isContentAvailable());
    }
    @Test void blankContentIsNotAnOriginalTextSource() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.NOT_ATTEMPTED,
                Collections.singletonList(RetrievalCandidateSnapshot.builder().name("采购指标").content("  ")
                        .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.INDICATOR)
                                .candidateId("indicator-1").build()).build()));
        RetrievalCandidateVO candidate = service.overview(86L, 7L).getBehaviorGroups().get(0)
                .getRetrievalAudit().getCandidates().get(0);
        assertTrue(candidate.isSnapshotAvailable());
        assertFalse(candidate.isContentAvailable());
    }
    @Test void truncationDoesNotLoseConclusionNames() {
        List<RetrievalCandidateSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 51; i++) snapshots.add(RetrievalCandidateSnapshot.builder().name("指标"+i).content("原文")
                .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.INDICATOR).candidateId("i"+i).rank(i+1).build()).build());
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.SUCCESS, snapshots); conclusion(ComplianceStatus.NON_COMPLIANT);
        AnalysisOverviewVO vo = service.overview(86L, 7L); BehaviorAnalysisGroupVO group = vo.getBehaviorGroups().get(0);
        assertTrue(vo.isTruncated()); assertTrue(group.getRetrievalAudit().isCandidateTruncated());
        assertEquals(51, group.getRetrievalAudit().getCandidateTotal()); assertEquals(50, group.getRetrievalAudit().getCandidates().size());
        assertEquals("指标50", group.getConclusions().get(0).getIndicatorName());
    }
    @Test void regulationNameFallsBackToRunSnapshot() {
        // 法规来自完整来源包但未进入检索候选时，名称仍应解析，不能把标识符展示给用户。
        reasoningRun();
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                AnalysisResult.builder().id("result").behaviorId("b").indicatorId("i50")
                        .applicable(Applicability.APPLICABLE).complianceStatus(ComplianceStatus.COMPLIANT)
                        .regulationIds(Collections.singletonList("reg-1")).modelVersion("model-v1").build()));
        when(snapshots.findById("latest")).thenReturn(Optional.of(snapshot("reg-1", "劳动合同法-经济性裁员")));

        AnalysisConclusionVO conclusion = service.overview(86L, 7L).getBehaviorGroups().get(0).getConclusions().get(0);

        assertEquals(Collections.singletonList("劳动合同法-经济性裁员"), conclusion.getRegulationNames());
    }

    @Test void retrievedCandidateNameTakesPrecedenceOverSnapshot() {
        reasoningRun();
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                AnalysisResult.builder().id("result").behaviorId("b").indicatorId("i50")
                        .applicable(Applicability.APPLICABLE).complianceStatus(ComplianceStatus.COMPLIANT)
                        .regulationIds(Collections.singletonList("reg-1")).modelVersion("model-v1").build()));
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.SUCCESS,
                Collections.singletonList(RetrievalCandidateSnapshot.builder().name("候选名称")
                        .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.REGULATION)
                                .candidateId("reg-1").build()).build()));
        when(snapshots.findById("latest")).thenReturn(Optional.of(snapshot("reg-1", "快照名称")));

        AnalysisConclusionVO conclusion = service.overview(86L, 7L).getBehaviorGroups().get(0).getConclusions().get(0);

        assertEquals(Collections.singletonList("候选名称"), conclusion.getRegulationNames());
    }

    @Test void regulationNameSnapshotFailureDoesNotBreakOverview() {
        reasoningRun();
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                AnalysisResult.builder().id("result").behaviorId("b").indicatorId("i50")
                        .applicable(Applicability.APPLICABLE).complianceStatus(ComplianceStatus.COMPLIANT)
                        .regulationIds(Collections.singletonList("reg-1")).modelVersion("model-v1").build()));
        when(snapshots.findById("latest")).thenThrow(new IllegalStateException("snapshot unavailable"));

        AnalysisConclusionVO conclusion = service.overview(86L, 7L).getBehaviorGroups().get(0).getConclusions().get(0);

        assertNull(conclusion.getRegulationNames().get(0));
    }

    private ScoringRunSnapshot snapshot(String regulationId, String name) {
        ScoringSourceBundle.Source source = new ScoringSourceBundle.Source();
        source.setRegulationId(regulationId);
        source.setRegulation(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("name", name));
        ScoringSourceBundle bundle = new ScoringSourceBundle();
        bundle.setRelations(Collections.singletonList(source));
        ScoringRunSnapshot snapshot = new ScoringRunSnapshot();
        snapshot.setAnalysisRunId("latest");
        snapshot.setSourceBundles(Collections.singletonMap("i50", bundle));
        return snapshot;
    }

    @Test void successCandidatesPreventNoCandidateOverride() {
        run.completeWithoutDecision(start.plusSeconds(1));
        RetrievalAudit empty = RetrievalAudit.builder().behaviorId("empty").retrievalStatus(RetrievalAuditStatus.NO_CANDIDATES).candidates(Collections.emptyList()).build();
        RetrievalAudit full = RetrievalAudit.builder().behaviorId("full").retrievalStatus(RetrievalAuditStatus.SUCCESS)
                .candidates(Collections.singletonList(new RetrievalCandidateSnapshot())).build();
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Arrays.asList(empty, full));
        assertEquals("COMPLETED_WITHOUT_DECISION", service.overview(86L, 7L).getDisplayStatus());
    }
    @Test void missingSuccessSnapshotPreventsNoCandidateOverride() {
        run.completeWithoutDecision(start.plusSeconds(1));
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Arrays.asList(
                RetrievalAudit.builder().behaviorId("empty").retrievalStatus(RetrievalAuditStatus.NO_CANDIDATES).candidates(Collections.emptyList()).build(),
                RetrievalAudit.builder().behaviorId("missing").retrievalStatus(RetrievalAuditStatus.SUCCESS).build()));
        assertEquals("COMPLETED_WITHOUT_DECISION", service.overview(86L, 7L).getDisplayStatus());
    }
    @Test void esFailureKeepsArtifacts() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.SUCCESS, Collections.emptyList());
        when(behaviors.findByScope(7L, 86L, "latest")).thenThrow(new IllegalStateException("ES unavailable"));
        assertEquals(1, service.overview(86L, 7L).getBehaviorGroups().size());
    }
    @Test void behaviorDescriptionAndTime() {
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.SUCCESS, Collections.emptyList());
        Behavior b = new Behavior(); b.setId("b"); b.setSubject("企业"); b.setAction("检验"); b.setObject("设备");
        when(behaviors.findByScope(7L, 86L, "latest")).thenReturn(Collections.singletonList(b));
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals("企业 检验 设备", vo.getBehaviorGroups().get(0).getBehaviorDescription());
        assertEquals("2026-09-18 10:00:00", vo.getRun().getStartedAt());
    }
    @Test void failedNewerRunDoesNotHideTheOlderSuccessfulRun() {
        AnalysisRun older = AnalysisRun.start(new AnalysisScope(7L, 86L, "good-run"), start);
        older.completeWithoutDecision(start.plusSeconds(1));
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("good-run", 86L, 7L)).thenReturn(Optional.of(older));
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "good-run")).thenReturn(Collections.singletonList(
                RetrievalAudit.builder().behaviorId("b").retrievalStatus(RetrievalAuditStatus.SUCCESS)
                        .analysisStatus(AnalysisAuditStatus.WAITING_ANALYSIS)
                        .embeddingModel("BAAI/bge-m3").embeddingVersion("v1")
                        .candidates(Collections.singletonList(RetrievalCandidateSnapshot.builder().name("采购指标")
                                .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.INDICATOR)
                                        .candidateId("indicator-1").behaviorId("b").assessmentId(86L)
                                        .analysisRunId("good-run").embeddingModel("BAAI/bge-m3").embeddingVersion("v1")
                                        .rank(1).score(0.8).scoreType(ScoreType.COSINE_SIMILARITY).build()).build()))
                        .build()));
        when(behaviors.findByScope(7L, 86L, "good-run")).thenReturn(Collections.singletonList(
                Behavior.builder().id("b").subject("企业").action("留存").object("审批意见")
                        .evidenceIds(Collections.singletonList("evidence")).build()));

        run.fail(start.plusSeconds(2));
        audit(RetrievalAuditStatus.FAILED, AnalysisAuditStatus.NOT_ATTEMPTED, Collections.emptyList());

        AnalysisOverviewVO olderView = service.overview(86L, 7L, "good-run");
        assertEquals("COMPLETED_WITHOUT_DECISION", olderView.getDisplayStatus());
        assertEquals(1, olderView.getSummary().getWaitingForAnalysisCount());
        assertEquals(0, olderView.getSummary().getRetrievalFailedCount());
        assertEquals("b", olderView.getBehaviorGroups().get(0).getBehaviorId());
        assertEquals(1, olderView.getBehaviorGroups().get(0).getRetrievalAudit().getCandidates().size());

        when(runs.findFirstByAssessmentIdAndProjectIdAndStatusInOrderByStartedAtDescAnalysisRunIdDesc(
                eq(86L), eq(7L), anyCollection())).thenReturn(Optional.of(older));
        AnalysisOverviewVO defaultView = service.overview(86L, 7L);
        assertEquals("good-run", defaultView.getRun().getAnalysisRunId());
        assertTrue(defaultView.getRun().isFallbackToUsableRun());
        assertEquals("COMPLETED_WITHOUT_DECISION", defaultView.getDisplayStatus());
        assertEquals(1, defaultView.getSummary().getWaitingForAnalysisCount());
        assertEquals(0, defaultView.getSummary().getRetrievalFailedCount());

        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("latest", 86L, 7L)).thenReturn(Optional.of(run));
        AnalysisOverviewVO explicitFailed = service.overview(86L, 7L, "latest");
        assertEquals("latest", explicitFailed.getRun().getAnalysisRunId());
        assertFalse(explicitFailed.getRun().isFallbackToUsableRun());
        assertEquals("FAILED", explicitFailed.getDisplayStatus());
    }

    @Test void onlyFailedRunFallsBackToLatestItself() {
        run.fail(start.plusSeconds(1));
        when(runs.findFirstByAssessmentIdAndProjectIdAndStatusInOrderByStartedAtDescAnalysisRunIdDesc(
                eq(86L), eq(7L), anyCollection())).thenReturn(Optional.empty());
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals("latest", vo.getRun().getAnalysisRunId());
        assertEquals("FAILED", vo.getDisplayStatus());
        assertFalse(vo.getRun().isFallbackToUsableRun());
    }
    @Test void runSummaryAlwaysUsesComplianceAnalysisMode() {
        run.succeed(start.plusSeconds(1));
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_ANALYSIS, Collections.emptyList());
        assertEquals("REASONING_RULES_V1", service.overview(86L, 7L).getRun().getAnalysisMode());

        conclusion(ComplianceStatus.NON_COMPLIANT);
        assertEquals("REASONING_RULES_V1", service.overview(86L, 7L).getRun().getAnalysisMode());
    }
    @Test void v2PreparedSummaryAndScoringDecisionAreAccepted() {
        // 1.2 摘要带分析模式与版本字段，审计只写 scoringDecision，概览必须能读而不报错。
        reasoningRun();
        when(ruleAudits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                RuleEvaluationAudit.builder().projectId(7L).assessmentId(86L).analysisRunId("latest")
                        .indicatorId("i50").inputHash("hash-1").status(RuleEvaluationStatus.DECIDED).conflict(false)
                        .calculationDetails(IndicatorResultDetail.builder().schemaVersion("1.2")
                                .analysisMode("REASONING_RULES_V2").scoringDecision(scoringDecision("i50", "hash-1")).build())
                        .build()));
        com.fasterxml.jackson.databind.node.ObjectNode summary =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        summary.put("schemaVersion", "1.2"); summary.put("analysisMode", "REASONING_RULES_V2");
        summary.put("behaviorCount", 1); summary.put("decisionCount", 1); summary.put("completionKind", "FULL");
        summary.put("registryVersion", "labor-reviewed-test-2026.10-v1"); summary.put("datasetVersion", "dataset-1");
        run.prepareReport(summary);
        run.succeed(start.plusSeconds(1));

        AnalysisOverviewVO vo = service.overview(86L, 7L);

        assertEquals("REASONING_RULES_V2", vo.getRun().getAnalysisMode());
        assertEquals(1, vo.getSummary().getDecisionCount());
    }

    @Test void v2AuditWithMismatchedDecisionIdentityIsRejected() {
        reasoningRun();
        when(ruleAudits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                RuleEvaluationAudit.builder().projectId(7L).assessmentId(86L).analysisRunId("latest")
                        .indicatorId("i50").inputHash("hash-1").status(RuleEvaluationStatus.DECIDED)
                        .calculationDetails(IndicatorResultDetail.builder().schemaVersion("1.2")
                                .scoringDecision(scoringDecision("other", "hash-1")).build())
                        .build()));

        assertThrows(IllegalStateException.class, () -> service.overview(86L, 7L));
    }

    @Test void controllerErrors() {
        AnalysisOverviewController controller = new AnalysisOverviewController(service);
        assertThrows(IllegalArgumentException.class, () -> controller.overview(86L, null));
        assertThrows(SecurityException.class, () -> controller.overview(86L, 8L));
        when(assessments.findById(99L)).thenReturn(Optional.empty());
        assertThrows(java.util.NoSuchElementException.class, () -> controller.overview(99L, 7L));
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class, () -> controller.overview(86L, 7L));
    }
    private void audit(RetrievalAuditStatus retrieval, AnalysisAuditStatus analysis, List<RetrievalCandidateSnapshot> candidates) {
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                RetrievalAudit.builder().behaviorId("b").retrievalStatus(retrieval).analysisStatus(analysis).candidates(candidates).build()));
    }
    private ScoringDecision scoringDecision(String indicatorId, String inputHash) {
        ScoringDecision decision = new ScoringDecision();
        decision.setIndicatorId(indicatorId); decision.setInputHash(inputHash);
        decision.setCalculatedScore(java.math.BigDecimal.TEN);
        return decision;
    }
    private void conclusion(ComplianceStatus status) {
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                AnalysisResult.builder().id("result").behaviorId("b").indicatorId("i50").applicable(Applicability.APPLICABLE)
                        .complianceStatus(status).modelVersion("model-v1").build()));
    }
}
