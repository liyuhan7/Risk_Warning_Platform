package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.utils.KafkaUtils;
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
    private final AnalysisRunNoDecisionService noDecision = mock(AnalysisRunNoDecisionService.class);
    private final KafkaUtils kafka = mock(KafkaUtils.class);
    private final P2ProcessingProperties properties = new P2ProcessingProperties();
    private final AnalysisScope scope = new AnalysisScope(7L, 90L, "real-run");
    private final IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
            "message", "time", "trace", 5L, 7L, 90L, "real-run");
    private final P2RetrievalProcessingService service = new P2RetrievalProcessingService(
            behaviors, evidence, retrieval, audits, runs, noDecision, new BehaviorQueryTextBuilder(), properties, kafka);
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
        verify(noDecision).complete(scope);
        verify(kafka).sendMessage(any());
        verify(retrieval).retrieve(argThat(request -> request.getRequests().get(0).getQueryText()
                .equals("公司留存采购审批意见 留存 审批意见")));
    }

    @Test void completedRunReplayDoesNotOverwriteSnapshots() {
        run.completeWithoutDecision(LocalDateTime.now());
        service.process(message, scope);
        verifyNoInteractions(behaviors, evidence, retrieval, audits, noDecision, kafka);
    }

    @Test void noCandidatesIsNotAZeroRiskConclusion() {
        response("b", RetrievalBatchStatus.NO_CANDIDATES, Collections.emptyList());
        service.process(message, scope);
        verify(audits).save(argThat(a -> a.getRetrievalStatus() == RetrievalAuditStatus.NO_CANDIDATES
                && a.getAnalysisStatus() == AnalysisAuditStatus.NOT_ATTEMPTED
                && "BAAI/bge-m3".equals(a.getEmbeddingModel()) && "v1".equals(a.getEmbeddingVersion())));
        verify(noDecision).complete(scope);
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
        verifyNoInteractions(noDecision, kafka);
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
        verifyNoInteractions(noDecision, kafka);
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
        verifyNoInteractions(noDecision, kafka);
    }

    @Test void sameRunReplayAfterCompletionKeepsTheOriginalAudit() {
        doAnswer(invocation -> {
            run.completeWithoutDecision(LocalDateTime.now());
            return null;
        }).when(noDecision).complete(scope);
        response("b", RetrievalBatchStatus.SUCCESS, Collections.singletonList(candidate("b")));

        service.process(message, scope);
        service.process(message, scope);

        verify(runs, times(2)).findByAnalysisRunIdAndAssessmentIdAndProjectId("real-run", 90L, 7L);
        verify(audits, times(1)).save(any());
        verify(noDecision, times(1)).complete(scope);
        verify(kafka, times(1)).sendMessage(any());
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
        verify(noDecision, never()).complete(nextScope);
        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, run.getStatus());
    }

    @Test void foreignBehaviorIsRejectedBeforeEvidenceOrRetrieval() {
        behavior.setAssessmentId(91L);
        assertThrows(IllegalStateException.class, () -> service.process(message, scope));
        verifyNoInteractions(evidence, retrieval, audits, noDecision, kafka);
    }

    private void response(String id, RetrievalBatchStatus status, List<RetrievalCandidateSnapshot> candidates) {
        when(retrieval.retrieve(any())).thenReturn(RetrievalBatchResponse.builder().items(Collections.singletonList(
                RetrievalBatchItem.builder().behaviorId(id).status(status).candidates(candidates)
                        .embeddingModel("BAAI/bge-m3").embeddingVersion("v1")
                        .matchedFilters(Collections.singletonMap("applied", "false")).build())).build());
    }
    private RetrievalCandidateSnapshot candidate(String id) {
        return RetrievalCandidateSnapshot.builder().name("采购审批指标").retrievalTextHash("hash")
                .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.INDICATOR).candidateId("indicator-1")
                        .rank(1).score(0.8).scoreType(ScoreType.COSINE_SIMILARITY).embeddingModel("BAAI/bge-m3")
                        .embeddingVersion("v1").behaviorId(id).assessmentId(90L).analysisRunId("real-run")
                        .matchedFilters(Collections.singletonMap("applied", "false")).build()).build();
    }
}
