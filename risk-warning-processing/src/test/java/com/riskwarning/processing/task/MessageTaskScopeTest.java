package com.riskwarning.processing.task;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.BehaviorProcessingService;
import com.riskwarning.processing.service.DocumentProcessingService;
import com.riskwarning.processing.service.EvidenceExtractionService;
import com.riskwarning.processing.service.FactExtractionPipeline;
import com.riskwarning.processing.service.SourceDocumentScopeValidator;
import com.riskwarning.processing.entity.dto.ProcessedDocument;
import com.riskwarning.common.utils.KafkaUtils;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageTaskScopeTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsCompleteScope() {
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                "message", "timestamp", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.emptyList());

        AnalysisScope scope = MessageTask.requireScope(message);

        assertEquals(new AnalysisScope(10L, 20L, "run-1"), scope);
    }

    @Test
    void rejectsLegacyMessageBeforeDocumentProcessing() {
        BehaviorProcessingTaskMessage legacy = new BehaviorProcessingTaskMessage(
                "message", "timestamp", "trace", 1L, 10L, 20L,
                DataSourceTypeEnum.FILE_UPLOAD, Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> MessageTask.requireScope(legacy));
        assertThrows(IllegalArgumentException.class, () -> MessageTask.requireScope(null));
    }

    @Test
    void preservesScopeInIndicatorMessage() {
        BehaviorProcessingTaskMessage source = new BehaviorProcessingTaskMessage(
                "message", "timestamp", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.emptyList());

        IndicatorCalculationTaskMessage next = MessageTask.createIndicatorMessage(source);

        assertEquals(Long.valueOf(10L), next.getProjectId());
        assertEquals(Long.valueOf(20L), next.getAssessmentId());
        assertEquals("run-1", next.getAnalysisRunId());
        assertEquals("trace", next.getTraceId());
    }

    @Test
    void requiresPersistentIdentityForEverySourceDocument() {
        BehaviorProcessingTaskMessage scoped = BehaviorProcessingTaskMessage.forDocuments(
                "message", "timestamp", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));
        assertEquals(1, MessageTask.requireDocuments(scoped).size());

        assertThrows(IllegalArgumentException.class,
                () -> MessageTask.requireDocuments(new BehaviorProcessingTaskMessage()));
        assertThrows(IllegalArgumentException.class,
                () -> BehaviorProcessingTaskMessage.forDocuments(
                        "message", "timestamp", "trace", 1L, 10L, 20L, "run-1",
                        DataSourceTypeEnum.FILE_UPLOAD,
                        Collections.singletonList(new SourceDocumentRef(null, "source.pdf"))));
    }

    @Test
    void marksIndicatorRunFailedWithoutRethrowingFromConsumerTask() {
        BehaviorProcessingService behaviorProcessingService = mock(BehaviorProcessingService.class);
        AnalysisRunFailureService failureService = mock(AnalysisRunFailureService.class);
        doThrow(new RuntimeException("指标计算异常")).when(behaviorProcessingService)
                .processProjectBehaviors(1L, 10L, 20L, "run-1");
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        try {
            MessageTask task = new MessageTask();
            ReflectionTestUtils.setField(task, "behaviorProcessingService", behaviorProcessingService);
            ReflectionTestUtils.setField(task, "analysisRunFailureService", failureService);
            ReflectionTestUtils.setField(task, "behaviorThreadPoolExecutor", executor);

            task.onMessage(new IndicatorCalculationTaskMessage(
                    "message", "timestamp", "trace", 1L, 10L, 20L, "run-1"));

            verify(failureService, timeout(1000)).markFailed(eq(new AnalysisScope(10L, 20L, "run-1")), any());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void fileUploadUsesFactExtractionPipelineAndAdvancesOnlyAfterSuccess() throws Exception {
        DocumentProcessingService documentService = mock(DocumentProcessingService.class);
        SourceDocumentScopeValidator scopeValidator = mock(SourceDocumentScopeValidator.class);
        EvidenceExtractionService evidenceService = mock(EvidenceExtractionService.class);
        FactExtractionPipeline pipeline = mock(FactExtractionPipeline.class);
        AnalysisRunFailureService failureService = mock(AnalysisRunFailureService.class);
        KafkaUtils kafkaUtils = mock(KafkaUtils.class);
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        Path internalFile = tempDir.resolve("segments.jsonl");
        Files.write(internalFile, Collections.singletonList("{}"));
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
        SourceDocumentRef document = new SourceDocumentRef(101L, "source.pdf");
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "message", "timestamp", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.singletonList(document));
        when(documentService.processDocuments(eq(scope), eq(Collections.singletonList(document))))
                .thenReturn(Collections.singletonList(new ProcessedDocument(101L, internalFile.toString())));
        when(evidenceService.extractAndPersist(eq(scope), any())).thenReturn(Collections.emptyList());
        when(pipeline.process(scope, Collections.singletonList(document))).thenReturn(Collections.emptyList());

        MessageTask task = new MessageTask();
        ReflectionTestUtils.setField(task, "documentProcessingService", documentService);
        ReflectionTestUtils.setField(task, "sourceDocumentScopeValidator", scopeValidator);
        ReflectionTestUtils.setField(task, "evidenceExtractionService", evidenceService);
        ReflectionTestUtils.setField(task, "factExtractionPipeline", pipeline);
        ReflectionTestUtils.setField(task, "analysisRunFailureService", failureService);
        ReflectionTestUtils.setField(task, "fileThreadPoolExecutor", executor);
        ReflectionTestUtils.setField(task, "kafkaUtils", kafkaUtils);
        try {
            task.onMessage(message);

            verify(pipeline, timeout(2000)).process(scope, Collections.singletonList(document));
            verify(kafkaUtils, timeout(2000)).sendMessage(any(IndicatorCalculationTaskMessage.class));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void factExtractionFailureMarksRunFailedAndDoesNotAdvance() throws Exception {
        DocumentProcessingService documentService = mock(DocumentProcessingService.class);
        SourceDocumentScopeValidator scopeValidator = mock(SourceDocumentScopeValidator.class);
        EvidenceExtractionService evidenceService = mock(EvidenceExtractionService.class);
        FactExtractionPipeline pipeline = mock(FactExtractionPipeline.class);
        AnalysisRunFailureService failureService = mock(AnalysisRunFailureService.class);
        KafkaUtils kafkaUtils = mock(KafkaUtils.class);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        CountDownLatch workerCompleted = new CountDownLatch(1);
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor() {
                    @Override
                    public void execute(Runnable task) {
                        super.execute(() -> {
                            try {
                                task.run();
                            } catch (Throwable throwable) {
                                workerFailure.set(throwable);
                            } finally {
                                workerCompleted.countDown();
                            }
                        });
                    }
                };
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        Path internalFile = tempDir.resolve("failed-segments.jsonl");
        Files.write(internalFile, Collections.singletonList("{}"));
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-2");
        SourceDocumentRef document = new SourceDocumentRef(101L, "source.pdf");
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "message", "timestamp", "trace", 1L, 10L, 20L, "run-2",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.singletonList(document));
        when(documentService.processDocuments(eq(scope), eq(Collections.singletonList(document))))
                .thenReturn(Collections.singletonList(new ProcessedDocument(101L, internalFile.toString())));
        when(evidenceService.extractAndPersist(eq(scope), any())).thenReturn(Collections.emptyList());
        when(pipeline.process(scope, Collections.singletonList(document)))
                .thenThrow(new IllegalStateException("事实抽取失败"));

        MessageTask task = new MessageTask();
        ReflectionTestUtils.setField(task, "documentProcessingService", documentService);
        ReflectionTestUtils.setField(task, "sourceDocumentScopeValidator", scopeValidator);
        ReflectionTestUtils.setField(task, "evidenceExtractionService", evidenceService);
        ReflectionTestUtils.setField(task, "factExtractionPipeline", pipeline);
        ReflectionTestUtils.setField(task, "analysisRunFailureService", failureService);
        ReflectionTestUtils.setField(task, "fileThreadPoolExecutor", executor);
        ReflectionTestUtils.setField(task, "kafkaUtils", kafkaUtils);
        try {
            task.onMessage(message);

            assertTrue(workerCompleted.await(2, TimeUnit.SECONDS));
            assertNull(workerFailure.get(), "FILE_UPLOAD 失败终态不得从工作任务重新抛出");
            verify(failureService).markFailed(eq(scope), any());
            verifyNoInteractions(kafkaUtils);
        } finally {
            executor.shutdown();
        }
    }
}
