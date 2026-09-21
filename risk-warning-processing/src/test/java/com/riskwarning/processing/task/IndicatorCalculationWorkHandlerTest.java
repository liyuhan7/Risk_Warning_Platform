package com.riskwarning.processing.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.processing.config.P2ProcessingProperties;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.BehaviorProcessingService;
import com.riskwarning.processing.service.P2RetrievalProcessingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 指标计算持久任务测试：运行模式分发、稳定 messageId 透传、
 * 异常外抛与重试耗尽后的运行失败标记。
 */
class IndicatorCalculationWorkHandlerTest {

    private final ProcessingMessageCodec codec = new ProcessingMessageCodec(new ObjectMapper());
    private final P2RetrievalProcessingService p2Service = mock(P2RetrievalProcessingService.class);
    private final BehaviorProcessingService legacyService = mock(BehaviorProcessingService.class);
    private final AnalysisRunFailureService analysisRunFailureService =
            mock(AnalysisRunFailureService.class);

    @Test
    void routesP2ModeToRetrievalChain() throws Exception {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        properties.setMode(P2ProcessingProperties.Mode.P2);
        IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(
                codec, p2Service, legacyService, properties, analysisRunFailureService);
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-1:indicator", "ts", "trace", 1L, 10L, 20L, "run-1");
        String payload = codec.encodeIndicator(message);

        handler.execute(workContext(), payload);

        verify(p2Service).process(eq(message), eq(new AnalysisScope(10L, 20L, "run-1")));
        verifyNoInteractions(legacyService);
    }

    @Test
    void routesLegacyModeToBehaviorChainWithStableMessageId() throws Exception {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(
                codec, p2Service, legacyService, properties, analysisRunFailureService);
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-1:indicator", "ts", "trace", 7L, 10L, 20L, "run-1");
        String payload = codec.encodeIndicator(message);

        handler.execute(workContext(), payload);

        verify(legacyService).processProjectBehaviors(
                7L, 10L, 20L, "run-1", "msg-1:indicator");
        verifyNoInteractions(p2Service);
    }

    @Test
    void flowContextCarriesMessageIdentityFromPayload() {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(
                codec, p2Service, legacyService, properties, analysisRunFailureService);
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-1:indicator", "ts", "trace", 7L, 10L, 20L, "run-1");
        String payload = codec.encodeIndicator(message);

        AssessmentFlowContext context = handler.flowContext(workContext(), payload);

        assertEquals(Long.valueOf(10L), context.getProjectId());
        assertEquals(Long.valueOf(20L), context.getAssessmentId());
        assertEquals("run-1", context.getAnalysisRunId());
        assertEquals("msg-1:indicator", context.getMessageId());
        assertEquals("trace", context.getTraceId());
    }

    @Test
    void retrievalFailurePropagatesToDurableRetry() throws Exception {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        properties.setMode(P2ProcessingProperties.Mode.P2);
        IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(
                codec, p2Service, legacyService, properties, analysisRunFailureService);
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-1:indicator", "ts", "trace", 1L, 10L, 20L, "run-1");
        String payload = codec.encodeIndicator(message);
        IllegalStateException failure = new IllegalStateException("检索服务不可用");
        doThrow(failure).when(p2Service).process(any(), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> handler.execute(workContext(), payload));

        assertEquals(failure, thrown);
        verifyNoInteractions(legacyService, analysisRunFailureService);
    }

    @Test
    void legacyFailurePropagatesToDurableRetry() throws Exception {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(
                codec, p2Service, legacyService, properties, analysisRunFailureService);
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-1:indicator", "ts", "trace", 7L, 10L, 20L, "run-1");
        String payload = codec.encodeIndicator(message);
        IllegalStateException failure = new IllegalStateException("行为评估失败");
        doThrow(failure).when(legacyService).processProjectBehaviors(
                any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> handler.execute(workContext(), payload));

        verifyNoInteractions(analysisRunFailureService);
    }

    @Test
    void exhaustionMarksAnalysisRunFailedWithPayloadScopeInP2Mode() throws Exception {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        properties.setMode(P2ProcessingProperties.Mode.P2);
        IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(
                codec, p2Service, legacyService, properties, analysisRunFailureService);
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-1:indicator", "ts", "trace", 1L, 10L, 20L, "run-1");
        String payload = codec.encodeIndicator(message);

        handler.onExhausted(workContext(), payload, new IllegalStateException("重试次数耗尽"));

        verify(analysisRunFailureService).markFailed(
                eq(new AnalysisScope(10L, 20L, "run-1")), any(java.time.LocalDateTime.class));
    }

    @Test
    void exhaustionMarksAnalysisRunFailedWithPayloadScopeInLegacyMode() throws Exception {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(
                codec, p2Service, legacyService, properties, analysisRunFailureService);
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-1:indicator", "ts", "trace", 7L, 10L, 20L, "run-1");
        String payload = codec.encodeIndicator(message);

        handler.onExhausted(workContext(), payload, new IllegalStateException("重试次数耗尽"));

        verify(analysisRunFailureService).markFailed(
                eq(new AnalysisScope(10L, 20L, "run-1")), any(java.time.LocalDateTime.class));
    }

    @Test
    void exhaustionWithBrokenPayloadSkipsRunFailureMarking() {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(
                codec, p2Service, legacyService, properties, analysisRunFailureService);

        handler.onExhausted(workContext(), "{broken", new IllegalStateException("重试次数耗尽"));

        verifyNoInteractions(analysisRunFailureService);
    }

    private com.riskwarning.common.reliability.DurableWorkContext workContext() {
        com.riskwarning.common.reliability.DurableWork work =
                new com.riskwarning.common.reliability.DurableWork(
                        "work-1", "risk-warning-processing",
                        IndicatorCalculationWorkHandler.KIND, "msg-1:indicator", "{}", 1,
                        "worker-1", "lease-1", java.time.LocalDateTime.now().plusMinutes(15));
        return new com.riskwarning.common.reliability.DurableWorkContext(work);
    }
}
