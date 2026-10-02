package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.CompletionKind;
import com.riskwarning.common.enums.analysis.RuleEvaluationStatus;
import com.riskwarning.common.message.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.processing.dto.analysis.AnalysisSummaryVO;
import com.riskwarning.processing.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportPreparationServiceTest {
    private final AnalysisScope scope = new AnalysisScope(1L, 2L, "run");
    private final IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage("m", "t", "trace", 3L, 1L, 2L, "run");
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final RetrievalAuditRepository retrievals = mock(RetrievalAuditRepository.class);
    private final AnalysisResultRepository analysis = mock(AnalysisResultRepository.class);
    private final AnalysisAttemptRepository attempts = mock(AnalysisAttemptRepository.class);
    private final RuleEvaluationAuditRepository rules = mock(RuleEvaluationAuditRepository.class);
    private final IndicatorResultRepository indicators = mock(IndicatorResultRepository.class);
    private final AnalysisCompletionPolicy policy = mock(AnalysisCompletionPolicy.class);
    private final KafkaOutbox outbox = mock(KafkaOutbox.class);
    private final ReportPreparationService service = new ReportPreparationService(runs, retrievals, analysis, attempts, rules, indicators, policy, outbox);
    private AnalysisRun run;

    @BeforeEach void setup() {
        run = AnalysisRun.start(scope, LocalDateTime.now(), AnalysisExecutionMode.REASONING); run.freezeBehaviors(Arrays.asList("b"));
        when(runs.lockScope("run", 2L, 1L)).thenReturn(Optional.of(run));
    }
    private void outcome(AnalysisRunStatus status, CompletionKind kind) {
        AnalysisSummaryVO summary = new AnalysisSummaryVO(); summary.setBehaviorCount(1); summary.setCompletionKind(kind);
        when(policy.resolve(eq(scope), eq(Collections.singleton("b")), anyList(), anyList(), anyList(), anyList(), anyBoolean()))
                .thenReturn(new AnalysisCompletionPolicy.Completion(status, summary));
    }
    @Test void successfulPreparationKeepsRunRunningAndEnqueuesOneReportEvent() {
        outcome(AnalysisRunStatus.SUCCEEDED, CompletionKind.FULL);
        service.prepare(message, scope, false); service.prepare(message, scope, false);
        assertEquals(AnalysisRunStatus.RUNNING, run.getStatus()); assertNull(run.getFinishedAt());
        assertEquals("FULL", run.getAnalysisSummary().path("completionKind").asText());
        verify(outbox, times(1)).enqueue(any(AssessmentCompletedEventMessage.class));
        verify(runs, times(1)).saveAndFlush(run);
    }
    @Test void noneAndFailureFinishWithoutPublishingReport() {
        outcome(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, CompletionKind.NONE);
        service.prepare(message, scope, false);
        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, run.getStatus());
        verify(outbox).enqueue(any(NotificationMessage.class)); verify(outbox, never()).enqueue(any(AssessmentCompletedEventMessage.class));
    }
    @Test void failedPreparationRetainsFailureSummary() {
        outcome(AnalysisRunStatus.FAILED, null); service.prepare(message, scope, true);
        assertEquals(AnalysisRunStatus.FAILED, run.getStatus());
        verify(outbox).enqueue(any(NotificationMessage.class));
    }
    @Test void missingIndicatorResultRejectsPreparationBeforeOutbox() {
        outcome(AnalysisRunStatus.SUCCEEDED, CompletionKind.FULL);
        when(rules.findByAssessmentIdAndAnalysisRunId(2L, "run")).thenReturn(Arrays.asList(RuleEvaluationAudit.builder()
                .indicatorId("i").status(RuleEvaluationStatus.DECIDED).build()));
        assertThrows(IllegalStateException.class, () -> service.prepare(message, scope, false));
        assertNull(run.getAnalysisSummary()); assertEquals(AnalysisRunStatus.RUNNING, run.getStatus()); verifyNoInteractions(outbox);
    }
}
