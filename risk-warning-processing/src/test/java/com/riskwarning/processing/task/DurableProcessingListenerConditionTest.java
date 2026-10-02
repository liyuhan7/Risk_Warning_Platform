package com.riskwarning.processing.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.reliability.DurableWorkStore;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.processing.config.AnalysisProcessingProperties;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.AnalysisRunModeResolver;
import com.riskwarning.processing.service.ComplianceAnalysisOrchestrator;
import com.riskwarning.processing.service.DocumentProcessingService;
import com.riskwarning.processing.service.EvidenceExtractionService;
import com.riskwarning.processing.service.FactExtractionPipeline;
import com.riskwarning.processing.service.RetrievalProcessingService;
import com.riskwarning.processing.service.SourceDocumentScopeValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 可靠/兼容消费切换测试：两个 Kafka listener 不得同时存在，
 * 默认配置保持兼容消费者，可靠开关打开后切换为入箱 listener。
 */
class DurableProcessingListenerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DurableWorkStore.class, () -> mock(DurableWorkStore.class))
            .withBean(ProcessingMessageCodec.class)
            .withBean(com.riskwarning.common.observability.AssessmentFlowLogger.class,
                    com.riskwarning.common.observability.AssessmentFlowLogger::new)
            .withBean(DocumentProcessingService.class,
                    () -> mock(DocumentProcessingService.class))
            // mock 实例仍会被 AutowiredAnnotationBeanPostProcessor 注入 @Autowired 字段
            .withBean(com.riskwarning.processing.util.FileGetter.class,
                    () -> mock(com.riskwarning.processing.util.FileGetter.class))
            .withBean(com.riskwarning.processing.util.FileScanner.class,
                    () -> mock(com.riskwarning.processing.util.FileScanner.class))
            .withBean(com.riskwarning.processing.util.ContentExtractor.class,
                    () -> mock(com.riskwarning.processing.util.ContentExtractor.class))
            .withBean(co.elastic.clients.elasticsearch.ElasticsearchClient.class,
                    () -> mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class))
            .withBean(com.riskwarning.processing.repository.IndicatorResultRepository.class,
                    () -> mock(com.riskwarning.processing.repository.IndicatorResultRepository.class))
            .withBean(com.riskwarning.processing.repository.AssessmentRepository.class,
                    () -> mock(com.riskwarning.processing.repository.AssessmentRepository.class))
            .withBean(org.springframework.transaction.support.TransactionTemplate.class,
                    () -> mock(org.springframework.transaction.support.TransactionTemplate.class))
            // RedisUtil 依赖按名称注入的 redisTemplate，注册真实实例避免连锁 mock
            .withBean("redisTemplate", org.springframework.data.redis.core.RedisTemplate.class,
                    () -> mock(org.springframework.data.redis.core.RedisTemplate.class))
            .withBean(com.riskwarning.common.utils.RedisUtil.class,
                    com.riskwarning.common.utils.RedisUtil::new)
            .withBean(SourceDocumentScopeValidator.class,
                    () -> mock(SourceDocumentScopeValidator.class))
            .withBean(AnalysisRunFailureService.class,
                    () -> mock(AnalysisRunFailureService.class))
            .withBean(EvidenceExtractionService.class,
                    () -> mock(EvidenceExtractionService.class))
            .withBean(FactExtractionPipeline.class,
                    () -> mock(FactExtractionPipeline.class))
            .withBean("FileProcessTaskThreadPool", ThreadPoolTaskExecutor.class,
                    ThreadPoolTaskExecutor::new)
            .withBean("BehaviorProcessTaskThreadPool", ThreadPoolTaskExecutor.class,
                    ThreadPoolTaskExecutor::new)
            .withBean(KafkaOutbox.class, () -> mock(KafkaOutbox.class))
            .withBean(RetrievalProcessingService.class,
                    () -> mock(RetrievalProcessingService.class))
            .withBean(AnalysisProcessingProperties.class, AnalysisProcessingProperties::new)
            .withBean(AnalysisRunModeResolver.class, () -> mock(AnalysisRunModeResolver.class))
            .withBean(ComplianceAnalysisOrchestrator.class, () -> mock(ComplianceAnalysisOrchestrator.class))
            .withUserConfiguration(DurableProcessingMessageTask.class, MessageTask.class);

    @Test
    void reliabilityEnabledReplacesCompatListenerWithInboxListener() {
        runner.withPropertyValues("assessment.reliability.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DurableProcessingMessageTask.class);
            assertThat(context).doesNotHaveBean(MessageTask.class);
        });
    }

    @Test
    void defaultConfigurationKeepsCompatListenerOnly() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MessageTask.class);
            assertThat(context).doesNotHaveBean(DurableProcessingMessageTask.class);
        });
    }

    @Test
    void explicitFalseConfigurationKeepsCompatListenerOnly() {
        runner.withPropertyValues("assessment.reliability.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MessageTask.class);
            assertThat(context).doesNotHaveBean(DurableProcessingMessageTask.class);
        });
    }
}
