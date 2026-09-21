package com.riskwarning.processing.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.reliability.DurableWorkStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 可靠模式入箱 listener 测试：listener 返回前任务所有权必须已持久化，
 * 重复 messageId 幂等去重，结构不合法的消息不入箱。
 */
class DurableProcessingMessageTaskTest {

    private final DurableWorkStore store = mock(DurableWorkStore.class);
    private final ProcessingMessageCodec codec = new ProcessingMessageCodec(new ObjectMapper());
    private final AssessmentFlowLogger flowLogger = mock(AssessmentFlowLogger.class);
    private final DurableProcessingMessageTask task =
            new DurableProcessingMessageTask(store, codec, flowLogger);

    @Test
    void behaviorListenerPersistsWorkBeforeReturning() {
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));

        task.onBehaviorMessage(message);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(store).enqueue(eq(DurableProcessingMessageTask.BEHAVIOR_KIND),
                eq("msg-1"), payload.capture());
        BehaviorProcessingTaskMessage decoded = codec.decodeBehavior(payload.getValue());
        assertEquals(message, decoded);
    }

    @Test
    void indicatorListenerPersistsWorkBeforeReturning() {
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-2", "ts", "trace", 1L, 10L, 20L, "run-1");

        task.onIndicatorMessage(message);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(store).enqueue(eq(DurableProcessingMessageTask.INDICATOR_KIND),
                eq("msg-2"), payload.capture());
        assertEquals(message, codec.decodeIndicator(payload.getValue()));
    }

    @Test
    void duplicateTaskKeyStillCompletesInboxHandoff() {
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));
        when(store.enqueue(anyString(), anyString(), anyString())).thenReturn(false);

        // 唯一键冲突代表同一逻辑任务已持久化，listener 必须正常返回让容器提交位点
        assertDoesNotThrow(() -> task.onBehaviorMessage(message));
    }

    @Test
    void invalidMessageNeverEntersInbox() {
        BehaviorProcessingTaskMessage legacy = new BehaviorProcessingTaskMessage(
                "msg-1", "ts", "trace", 1L, 10L, 20L,
                DataSourceTypeEnum.FILE_UPLOAD, Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> task.onBehaviorMessage(legacy));
        verifyNoInteractions(store);
    }

    @Test
    void keepsStableTaskKeyContract() {
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "derived:indicator", "ts", "trace", 1L, 10L, 20L, "run-1");

        task.onIndicatorMessage(message);

        // 下游任务 key 沿用消息自身 messageId，保证重投幂等
        verify(store).enqueue(eq(DurableProcessingMessageTask.INDICATOR_KIND),
                eq("derived:indicator"), anyString());
    }
}
