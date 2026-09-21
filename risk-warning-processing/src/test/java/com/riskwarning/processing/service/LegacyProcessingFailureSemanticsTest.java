package com.riskwarning.processing.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.processing.repository.AssessmentRepository;
import com.riskwarning.processing.repository.IndicatorResultRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * LEGACY 失败语义测试：向量缺失、候选检索失败、0 结果
 * 都必须在完成事件之前向上抛出，交由 Durable Work retry。
 * 取数与候选检索通过 spy 替换，避免依赖外部 ES。
 */
class LegacyProcessingFailureSemanticsTest {

    private static final Long USER_ID = 5L;
    private static final Long PROJECT_ID = 10L;
    private static final Long ASSESSMENT_ID = 20L;
    private static final String RUN_ID = "run-1";
    private static final String INDICATOR_MESSAGE_ID = "msg-indicator";

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private BehaviorProcessingService wiredService() {
        BehaviorProcessingService service = spy(new BehaviorProcessingService());
        ReflectionTestUtils.setField(service, "esClient", mock(ElasticsearchClient.class));
        ReflectionTestUtils.setField(service, "indicatorResultRepository",
                mock(IndicatorResultRepository.class));
        ReflectionTestUtils.setField(service, "assessmentRepository",
                mock(AssessmentRepository.class));
        ReflectionTestUtils.setField(service, "kafkaOutbox", mock(KafkaOutbox.class));
        ReflectionTestUtils.setField(service, "objectMapper",
                new com.fasterxml.jackson.databind.ObjectMapper());
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.initialize();
        ReflectionTestUtils.setField(service, "behaviorThreadPoolExecutor", executor);
        doReturn(mock(ElasticsearchIndicesClient.class))
                .when((ElasticsearchClient) ReflectionTestUtils.getField(service, "esClient"))
                .indices();
        whenAssessmentExists(service);
        return service;
    }

    private void whenAssessmentExists(BehaviorProcessingService service) {
        AssessmentRepository repository =
                (AssessmentRepository) ReflectionTestUtils.getField(service, "assessmentRepository");
        org.mockito.Mockito.when(repository.findById(ASSESSMENT_ID))
                .thenReturn(Optional.of(Assessment.builder().build()));
    }

    private Behavior vectorizedBehavior(String behaviorId) {
        return Behavior.builder()
                .id(behaviorId)
                .projectId(PROJECT_ID)
                .assessmentId(ASSESSMENT_ID)
                .analysisRunId(RUN_ID)
                .description("公司留存采购审批意见")
                .descriptionVector(Arrays.asList(0.1F, 0.2F, 0.3F))
                .build();
    }

    private KafkaOutbox outboxOf(BehaviorProcessingService service) {
        return (KafkaOutbox) ReflectionTestUtils.getField(service, "kafkaOutbox");
    }

    private IndicatorResultRepository resultsOf(BehaviorProcessingService service) {
        return (IndicatorResultRepository) ReflectionTestUtils.getField(service,
                "indicatorResultRepository");
    }

    @Test
    void missingBehaviorVectorFailsBeforeDispatchWithoutCompletionEvent() throws IOException {
        BehaviorProcessingService service = wiredService();
        doReturn(Arrays.asList(Behavior.builder().id("b-no-vector")
                .description("缺少向量").build()))
                .when(service).fetchBehaviors(PROJECT_ID, ASSESSMENT_ID, RUN_ID);

        assertThrows(IllegalStateException.class, () -> service.processProjectBehaviors(
                USER_ID, PROJECT_ID, ASSESSMENT_ID, RUN_ID, INDICATOR_MESSAGE_ID));

        verify(outboxOf(service), never()).enqueue(any(AssessmentCompletedEventMessage.class));
        verify(resultsOf(service), never()).saveAllAndFlush(any());
    }

    @Test
    void candidateFetchFailurePropagatesWithoutCompletionEvent() throws IOException {
        BehaviorProcessingService service = wiredService();
        doReturn(Collections.singletonList(vectorizedBehavior("b-1")))
                .when(service).fetchBehaviors(PROJECT_ID, ASSESSMENT_ID, RUN_ID);
        // 候选检索在行为线程内失败后汇总上抛
        doThrow(new IllegalStateException("es query boom"))
                .when(service).fetchTopIndicators(any(Behavior.class), org.mockito.ArgumentMatchers.anyInt());

        assertThrows(IllegalStateException.class, () -> service.processProjectBehaviors(
                USER_ID, PROJECT_ID, ASSESSMENT_ID, RUN_ID, INDICATOR_MESSAGE_ID));

        verify(outboxOf(service), never()).enqueue(any(AssessmentCompletedEventMessage.class));
        verify(resultsOf(service), never()).saveAllAndFlush(any());
    }

    @Test
    void zeroMappingResultsFailWithoutCompletionEvent() throws IOException {
        BehaviorProcessingService service = wiredService();
        doReturn(Collections.singletonList(vectorizedBehavior("b-1")))
                .when(service).fetchBehaviors(PROJECT_ID, ASSESSMENT_ID, RUN_ID);
        // 候选为真实空命中：计算后 0 个指标结果，禁止发送完成事件
        doReturn(Collections.emptyList())
                .when(service).fetchTopIndicators(any(Behavior.class),
                        org.mockito.ArgumentMatchers.anyInt());
        doReturn(Collections.emptyList())
                .when(service).fetchTopRegulations(any(Behavior.class),
                        org.mockito.ArgumentMatchers.anyInt());

        assertThrows(IllegalStateException.class, () -> service.processProjectBehaviors(
                USER_ID, PROJECT_ID, ASSESSMENT_ID, RUN_ID, INDICATOR_MESSAGE_ID));

        verify(outboxOf(service), never()).enqueue(any(AssessmentCompletedEventMessage.class));
        verify(resultsOf(service), never()).saveAllAndFlush(any());
    }
}
