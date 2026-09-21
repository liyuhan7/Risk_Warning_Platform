package com.riskwarning.report.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.report.repository.AnalysisRunRepository;
import com.riskwarning.report.repository.AssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LEGACY 报告完成链测试：严格顺序 aggregate → cleanup → succeed → 通知入箱、
 * 幂等重放、终态保护、作用域校验与 Outbox 异常传播。
 */
class AnalysisRunCompletionServiceTest {

    private static final AnalysisScope SCOPE = new AnalysisScope(10L, 20L, "run-1");

    private final AnalysisRunRepository analysisRunRepository = mock(AnalysisRunRepository.class);
    private final AssessmentService assessmentService = mock(AssessmentService.class);
    private final AssessmentRepository assessmentRepository = mock(AssessmentRepository.class);
    private final AnalysisRunResultCleanupService cleanupService =
            mock(AnalysisRunResultCleanupService.class);
    private final KafkaOutbox outbox = mock(KafkaOutbox.class);
    private final AssessmentFlowLogger flowLogger = mock(AssessmentFlowLogger.class);
    private final AnalysisRunCompletionService service = new AnalysisRunCompletionService(
            analysisRunRepository, assessmentService, assessmentRepository,
            cleanupService, outbox, flowLogger);
    private AnalysisRun run;
    private AssessmentCompletedEventMessage message;
    private Assessment assessment;

    @BeforeEach
    void setup() {
        run = AnalysisRun.start(SCOPE, LocalDateTime.of(2026, 9, 5, 10, 0));
        when(analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                "run-1", 20L, 10L)).thenReturn(Optional.of(run));
        message = new AssessmentCompletedEventMessage(
                "msg-1", "ts", "trace", 5L, 10L, 20L, "run-1");
        assessment = Assessment.builder().projectId(10L)
                .overallRiskLevel(RiskLevelEnum.LOW_RISK).overallScore(0.75).build();
        when(assessmentRepository.findById(20L)).thenReturn(Optional.of(assessment));
    }

    @Test
    void aggregatesThenCleanupsThenSucceedsThenEnqueuesNotification() throws Exception {
        service.aggregateAndComplete(message, SCOPE);

        InOrder order = inOrder(assessmentService, cleanupService, analysisRunRepository, outbox);
        order.verify(assessmentService).aggregateInformation(5L, 10L, 20L, "run-1");
        order.verify(cleanupService).cleanupAfterSuccess(20L, "run-1");
        order.verify(analysisRunRepository).saveAndFlush(run);
        order.verify(outbox).enqueue(any(NotificationMessage.class));
        assertEquals(AnalysisRunStatus.SUCCEEDED, run.getStatus());

        org.mockito.ArgumentCaptor<AssessmentFlowEvent> flowCaptor =
                org.mockito.ArgumentCaptor.forClass(AssessmentFlowEvent.class);
        verify(flowLogger, org.mockito.Mockito.times(2)).info(flowCaptor.capture());
        assertEquals(AssessmentFlowStage.REPORT_AGGREGATE,
                flowCaptor.getAllValues().get(0).getStage());
        assertEquals(AssessmentFlowStage.REPORT_COMPLETE,
                flowCaptor.getAllValues().get(1).getStage());
        assertEquals(AssessmentFlowStatus.SUCCEEDED,
                flowCaptor.getAllValues().get(1).getStatus());
    }

    @Test
    void notificationKeepsStableIdentityAndRunScope() {
        service.aggregateAndComplete(message, SCOPE);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(outbox).enqueue(captor.capture());
        NotificationMessage notification = captor.getValue();
        assertEquals("run-1:assessment-completed-notification", notification.getMessageId());
        assertEquals("trace", notification.getTraceId());
        assertEquals(5L, notification.getUserId());
        assertEquals(10L, notification.getProjectId());
        assertEquals(20L, notification.getAssessmentId());
        assertEquals("run-1", notification.getAnalysisRunId());
        assertEquals(NotificationMessage.NotificationType.ASSESSMENT_COMPLETED,
                notification.getNotificationType());
        assertTrue(notification.getContent().contains("低风险"));
        assertTrue(notification.getExtraData().contains("assessmentId"));
        assertTrue(notification.getExtraData().contains("LOW_RISK"));
    }

    @Test
    void missingUpstreamTraceIdFallsBackToGeneratedTrace() {
        message.setTraceId(null);

        service.aggregateAndComplete(message, SCOPE);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(outbox).enqueue(captor.capture());
        assertTrue(captor.getValue().getTraceId() != null
                && !captor.getValue().getTraceId().trim().isEmpty());
    }

    @Test
    void aggregationFailureSkipsCleanupSuccessAndNotification() {
        RuntimeException failure = new RuntimeException("汇总失败");
        doThrow(failure).when(assessmentService)
                .aggregateInformation(5L, 10L, 20L, "run-1");

        assertThrows(RuntimeException.class, () -> service.aggregateAndComplete(message, SCOPE));

        assertEquals(AnalysisRunStatus.RUNNING, run.getStatus());
        verify(analysisRunRepository, never()).saveAndFlush(any());
        verifyNoInteractions(cleanupService, outbox);
    }

    @Test
    void cleanupFailureKeepsRunRunningWithoutNotification() throws Exception {
        doThrow(new IllegalStateException("清理失败")).when(cleanupService)
                .cleanupAfterSuccess(20L, "run-1");

        assertThrows(IllegalStateException.class, () -> service.aggregateAndComplete(message, SCOPE));

        assertEquals(AnalysisRunStatus.RUNNING, run.getStatus());
        verify(analysisRunRepository, never()).saveAndFlush(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void outboxFailurePropagatesForDurableRetry() {
        IllegalStateException failure = new IllegalStateException("Outbox 入箱失败");
        doThrow(failure).when(outbox).enqueue(any(NotificationMessage.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.aggregateAndComplete(message, SCOPE));

        assertEquals(failure, thrown);
        verify(analysisRunRepository).saveAndFlush(run);
    }

    @Test
    void missingAssessmentAfterAggregationBlocksNotification() {
        when(assessmentRepository.findById(20L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.aggregateAndComplete(message, SCOPE));

        verify(analysisRunRepository).saveAndFlush(run);
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void succeededRunReplayIsIdempotent() {
        run.succeed(LocalDateTime.now());

        service.aggregateAndComplete(message, SCOPE);

        verifyNoInteractions(assessmentService, cleanupService, outbox);
        verify(analysisRunRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownOrTerminalRunIsRejected() {
        when(analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                "run-1", 20L, 10L)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.aggregateAndComplete(message, SCOPE));

        AnalysisRun failed = AnalysisRun.start(SCOPE, LocalDateTime.of(2026, 9, 5, 10, 0));
        failed.fail(LocalDateTime.of(2026, 9, 5, 10, 1));
        when(analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                "run-1", 20L, 10L)).thenReturn(Optional.of(failed));
        assertThrows(BusinessException.class, () -> service.aggregateAndComplete(message, SCOPE));
        verifyNoInteractions(assessmentService);
    }

    @Test
    void scopeMismatchIsRejectedBeforeAnyBusinessStep() {
        AssessmentCompletedEventMessage foreign = new AssessmentCompletedEventMessage(
                "msg-1", "ts", "trace", 5L, 11L, 20L, "run-1");

        assertThrows(BusinessException.class, () -> service.aggregateAndComplete(foreign, SCOPE));
        verifyNoInteractions(assessmentService, cleanupService, outbox);
    }

    @Test
    void invalidUserIsRejectedBeforeAnyBusinessStep() {
        message.setUserId(null);

        assertThrows(BusinessException.class, () -> service.aggregateAndComplete(message, SCOPE));
        verifyNoInteractions(assessmentService, cleanupService, outbox);
    }
}
