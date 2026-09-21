package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.processing.client.RetrievalClient;
import com.riskwarning.processing.config.P2ProcessingProperties;
import com.riskwarning.processing.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalProcessingServiceTest {
    private final BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
    private final EvidenceQueryService evidence = mock(EvidenceQueryService.class);
    private final RetrievalClient retrieval = mock(RetrievalClient.class);
    private final RetrievalAuditService audits = mock(RetrievalAuditService.class);
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final P2CompletionService completion = mock(P2CompletionService.class);
    private final P2ProcessingProperties properties = new P2ProcessingProperties();
    private final AnalysisScope scope = new AnalysisScope(7L, 90L, "real-run");
    private final IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
            "message", "time", "trace", 5L, 7L, 90L, "real-run");
    private final P2RetrievalProcessingService service = new P2RetrievalProcessingService(
            behaviors, evidence, retrieval, audits, runs, completion,
            new BehaviorQueryTextBuilder(), properties, mock(AssessmentFlowLogger.class));
    private AnalysisRun run;
    private Behavior behavior;

    @BeforeEach void setup() {
        run = AnalysisRun.start(scope, LocalDateTime.now());
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("real-run", 90L, 7L)).thenReturn(Optional.of(run));
        EvidenceChunk chunk = EvidenceChunk.create(71L, 7L, 90L, "真实材料.pdf", 1, 0,
                null, null, "公司留存采购审批意见", LocalDateTime.now());
        behavior = Behavior.builder().id("b").projectId(7L).assessmentId(90L).analysisRunId("real-run")
                .sourceDocumentId(71L).description("公司留存采购审批意见").action("留存").object("审批意见")
                .evidenceIds(Collections.singletonList(chunk.getId())).build();
        when(behaviors.findByScope(7L, 90L, "real-run")).thenReturn(Collections.singletonList(behavior));
        when(evidence.listByIds(anyList(), eq(7L), eq(90L))).thenReturn(Collections.singletonList(chunk));
    }

    @Test void realInputSavesCandidatesAndWaitsForP3WithoutSourceFileOrFixture() {
        response("b", RetrievalBatchStatus.SUCCESS, Collections.singletonList(candidate("b")));
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getRetrievalStatus() == RetrievalAuditStatus.SUCCESS
                && a.getAnalysisStatus() == AnalysisAuditStatus.WAITING_P3 && a.getCandidates().size() == 1
                && !a.getFilterEnabled() && !a.getFilterApplied()));
        verify(completion).complete(message, scope);
        verify(retrieval).retrieve(argThat(request -> request.getRequests().get(0).getQueryText()
                .equals("公司留存采购审批意见 留存 审批意见")));
    }

    @Test void disabledQualityGateKeepsWaitingForP3() {
        response("b", RetrievalBatchStatus.SUCCESS, Collections.singletonList(candidate("b")));
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getAnalysisStatus() == AnalysisAuditStatus.WAITING_P3));
    }

    @Test void enabledQualityGateMarksRecallGapWhenBothTypesAreBelowThreshold() {
        List<RetrievalCandidateSnapshot> candidates = Arrays.asList(
                candidate("b", RetrievalCandidateType.INDICATOR, "indicator-1", 0.4, 1),
                candidate("b", RetrievalCandidateType.REGULATION, "regulation-1", 0.5, 1));
        gatedResponse(candidates, 0.7, 0.8);
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getRetrievalStatus() == RetrievalAuditStatus.SUCCESS
                && a.getAnalysisStatus() == AnalysisAuditStatus.RECALL_GAP
                && a.getCandidates().size() == 2));
    }

    @Test void enabledQualityGateWaitsWhenEitherCandidateTypeReachesItsThreshold() {
        List<RetrievalCandidateSnapshot> candidates = Arrays.asList(
                candidate("b", RetrievalCandidateType.INDICATOR, "indicator-1", 0.71, 1),
                candidate("b", RetrievalCandidateType.REGULATION, "regulation-1", 0.5, 1));
        gatedResponse(candidates, 0.7, 0.8);
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getAnalysisStatus() == AnalysisAuditStatus.WAITING_P3
                && a.getCandidates().size() == 2
                && a.getCandidates().stream().anyMatch(c -> "regulation-1".equals(c.getResult().getCandidateId()))));
    }

    @Test void completedRunReplayDoesNotOverwriteSnapshots() {
        run.completeWithoutDecision(LocalDateTime.now());
        service.process(message, scope);
        verifyNoInteractions(behaviors, evidence, retrieval, audits, completion);
    }

    @Test void noCandidatesIsNotAZeroRiskConclusion() {
        response("b", RetrievalBatchStatus.NO_CANDIDATES, Collections.emptyList());
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getRetrievalStatus() == RetrievalAuditStatus.NO_CANDIDATES
                && a.getAnalysisStatus() == AnalysisAuditStatus.NOT_ATTEMPTED
                && "BAAI/bge-m3".equals(a.getEmbeddingModel()) && "v1".equals(a.getEmbeddingVersion())));
        verify(completion).complete(message, scope);
    }

    @Test void missingEvidenceStillHasExplicitDegradedContext() {
        when(evidence.listByIds(anyList(), eq(7L), eq(90L))).thenReturn(Collections.emptyList());
        response("b", RetrievalBatchStatus.SUCCESS, Collections.singletonList(candidate("b")));
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getAnalysisStatus() == AnalysisAuditStatus.INSUFFICIENT_EVIDENCE));
    }

    @Test void missingMetadataIsSnapshotUnavailableAndCannotWaitForNormalAnalysis() {
        RetrievalCandidateSnapshot candidate = candidate("b"); candidate.setName(null);
        response("b", RetrievalBatchStatus.SUCCESS, Collections.singletonList(candidate));
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getRetrievalStatus() == RetrievalAuditStatus.SNAPSHOT_UNAVAILABLE
                && a.getAnalysisStatus() == AnalysisAuditStatus.NOT_ATTEMPTED));
    }

    @Test void candidateFromOtherRunIsRejectedAndFailureDoesNotLeakProviderSecrets() {
        RetrievalCandidateSnapshot candidate = candidate("b"); candidate.getResult().setAnalysisRunId("another-run");
        response("b", RetrievalBatchStatus.SUCCESS, Collections.singletonList(candidate));
        assertThrows(IllegalStateException.class, () -> service.process(message, scope));
        verify(audits).save(argThat(a -> a.getRetrievalStatus() == RetrievalAuditStatus.FAILED));
        verifyNoInteractions(completion);
    }

    @Test void unavailableSnapshotKeepsScoredCandidateAndNeverBecomesNoCandidates() {
        RetrievalCandidateSnapshot candidate = candidate("b"); candidate.setName(null);
        response("b", RetrievalBatchStatus.SNAPSHOT_UNAVAILABLE, Collections.singletonList(candidate));
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getRetrievalStatus() == RetrievalAuditStatus.SNAPSHOT_UNAVAILABLE
                && a.getCandidates().get(0).getResult().getCandidateId().equals("indicator-1")
                && a.getAnalysisStatus() == AnalysisAuditStatus.NOT_ATTEMPTED));
    }

    @Test void mixedEmbeddingVersionsAreRejectedBeforeContextCanBeUsed() {
        RetrievalCandidateSnapshot candidate = candidate("b"); candidate.getResult().setEmbeddingVersion("old-version");
        response("b", RetrievalBatchStatus.SUCCESS, Collections.singletonList(candidate));
        assertThrows(IllegalStateException.class, () -> service.process(message, scope));
        verify(audits).save(argThat(a -> a.getRetrievalStatus() == RetrievalAuditStatus.FAILED));
        verifyNoInteractions(completion);
    }

    @Test void laterBatchFailureDoesNotRewriteEarlierSuccessfulAudit() {
        properties.setBatchSize(1);
        Behavior second = Behavior.builder().id("c").projectId(7L).assessmentId(90L).analysisRunId("real-run")
                .sourceDocumentId(71L).description("第二条事实").evidenceIds(behavior.getEvidenceIds()).build();
        when(behaviors.findByScope(7L, 90L, "real-run")).thenReturn(Arrays.asList(behavior, second));
        when(retrieval.retrieve(any())).thenReturn(RetrievalBatchResponse.builder().items(Collections.singletonList(
                RetrievalBatchItem.builder().behaviorId("b").status(RetrievalBatchStatus.SUCCESS)
                        .embeddingModel("BAAI/bge-m3").embeddingVersion("v1")
                        .candidates(Collections.singletonList(candidate("b"))).build())).build())
                .thenThrow(new IllegalStateException("api-key=secret"));
        assertThrows(IllegalStateException.class, () -> service.process(message, scope));
        verify(audits).save(argThat(a -> "b".equals(a.getBehaviorId()) && a.getRetrievalStatus() == RetrievalAuditStatus.SUCCESS));
        verify(audits).save(argThat(a -> "c".equals(a.getBehaviorId()) && a.getRetrievalStatus() == RetrievalAuditStatus.FAILED
                && !a.getErrorMessage().contains("secret")));
        verify(audits, times(2)).save(any());
        verifyNoInteractions(completion);
    }

    @Test void sameRunReplayAfterCompletionKeepsTheOriginalAudit() {
        // 完成服务内部推进运行状态；mock 中模拟事务提交后的终态变更
        doAnswer(invocation -> {
            run.completeWithoutDecision(LocalDateTime.now());
            return null;
        }).when(completion).complete(message, scope);
        response("b", RetrievalBatchStatus.SUCCESS, Collections.singletonList(candidate("b")));

        service.process(message, scope);
        service.process(message, scope);

        verify(runs, times(2)).findByAnalysisRunIdAndAssessmentIdAndProjectId("real-run", 90L, 7L);
        verify(audits, times(1)).save(any());
        verify(completion, times(1)).complete(message, scope);
    }

    @Test void failingNewRunOnSameAssessmentLeavesPreviousRunAuditsUntouched() {
        run.completeWithoutDecision(LocalDateTime.now());
        AnalysisScope nextScope = new AnalysisScope(7L, 90L, "next-run");
        AnalysisRun nextRun = AnalysisRun.start(nextScope, LocalDateTime.now());
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("next-run", 90L, 7L)).thenReturn(Optional.of(nextRun));
        Behavior nextBehavior = Behavior.builder().id("next-behavior").projectId(7L).assessmentId(90L)
                .analysisRunId("next-run").sourceDocumentId(behavior.getSourceDocumentId())
                .description("第二条事实").evidenceIds(behavior.getEvidenceIds()).build();
        when(behaviors.findByScope(7L, 90L, "next-run")).thenReturn(Collections.singletonList(nextBehavior));
        when(retrieval.retrieve(any())).thenThrow(new IllegalStateException("检索服务不可用"));
        IndicatorCalculationTaskMessage nextMessage = new IndicatorCalculationTaskMessage(
                "message-2", "time", "trace", 5L, 7L, 90L, "next-run");

        assertThrows(IllegalStateException.class, () -> service.process(nextMessage, nextScope));

        verify(audits, times(1)).save(argThat(a -> "next-run".equals(a.getAnalysisRunId())
                && a.getRetrievalStatus() == RetrievalAuditStatus.FAILED
                && a.getAnalysisStatus() == AnalysisAuditStatus.NOT_ATTEMPTED));
        verify(completion, never()).complete(nextMessage, nextScope);
        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, run.getStatus());
    }

    @Test void foreignBehaviorIsRejectedBeforeEvidenceOrRetrieval() {
        behavior.setAssessmentId(91L);
        assertThrows(IllegalStateException.class, () -> service.process(message, scope));
        verifyNoInteractions(evidence, retrieval, audits, completion);
    }

    private void response(String id, RetrievalBatchStatus status, List<RetrievalCandidateSnapshot> candidates) {
        when(retrieval.retrieve(any())).thenReturn(RetrievalBatchResponse.builder().items(Collections.singletonList(
                RetrievalBatchItem.builder().behaviorId(id).status(status).candidates(candidates)
                        .embeddingModel("BAAI/bge-m3").embeddingVersion("v1")
                        .matchedFilters(Collections.singletonMap("applied", "false")).build())).build());
    }
    private void gatedResponse(List<RetrievalCandidateSnapshot> candidates,
                               double indicatorMinimumScore, double regulationMinimumScore) {
        when(retrieval.retrieve(any())).thenReturn(RetrievalBatchResponse.builder().items(Collections.singletonList(
                RetrievalBatchItem.builder().behaviorId("b").status(RetrievalBatchStatus.SUCCESS).candidates(candidates)
                        .embeddingModel("BAAI/bge-m3").embeddingVersion("v1")
                        .qualityGateEnabled(true).indicatorMinimumScore(indicatorMinimumScore)
                        .regulationMinimumScore(regulationMinimumScore)
                        .matchedFilters(Collections.singletonMap("applied", "false")).build())).build());
    }

    private RetrievalCandidateSnapshot candidate(String id) {
        return candidate(id, RetrievalCandidateType.INDICATOR, "indicator-1", 0.8, 1);
    }

    private RetrievalCandidateSnapshot candidate(String behaviorId, RetrievalCandidateType type,
                                                  String candidateId, double score, int rank) {
        return RetrievalCandidateSnapshot.builder().name("候选名称").retrievalTextHash("hash")
                .result(RetrievalResult.builder().candidateType(type).candidateId(candidateId)
                        .rank(rank).score(score).scoreType(type == RetrievalCandidateType.INDICATOR
                                ? ScoreType.COSINE_SIMILARITY : ScoreType.HYBRID)
                        .embeddingModel("BAAI/bge-m3").embeddingVersion("v1").behaviorId(behaviorId)
                        .assessmentId(90L).analysisRunId("real-run")
                        .matchedFilters(Collections.singletonMap("applied", "false")).build()).build();
    }
}
