package com.riskwarning.report.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.reliability.DurableWork;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.report.service.AnalysisRunCompletionService;
import com.riskwarning.report.service.AnalysisRunFailureService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 报告汇总持久任务测试：消息分发、异常外抛与重试耗尽后的运行失败标记。
 */
class ReportAggregationWorkHandlerTest {

    private final ReportMessageCodec codec = new ReportMessageCodec(new ObjectMapper());
    private final AnalysisRunCompletionService completionService =
            mock(AnalysisRunCompletionService.class);
    private final AnalysisRunFailureService failureService = mock(AnalysisRunFailureService.class);
    private final ReportAggregationWorkHandler handler = new ReportAggregationWorkHandler(
            codec, completionService, failureService);

    @Test
    void executesCompletionServiceWithPayloadScope() throws Exception {
        AssessmentCompletedEventMessage message = message();
        String payload = codec.encode(message);

        handler.execute(workContext(), payload);

        verify(completionService).aggregateAndComplete(
                eq(message), eq(new AnalysisScope(10L, 20L, "run-1")));
    }

    @Test
    void businessFailurePropagatesWithoutMarkingRunFailed() throws Exception {
        String payload = codec.encode(message());
        RuntimeException failure = new RuntimeException("汇总失败");
        doThrow(failure).when(completionService)
                .aggregateAndComplete(any(), any());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> handler.execute(workContext(), payload));

        assertEquals(failure, thrown);
        verifyNoInteractionsOnFailureService();
    }

    @Test
    void exhaustionMarksAnalysisRunFailedWithPayloadScope() throws Exception {
        String payload = codec.encode(message());

        handler.onExhausted(workContext(), payload, new IllegalStateException("重试次数耗尽"));

        verify(failureService).markFailed(
                eq(new AnalysisScope(10L, 20L, "run-1")), any(LocalDateTime.class));
    }

    @Test
    void exhaustionWithBrokenPayloadSkipsRunFailureMarking() {
        handler.onExhausted(workContext(), "{broken", new IllegalStateException("重试次数耗尽"));

        verifyNoInteractionsOnFailureService();
    }

    @Test
    void markFailureExceptionDoesNotEscapeExhaustedCallback() throws Exception {
        String payload = codec.encode(message());
        doThrow(new IllegalStateException("标记失败")).when(failureService)
                .markFailed(any(), any());

        handler.onExhausted(workContext(), payload, new IllegalStateException("重试次数耗尽"));

        verify(failureService).markFailed(
                eq(new AnalysisScope(10L, 20L, "run-1")), any(LocalDateTime.class));
    }

    @Test
    void exhaustedScopeKeepsOriginalAnalysisRunId() throws Exception {
        // 重试耗尽也必须使用原始 payload 中的作用域，不得派生新运行
        AssessmentCompletedEventMessage message = message();
        String payload = codec.encode(message);

        handler.onExhausted(workContext(), payload, new IllegalStateException("重试次数耗尽"));

        verify(failureService).markFailed(
                eq(new AnalysisScope(message.getProjectId(), message.getAssessmentId(),
                        message.getAnalysisRunId())), any(LocalDateTime.class));
    }

    private void verifyNoInteractionsOnFailureService() {
        org.mockito.Mockito.verifyNoInteractions(failureService);
    }

    @Test
    void flowContextCarriesEventIdentityFromPayload() {
        AssessmentCompletedEventMessage message = message();
        String payload = codec.encode(message);

        com.riskwarning.common.observability.AssessmentFlowContext flow =
                handler.flowContext(workContext(), payload);

        assertEquals(Long.valueOf(10L), flow.getProjectId());
        assertEquals(Long.valueOf(20L), flow.getAssessmentId());
        assertEquals("run-1", flow.getAnalysisRunId());
        assertEquals("msg-1", flow.getMessageId());
        assertEquals("trace", flow.getTraceId());
    }

    private AssessmentCompletedEventMessage message() {
        return new AssessmentCompletedEventMessage(
                "msg-1", "ts", "trace", 5L, 10L, 20L, "run-1");
    }

    private DurableWorkContext workContext() {
        DurableWork work = new DurableWork("work-1", "risk-warning-report",
                ReportAggregationWorkHandler.KIND, "msg-1", "{}", 1,
                "worker-1", "lease-1", LocalDateTime.now().plusMinutes(15));
        return new DurableWorkContext(work);
    }
}

