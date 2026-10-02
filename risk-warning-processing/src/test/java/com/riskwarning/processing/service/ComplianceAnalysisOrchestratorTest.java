package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.provider.LlmProviderException;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.rule.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplianceAnalysisOrchestratorTest {
    private final AnalysisScope scope = new AnalysisScope(1L, 2L, "run");
    private final IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage("m", "t", "trace", 3L, 1L, 2L, "run");
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
    private final AnalysisRunIdentityService identity = mock(AnalysisRunIdentityService.class);
    private final RetrievalProcessingService retrieval = mock(RetrievalProcessingService.class);
    private final RetrievalAuditRepository audits = mock(RetrievalAuditRepository.class);
    private final AnalysisResultRepository results = mock(AnalysisResultRepository.class);
    private final AnalysisAttemptRepository attempts = mock(AnalysisAttemptRepository.class);
    private final AnalysisContextService contexts = mock(AnalysisContextService.class);
    private final RegisteredComplianceAnalysisService analysis = mock(RegisteredComplianceAnalysisService.class);
    private final RuleSourceSnapshotService sources = mock(RuleSourceSnapshotService.class);
    private final ComplianceAuditWriter writer = mock(ComplianceAuditWriter.class);
    private final IndicatorEvaluationService evaluation = mock(IndicatorEvaluationService.class);
    private final ReportPreparationService preparation = mock(ReportPreparationService.class);
    private final ComplianceAnalysisOrchestrator orchestrator = new ComplianceAnalysisOrchestrator(runs, behaviors, identity, retrieval, audits, results, attempts,
            contexts, analysis, sources, writer, evaluation, preparation);
    private AnalysisRun run;
    private BehaviorAnalysisContextVO item;

    @BeforeEach void setup() {
        run = AnalysisRun.start(scope, LocalDateTime.now(), AnalysisExecutionMode.REASONING);
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.of(run));
        when(behaviors.findByScope(1L, 2L, "run")).thenReturn(Arrays.asList(Behavior.builder().id("b").projectId(1L).assessmentId(2L).analysisRunId("run").build()));
        when(identity.freeze(eq(scope), anyList())).thenReturn(true);
        when(sources.sources(scope)).thenReturn(Collections.emptyMap());
        item = BehaviorAnalysisContextVO.builder().behaviorId("b").analysisStatus("WAITING_ANALYSIS").readyForAnalysis(true).build();
        when(contexts.context(2L, 1L, "run")).thenReturn(AnalysisContextVO.builder().projectId(1L).assessmentId(2L).analysisRunId("run").items(Arrays.asList(item)).build());
    }
    @Test void connectsFreezeRetrievalAnalysisRulesAndReportPreparationInOrder() {
        orchestrator.process(message, scope);
        InOrder order = inOrder(identity, retrieval, analysis, evaluation, preparation);
        order.verify(identity).freeze(eq(scope), anyList()); order.verify(retrieval).retrieveForAnalysis(message, scope);
        order.verify(analysis).analyze(scope, "b", Collections.emptyMap(), Collections.emptyMap());
        order.verify(evaluation).evaluate(eq(scope), anyList(), anyList(), anyList(), eq(Collections.emptyMap()));
        order.verify(preparation).prepare(message, scope, false);
        assertEquals(AnalysisRunStatus.RUNNING, run.getStatus());
    }
    @Test void terminalAndHistoricalRunCannotEnterModelChain() {
        run.completeWithoutDecision(LocalDateTime.now()); orchestrator.process(message, scope);
        run = AnalysisRun.start(scope, LocalDateTime.now());
        org.springframework.test.util.ReflectionTestUtils.setField(run, "executionMode", null);
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.of(run));
        assertThrows(IllegalStateException.class, () -> orchestrator.process(message, scope));
        verifyNoInteractions(behaviors, retrieval, analysis, evaluation, preparation);
    }
    @Test void providerFailureStopsRemainingStepsAndPreparesFailure() {
        when(analysis.analyze(scope, "b", Collections.emptyMap(), Collections.emptyMap())).thenThrow(new LlmProviderException("unavailable", 503, true));
        orchestrator.process(message, scope);
        verify(preparation).prepare(message, scope, true); verifyNoInteractions(evaluation);
    }
    @Test void previousProviderFailureDoesNotCallModelAgain() {
        when(attempts.findByAssessmentIdAndAnalysisRunId(2L, "run")).thenReturn(Arrays.asList(AnalysisAttempt.builder().status(AnalysisAttemptStatus.PROVIDER_FAILED).build()));
        orchestrator.process(message, scope);
        verify(preparation).prepare(message, scope, true); verifyNoInteractions(retrieval, analysis, evaluation);
    }
    @Test void blockedEvidenceIsRecordedRatherThanSentToModel() {
        item.setReadyForAnalysis(false); item.setBlockedReason("MISSING_EVIDENCE");
        orchestrator.process(message, scope);
        verify(writer).complete(scope, "b", AnalysisAuditStatus.INSUFFICIENT_EVIDENCE, null, "ANALYSIS_CONTEXT_NOT_READY", "MISSING_EVIDENCE");
        verifyNoInteractions(analysis); verify(preparation).prepare(message, scope, false);
    }
    @Test void changedReplayInputFailsBeforeRetrievalOrModel() {
        when(identity.freeze(eq(scope), anyList())).thenThrow(new com.riskwarning.common.exception.AnalysisInputConflictException("事实变化"));
        orchestrator.process(message, scope);
        verify(preparation).prepare(message, scope, true);
        verifyNoInteractions(retrieval, analysis, evaluation);
    }
}
