package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2 完成原子化测试：状态推进与通知入箱同事务、幂等重放、
 * 作用域校验与 Outbox 异常传播边界。
 */
class P2CompletionServiceTest {

    private static final AnalysisScope SCOPE = new AnalysisScope(7L, 90L, "real-run");

    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final KafkaOutbox outbox = mock(KafkaOutbox.class);
    private final P2CompletionService service = new P2CompletionService(runs, outbox,
            mock(AssessmentFlowLogger.class));
    private AnalysisRun run;
    private IndicatorCalculationTaskMessage message;

    @BeforeEach
    void setup() {
        run = AnalysisRun.start(SCOPE, LocalDateTime.now());
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("real-run", 90L, 7L))
                .thenReturn(Optional.of(run));
        message = new IndicatorCalculationTaskMessage(
                "message", "time", "trace", 5L, 7L, 90L, "real-run");
    }

    @Test
    void completesRunningRunAndEnqueuesNotificationInSameInvocation() {
        service.complete(message, SCOPE);

        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, run.getStatus());
        assertNotNull(run.getFinishedAt());
        verify(runs).saveAndFlush(run);
        verify(outbox).enqueue(any(NotificationMessage.class));
    }

    @Test
    void notificationKeepsStableIdentityAndScopeContract() {
        service.complete(message, SCOPE);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(outbox).enqueue(captor.capture());
        NotificationMessage notification = captor.getValue();
        assertEquals("real-run:p2-completed-notification", notification.getMessageId());
        assertEquals("trace", notification.getTraceId());
        assertEquals(5L, notification.getUserId());
        assertEquals(7L, notification.getProjectId());
        assertEquals(90L, notification.getAssessmentId());
        assertEquals("real-run", notification.getAnalysisRunId());
        assertEquals(NotificationMessage.NotificationType.ASSESSMENT_COMPLETED, notification.getNotificationType());
        assertTrue(notification.getExtraData().contains("COMPLETED_WITHOUT_DECISION"));
        assertTrue(notification.getExtraData().contains("P2"));
    }

    @Test
    void missingUpstreamTraceIdFallsBackToGeneratedTrace() {
        message.setTraceId(null);

        service.complete(message, SCOPE);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(outbox).enqueue(captor.capture());
        assertTrue(captor.getValue().getTraceId() != null
                && !captor.getValue().getTraceId().trim().isEmpty());
    }

    @Test
    void replayAfterCompletionIsIdempotent() {
        run.completeWithoutDecision(LocalDateTime.now());

        service.complete(message, SCOPE);

        verify(runs, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void otherTerminalStateIsNotOverwrittenOrNotified() {
        run.fail(LocalDateTime.now());

        service.complete(message, SCOPE);

        assertEquals(AnalysisRunStatus.FAILED, run.getStatus());
        verify(runs, never()).saveAndFlush(run);
        verifyNoInteractions(outbox);
    }

    @Test
    void scopeMismatchIsRejectedBeforeAnyStateChange() {
        IndicatorCalculationTaskMessage foreign = new IndicatorCalculationTaskMessage(
                "message", "time", "trace", 5L, 8L, 90L, "real-run");

        assertThrows(IllegalArgumentException.class, () -> service.complete(foreign, SCOPE));
        verifyNoInteractions(runs, outbox);
    }

    @Test
    void missingRunIsRejectedWithoutNotification() {
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("real-run", 90L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.complete(message, SCOPE));
        verify(runs, never()).saveAndFlush(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void outboxFailurePropagatesForDurableRetry() {
        IllegalStateException failure = new IllegalStateException("Outbox 入箱失败");
        doThrow(failure).when(outbox).enqueue(any(NotificationMessage.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.complete(message, SCOPE));

        assertEquals(failure, thrown);
        verify(runs).saveAndFlush(run);
    }
}
