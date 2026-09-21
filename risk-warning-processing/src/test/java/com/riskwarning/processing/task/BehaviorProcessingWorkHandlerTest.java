package com.riskwarning.processing.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.reliability.DurableWork;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.processing.entity.dto.ProcessedDocument;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.DocumentProcessingService;
import com.riskwarning.processing.service.EvidenceExtractionService;
import com.riskwarning.processing.service.FactExtractionPipeline;
import com.riskwarning.processing.service.SourceDocumentScopeValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behavior 处理持久任务测试：业务步骤顺序、稳定 Indicator messageId、
 * 异常外抛、临时文件清理边界与重试耗尽后的运行失败标记。
 */
class BehaviorProcessingWorkHandlerTest {

    private final ProcessingMessageCodec codec = new ProcessingMessageCodec(new ObjectMapper());
    private final DocumentProcessingService documentProcessingService =
            mock(DocumentProcessingService.class);
    private final SourceDocumentScopeValidator scopeValidator =
            mock(SourceDocumentScopeValidator.class);
    private final EvidenceExtractionService evidenceExtractionService =
            mock(EvidenceExtractionService.class);
    private final FactExtractionPipeline factExtractionPipeline =
            mock(FactExtractionPipeline.class);
    private final KafkaOutbox kafkaOutbox = mock(KafkaOutbox.class);
    private final AnalysisRunFailureService analysisRunFailureService =
            mock(AnalysisRunFailureService.class);
    private final BehaviorProcessingWorkHandler handler = new BehaviorProcessingWorkHandler(
            codec, documentProcessingService, scopeValidator,
            evidenceExtractionService, factExtractionPipeline, kafkaOutbox,
            analysisRunFailureService);

    @TempDir
    Path tempDir;

    @Test
    void executesPipelineInOrderAndEnqueuesDerivedIndicatorMessage() throws Exception {
        BehaviorProcessingTaskMessage message = scopedMessage();
        String payload = codec.encodeBehavior(message);
        List<ProcessedDocument> internalFiles = Collections.singletonList(
                processedDocument("internal.docx"));

        when(documentProcessingService.processDocuments(any(AnalysisScope.class),
                eq(message.getDocuments()))).thenReturn(internalFiles);
        when(factExtractionPipeline.process(any(AnalysisScope.class),
                eq(message.getDocuments()))).thenReturn(Collections.emptyList());

        handler.execute(workContext(), payload);

        InOrder order = inOrder(scopeValidator, documentProcessingService,
                evidenceExtractionService, factExtractionPipeline, kafkaOutbox);
        order.verify(scopeValidator).validate(new AnalysisScope(10L, 20L, "run-1"),
                message.getDocuments());
        order.verify(documentProcessingService).processDocuments(
                new AnalysisScope(10L, 20L, "run-1"), message.getDocuments());
        order.verify(evidenceExtractionService).extractAndPersist(
                new AnalysisScope(10L, 20L, "run-1"), internalFiles);
        order.verify(factExtractionPipeline).process(
                new AnalysisScope(10L, 20L, "run-1"), message.getDocuments());
        ArgumentCaptor<IndicatorCalculationTaskMessage> indicator =
                ArgumentCaptor.forClass(IndicatorCalculationTaskMessage.class);
        order.verify(kafkaOutbox).enqueue(indicator.capture());
        assertEquals("msg-1:indicator", indicator.getValue().getMessageId());
        assertEquals(message.getDocuments(), indicator.getValue().getDocuments());
    }

    @Test
    void businessFailurePropagatesWithoutIndicatorDelivery() throws Exception {
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));
        String payload = codec.encodeBehavior(message);
        when(documentProcessingService.processDocuments(any(AnalysisScope.class),
                any())).thenReturn(Collections.emptyList());
        IllegalStateException failure = new IllegalStateException("事实抽取失败");
        when(factExtractionPipeline.process(any(AnalysisScope.class), any()))
                .thenThrow(failure);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> handler.execute(workContext(), payload));

        assertEquals(failure, thrown);
        verifyNoInteractions(kafkaOutbox, analysisRunFailureService);
    }

    @Test
    void exhaustionMarksAnalysisRunFailedWithPayloadScope() throws Exception {
        BehaviorProcessingTaskMessage message = scopedMessage();
        String payload = codec.encodeBehavior(message);
        IllegalStateException failure = new IllegalStateException("重试次数耗尽");

        handler.onExhausted(workContext(), payload, failure);

        verify(analysisRunFailureService).markFailed(
                eq(new AnalysisScope(10L, 20L, "run-1")), any(LocalDateTime.class));
    }

    @Test
    void exhaustionWithBrokenPayloadSkipsRunFailureMarking() {
        handler.onExhausted(workContext(), "{broken", new IllegalStateException("重试次数耗尽"));

        verifyNoInteractions(analysisRunFailureService);
    }

    @Test
    void markFailureExceptionDoesNotEscapeExhaustedCallback() throws Exception {
        BehaviorProcessingTaskMessage message = scopedMessage();
        String payload = codec.encodeBehavior(message);
        doThrow(new IllegalStateException("标记失败")).when(analysisRunFailureService)
                .markFailed(any(AnalysisScope.class), any(LocalDateTime.class));

        handler.onExhausted(workContext(), payload, new IllegalStateException("重试次数耗尽"));

        verify(analysisRunFailureService).markFailed(
                eq(new AnalysisScope(10L, 20L, "run-1")), any(LocalDateTime.class));
    }

    @Test
    void deletesInternalFilesOnSuccessAndFailure() throws Exception {
        Path successFile = Files.createFile(tempDir.resolve("success.txt"));
        Path failureFile = Files.createFile(tempDir.resolve("failure.txt"));
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));
        String payload = codec.encodeBehavior(message);
        when(documentProcessingService.processDocuments(any(AnalysisScope.class), any()))
                .thenReturn(Collections.singletonList(processedDocument(successFile.toString())))
                .thenReturn(Collections.singletonList(processedDocument(failureFile.toString())));
        doThrow(new IllegalStateException("作用域校验失败")).doNothing().when(scopeValidator)
                .validate(any(AnalysisScope.class), any());

        assertThrows(IllegalStateException.class, () -> handler.execute(workContext(), payload));
        assertTrue(Files.exists(failureFile), "校验失败时尚未生成内部文件，不应误删");

        handler.execute(workContext(), payload);
        assertFalse(Files.exists(successFile), "成功路径必须清理内部文件");
    }

    @Test
    void cleanupFailureDoesNotBlockIndicatorDelivery() throws Exception {
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));
        String payload = codec.encodeBehavior(message);
        // 指向不存在的路径，deleteIfExists 返回 false 且不抛错
        when(documentProcessingService.processDocuments(any(AnalysisScope.class), any()))
                .thenReturn(Collections.singletonList(
                        processedDocument(tempDir.resolve("missing.txt").toString())));

        handler.execute(workContext(), payload);

        verify(kafkaOutbox).enqueue(any(IndicatorCalculationTaskMessage.class));
    }

    @Test
    void rejectsNonFileUploadBehaviorTask() throws Exception {
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.KNOWLEDGE_QUERY, Collections.emptyList());
        String payload = codec.encodeBehavior(message);

        assertThrows(IllegalStateException.class,
                () -> handler.execute(workContext(), payload));
        verifyNoInteractions(documentProcessingService, kafkaOutbox);
    }

    @Test
    void scopeValidationFailureStopsPipelineBeforeDocumentProcessing() throws Exception {
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));
        String payload = codec.encodeBehavior(message);
        doThrow(new IllegalStateException("作用域校验失败")).when(scopeValidator)
                .validate(any(AnalysisScope.class), any());

        assertThrows(IllegalStateException.class, () -> handler.execute(workContext(), payload));

        verifyNoInteractions(documentProcessingService, evidenceExtractionService, kafkaOutbox);
    }

    @Test
    void flowContextCarriesMessageIdentityFromPayload() {
        BehaviorProcessingTaskMessage message = scopedMessage();
        String payload = codec.encodeBehavior(message);

        AssessmentFlowContext context = handler.flowContext(workContext(), payload);

        assertEquals(Long.valueOf(10L), context.getProjectId());
        assertEquals(Long.valueOf(20L), context.getAssessmentId());
        assertEquals("run-1", context.getAnalysisRunId());
        assertEquals("msg-1", context.getMessageId());
        assertEquals("trace", context.getTraceId());
        assertEquals("work-1", context.getTaskId());
    }

    private BehaviorProcessingTaskMessage scopedMessage() {
        return BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));
    }

    private ProcessedDocument processedDocument(String path) {
        ProcessedDocument document = new ProcessedDocument();
        document.setInternalFilePath(path);
        return document;
    }

    private DurableWorkContext workContext() {
        DurableWork work = new DurableWork("work-1", "risk-warning-processing",
                BehaviorProcessingWorkHandler.KIND, "msg-1", "{}", 1,
                "worker-1", "lease-1", LocalDateTime.now().plusMinutes(15));
        return new DurableWorkContext(work);
    }
}

