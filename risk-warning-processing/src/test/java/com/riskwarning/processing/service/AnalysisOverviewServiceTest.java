package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
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
    private final AnalysisOverviewService service = new AnalysisOverviewService(assessments, projects, runs, results, audits, behaviors);
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
    }

    @Test void notStartedDoesNotReadArtifacts() {
        when(runs.findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(86L, 7L)).thenReturn(Optional.empty());
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals("NOT_STARTED", vo.getDisplayStatus()); assertNull(vo.getRun()); assertTrue(vo.getBehaviorGroups().isEmpty());
        verifyNoInteractions(results, audits, behaviors);
    }
    @Test void running() { assertEquals("RUNNING", service.overview(86L, 7L).getDisplayStatus()); }
    @Test void failedDoesNotBecomeNoCandidates() {
        run.fail(start.plusSeconds(1)); audit(RetrievalAuditStatus.NO_CANDIDATES, AnalysisAuditStatus.NOT_ATTEMPTED, Collections.emptyList());
        assertEquals("FAILED", service.overview(86L, 7L).getDisplayStatus());
    }
    @Test void succeededCountsDecision() {
        run.succeed(start.plusSeconds(1)); conclusion(ComplianceStatus.NON_COMPLIANT);
        AnalysisOverviewVO vo = service.overview(86L, 7L);
        assertEquals("COMPLETED_WITH_DECISION", vo.getDisplayStatus()); assertEquals(1, vo.getSummary().getDecisionCount());
        assertTrue(vo.getBehaviorGroups().get(0).getConclusions().get(0).isMock());
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
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_P3, Collections.singletonList(
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
                        .analysisStatus(AnalysisAuditStatus.WAITING_P3)
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

        AnalysisOverviewVO latestView = service.overview(86L, 7L);
        assertEquals("FAILED", latestView.getDisplayStatus());
        assertEquals(1, latestView.getSummary().getRetrievalFailedCount());
        assertEquals(0, latestView.getSummary().getWaitingForAnalysisCount());
    }
    @Test void runSummaryDistinguishesRealRetrievalFromMockConclusions() {
        run.succeed(start.plusSeconds(1));
        audit(RetrievalAuditStatus.SUCCESS, AnalysisAuditStatus.WAITING_P3, Collections.emptyList());
        assertEquals("P2_RETRIEVAL", service.overview(86L, 7L).getRun().getAnalysisMode());

        conclusion(ComplianceStatus.NON_COMPLIANT);
        assertEquals("LEGACY_MOCK_DEMO", service.overview(86L, 7L).getRun().getAnalysisMode());
    }
    @Test void controllerErrors() {
        AnalysisOverviewController controller = new AnalysisOverviewController(service);
        assertEquals(400, controller.overview(86L, null).getCode());
        assertEquals(403, controller.overview(86L, 8L).getCode());
        when(assessments.findById(99L)).thenReturn(Optional.empty());
        assertEquals(404, controller.overview(99L, 7L).getCode());
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenThrow(new IllegalStateException("DB unavailable"));
        assertEquals(500, controller.overview(86L, 7L).getCode());
    }
    private void audit(RetrievalAuditStatus retrieval, AnalysisAuditStatus analysis, List<RetrievalCandidateSnapshot> candidates) {
        when(audits.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                RetrievalAudit.builder().behaviorId("b").retrievalStatus(retrieval).analysisStatus(analysis).candidates(candidates).build()));
    }
    private void conclusion(ComplianceStatus status) {
        when(results.findByAssessmentIdAndAnalysisRunId(86L, "latest")).thenReturn(Collections.singletonList(
                AnalysisResult.builder().id("result").behaviorId("b").indicatorId("i50").applicable(Applicability.APPLICABLE)
                        .complianceStatus(status).modelVersion("mock-fixture-v1").build()));
    }
}
