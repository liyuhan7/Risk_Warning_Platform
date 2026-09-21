package com.riskwarning.report.task;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.report.service.AnalysisRunCompletionService;
import com.riskwarning.report.service.AnalysisRunFailureService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 兼容模式报告消费测试：直消费语义与本次失败即标记运行的既有表现。
 */
class MessageTaskTest {

    private final AnalysisRunCompletionService completionService =
            mock(AnalysisRunCompletionService.class);
    private final AnalysisRunFailureService failureService = mock(AnalysisRunFailureService.class);
    private final MessageTask task = new MessageTask();
    private final AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");

    @Test
    void compatModeDelegatesToCompletionService() {
        ReflectionTestUtils.setField(task, "analysisRunCompletionService", completionService);
        ReflectionTestUtils.setField(task, "analysisRunFailureService", failureService);
        AssessmentCompletedEventMessage message = message();

        task.onMessage(message);

        verify(completionService).aggregateAndComplete(eq(message), eq(scope));
        verifyNoInteractions(failureService);
    }

    @Test
    void compatModeMarksRunFailedOnFailure() {
        ReflectionTestUtils.setField(task, "analysisRunCompletionService", completionService);
        ReflectionTestUtils.setField(task, "analysisRunFailureService", failureService);
        doThrow(new RuntimeException("汇总失败")).when(completionService)
                .aggregateAndComplete(any(), any());

        task.onMessage(message());

        verify(failureService).markFailed(eq(scope), any(LocalDateTime.class));
    }

    @Test
    void compatModeKeepsFailingEvenWhenMarkingFails() {
        ReflectionTestUtils.setField(task, "analysisRunCompletionService", completionService);
        ReflectionTestUtils.setField(task, "analysisRunFailureService", failureService);
        doThrow(new RuntimeException("汇总失败")).when(completionService)
                .aggregateAndComplete(any(), any());
        doThrow(new IllegalStateException("标记失败")).when(failureService)
                .markFailed(any(), any());

        task.onMessage(message());

        verify(failureService).markFailed(eq(scope), any(LocalDateTime.class));
    }

    private AssessmentCompletedEventMessage message() {
        return new AssessmentCompletedEventMessage(
                "msg-1", "ts", "trace", 5L, 10L, 20L, "run-1");
    }
}
