package com.riskwarning.report.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.reliability.DurableWorkStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可靠报告入箱 listener 测试：稳定 taskKey 入箱、幂等去重与异常边界。
 */
class DurableReportMessageTaskTest {

    private final DurableWorkStore store = mock(DurableWorkStore.class);
    private final ReportMessageCodec codec = new ReportMessageCodec(new ObjectMapper());
    private final DurableReportMessageTask task = new DurableReportMessageTask(store, codec);

    @Test
    void enqueuesReportWorkWithStableTaskKey() throws Exception {
        AssessmentCompletedEventMessage message = message();

        task.onMessage(message);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(store).enqueue(eq(ReportAggregationWorkHandler.KIND), eq("msg-1"), payload.capture());
        assertEquals("msg-1", codec.decode(payload.getValue()).getMessageId());
    }

    @Test
    void duplicateEnqueueIsToleratedAsIdempotentHandoff() {
        AssessmentCompletedEventMessage message = message();
        org.mockito.Mockito.when(store.enqueue(anyString(), anyString(), anyString()))
                .thenReturn(false);

        task.onMessage(message);
    }

    @Test
    void storeFailurePropagatesOutOfListener() {
        AssessmentCompletedEventMessage message = message();
        doThrow(new IllegalStateException("入箱失败")).when(store)
                .enqueue(anyString(), anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> task.onMessage(message));
    }

    @Test
    void invalidMessageNeverTouchesStore() {
        AssessmentCompletedEventMessage message = message();
        message.setMessageId("");

        assertThrows(IllegalArgumentException.class, () -> task.onMessage(message));
        verify(store, never()).enqueue(any(), any(), any());
    }

    private AssessmentCompletedEventMessage message() {
        return new AssessmentCompletedEventMessage(
                "msg-1", "ts", "trace", 5L, 10L, 20L, "run-1");
    }
}
