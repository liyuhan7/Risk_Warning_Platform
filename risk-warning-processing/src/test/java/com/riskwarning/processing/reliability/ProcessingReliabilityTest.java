package com.riskwarning.processing.reliability;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.dto.retrieval.RetrievalBatchItem;
import com.riskwarning.common.dto.retrieval.RetrievalBatchRequest;
import com.riskwarning.common.dto.retrieval.RetrievalBatchResponse;
import com.riskwarning.common.dto.retrieval.RetrievalBatchStatus;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.reliability.DurableWork;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.common.reliability.DurableWorkProperties;
import com.riskwarning.common.reliability.DurableWorkStore;
import com.riskwarning.common.reliability.DurableWorker;
import com.riskwarning.common.reliability.DurableWorkHandler;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.common.reliability.KafkaOutboxCodec;
import com.riskwarning.common.reliability.KafkaOutboxHandler;
import com.riskwarning.common.reliability.TransactionalKafkaOutbox;
import com.riskwarning.processing.client.RetrievalClient;
import com.riskwarning.processing.config.P2ProcessingProperties;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import com.riskwarning.processing.repository.RetrievalAuditRepository;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.BehaviorProcessingService;
import com.riskwarning.processing.service.BehaviorQueryTextBuilder;
import com.riskwarning.processing.service.EvidenceQueryService;
import com.riskwarning.processing.service.P2CompletionService;
import com.riskwarning.processing.service.P2RetrievalProcessingService;
import com.riskwarning.processing.service.RetrievalAuditService;
import com.riskwarning.processing.task.BehaviorProcessingWorkHandler;
import com.riskwarning.processing.task.IndicatorCalculationWorkHandler;
import com.riskwarning.processing.task.ProcessingMessageCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R1-03 Step F：真实 PostgreSQL + Kafka 验证 processing 可靠链。
 * 检索超时运行保持 RUNNING 且保留失败审计；重试耗尽 Work 与 Run 同步 FAILED；
 * Behavior 重试指标任务 messageId 稳定且单一入箱；P2 完成事务原子并保留 Outbox。
 */
@EnabledIfEnvironmentVariable(named = "R1_03_IT_ENABLED", matches = "true")
class ProcessingReliabilityTest {

    private static final long PROJECT_ID = 920_777L;
    private static final long ASSESSMENT_ID = 920_778L;
    private static final String NAMESPACE = "r103-proc-it";

    private static ReliabilityPostgresEnv ENV;
    private static ReliabilityKafkaEnv kafkaEnv;
    private static AnnotationConfigApplicationContext context;
    private static DurableWorkStore completingStore;

    private String analysisRunId;

    @BeforeAll
    static void startEnvironment() throws Exception {
        ENV = ReliabilityPostgresEnv.start();
        kafkaEnv = ReliabilityKafkaEnv.start();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        completingStore = context.getBean("completingStore", DurableWorkStore.class);
    }

    @AfterAll
    static void stopEnvironment() {
        context.close();
        kafkaEnv.stop();
    }

    @AfterEach
    void cleanup() {
        ENV.getJdbcTemplate().update("DELETE FROM t_retrieval_audit WHERE assessment_id = ?", ASSESSMENT_ID);
        ENV.getJdbcTemplate().update("DELETE FROM t_analysis_run WHERE assessment_id = ?", ASSESSMENT_ID);
        ENV.getJdbcTemplate().update("DELETE FROM t_assessment_result WHERE id = ?", ASSESSMENT_ID);
        ENV.getJdbcTemplate().update(
                "DELETE FROM t_durable_work WHERE task_key LIKE ?", "r103-%");
        Mockito.reset(context.getBean(RetrievalClient.class),
                context.getBean(BehaviorDocumentRepository.class));
        TestConfig.completeFailures.set(0);
    }

    @Test
    void retrievalTimeoutKeepsRunRunningWithFailedAudit() {
        seedRun();
        seedBehavior();
        when(retrievalClient().retrieve(any(RetrievalBatchRequest.class)))
                .thenThrow(new IllegalStateException("retrieval timeout"));

        IndicatorCalculationWorkHandler handler = indicatorHandler();
        assertThrows(IllegalStateException.class, () -> handler.execute(
                new DurableWorkContext(fakeWork()), encodeIndicator(indicatorMessage())));

        // 临时检索故障不改变运行状态，只保留失败审计供诊断
        assertEquals(AnalysisRunStatus.RUNNING, runStatus());
        assertEquals(1L, auditCount("FAILED"));
    }

    @Test
    void exhaustedRetriesMarkWorkAndRunFailed() throws InterruptedException {
        seedRun();
        seedBehavior();
        when(retrievalClient().retrieve(any(RetrievalBatchRequest.class)))
                .thenThrow(new IllegalStateException("retrieval down"));

        IndicatorCalculationWorkHandler handler = indicatorHandler();
        IndicatorCalculationTaskMessage message = indicatorMessage();
        completingStore.enqueue(IndicatorCalculationWorkHandler.KIND,
                message.getMessageId(), encodeIndicator(message));
        DurableWork work = completingStore.claim(IndicatorCalculationWorkHandler.KIND, "worker-1");

        // 单次 execute 只执行一次尝试：循环领取直到任务进入终态
        DurableWorkProperties properties = exhaustionProperties();
        DurableWorker worker = new DurableWorker(completingStore,
                Collections.<DurableWorkHandler>emptyList(), properties, new AssessmentFlowLogger());
        invokeExecute(worker, handler, work);
        for (int i = 0; i < 30 && !"FAILED".equals(workState(message.getMessageId())); i++) {
            DurableWork next = completingStore.claim(IndicatorCalculationWorkHandler.KIND, "worker-retry");
            if (next != null) {
                invokeExecute(worker, handler, next);
            } else {
                Thread.sleep(100);
            }
        }

        // 重试耗尽：Durable Work 与 AnalysisRun 同时进入 FAILED
        assertEquals("FAILED", workState(message.getMessageId()));
        assertEquals(AnalysisRunStatus.FAILED, runStatus());
    }

    @Test
    void behaviorRetryKeepsIndicatorMessageIdStable() throws InterruptedException {
        // 模拟第一次发送成功但 worker 状态更新前崩溃：complete 失败一次
        TestConfig.completeFailures.set(1);
        seedRun();
        BehaviorProcessingTaskMessage behaviorMessage = BehaviorProcessingTaskMessage.forDocuments(
                "r103-b-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                String.valueOf(System.currentTimeMillis()), "trace-r103", 8L,
                PROJECT_ID, ASSESSMENT_ID, analysisRunId,
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(1L, "/tmp/doc.pdf")));
        String payload = codec().encodeBehavior(behaviorMessage);

        completingStore.enqueue(BehaviorProcessingWorkHandler.KIND,
                behaviorMessage.getMessageId(), payload);
        DurableWork claimed = completingStore.claim(BehaviorProcessingWorkHandler.KIND, "worker-1");
        DurableWorker worker = new DurableWorker(completingStore,
                Collections.<DurableWorkHandler>emptyList(), workerProperties(),
                new AssessmentFlowLogger());
        invokeExecute(worker, behaviorHandler(), claimed);

        // complete 首次失败不得误走业务 retry；指标任务已入箱，原任务保持 RUNNING，
        // lease 到期后接管重放，稳定 taskKey 保证下游仅一条逻辑任务。
        assertEquals("RUNNING", workState(behaviorMessage.getMessageId()));
        ENV.getJdbcTemplate().update("UPDATE t_durable_work SET lease_until = LOCALTIMESTAMP - INTERVAL '1 second' "
                + "WHERE namespace = ? AND task_key = ?", NAMESPACE, behaviorMessage.getMessageId());
        DurableWork retry = claimWithin(BehaviorProcessingWorkHandler.KIND, 5);
        invokeExecute(worker, behaviorHandler(), retry);
        assertEquals("DONE", workState(behaviorMessage.getMessageId()));

        String indicatorMessageId = behaviorMessage.getMessageId() + ":indicator";
        assertEquals(1L, workRowCount(indicatorMessageId), "重试不得重复入箱指标任务");
        String storedPayload = storedPayload(indicatorMessageId);
        assertTrue(storedPayload.contains(indicatorMessageId));
    }

    @Test
    void p2CompletionCommitsRunAndNotificationOutbox() throws Exception {
        seedRun();
        seedBehavior();
        RetrievalBatchItem item = RetrievalBatchItem.builder()
                .behaviorId("beh-1").status(RetrievalBatchStatus.SUCCESS)
                .embeddingModel("test-model").embeddingVersion("1")
                .candidates(new ArrayList<>()).build();
        when(retrievalClient().retrieve(any(RetrievalBatchRequest.class)))
                .thenReturn(RetrievalBatchResponse.builder()
                        .items(Arrays.asList(item)).build());

        IndicatorCalculationWorkHandler handler = indicatorHandler();
        handler.execute(new DurableWorkContext(fakeWork()),
                encodeIndicator(indicatorMessage()));
        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, runStatus());

        // Kafka 发送由 Outbox 任务异步执行：完成事务只保证通知持久化
        String notificationId = analysisRunId + ":p2-completed-notification";
        assertEquals(1L, workRowCount(notificationId), "完成通知必须已入箱");

        KafkaConsumer<String, String> consumer = kafkaEnv.stringConsumer("r103-proc-" + analysisRunId);
        consumer.subscribe(Arrays.asList("notification_tasks"));
        DurableWork outboxWork = completingStore.claim(KafkaOutboxHandler.KIND, "worker-send");
        new KafkaOutboxHandler(kafkaEnv.kafkaUtils(), new KafkaOutboxCodec())
                .execute(new DurableWorkContext(outboxWork), outboxWork.getPayload());
        int matched = waitForMessages(consumer, notificationId, 1, 30);
        assertEquals(1, matched, "恢复发送后必须收到同一稳定 messageId 的通知");
        consumer.close();
    }

    // ---------- 支撑 ----------

    private RetrievalClient retrievalClient() {
        return context.getBean(RetrievalClient.class);
    }

    /** 指标任务处理器：真实 P2 链，LEGACY 分支依赖仅以 mock 占位。 */
    private IndicatorCalculationWorkHandler indicatorHandler() {
        return new IndicatorCalculationWorkHandler(codec(),
                context.getBean(P2RetrievalProcessingService.class),
                Mockito.mock(BehaviorProcessingService.class),
                context.getBean(P2ProcessingProperties.class),
                context.getBean(AnalysisRunFailureService.class));
    }

    /** Behavior 链的重依赖以 mock 代替，聚焦重试与 messageId 稳定性。 */
    private BehaviorProcessingWorkHandler behaviorHandler() {
        com.riskwarning.processing.service.DocumentProcessingService documents =
                Mockito.mock(com.riskwarning.processing.service.DocumentProcessingService.class);
        com.riskwarning.processing.service.SourceDocumentScopeValidator validator =
                Mockito.mock(com.riskwarning.processing.service.SourceDocumentScopeValidator.class);
        com.riskwarning.processing.service.EvidenceExtractionService evidence =
                Mockito.mock(com.riskwarning.processing.service.EvidenceExtractionService.class);
        com.riskwarning.processing.service.FactExtractionPipeline facts =
                Mockito.mock(com.riskwarning.processing.service.FactExtractionPipeline.class);
        Mockito.when(documents.processDocuments(any(AnalysisScope.class), any(java.util.List.class)))
                .thenReturn(Collections.emptyList());
        Mockito.when(facts.process(any(AnalysisScope.class), any(java.util.List.class)))
                .thenReturn(Collections.emptyList());
        return new BehaviorProcessingWorkHandler(codec(), documents, validator,
                evidence, facts, context.getBean(KafkaOutbox.class),
                context.getBean(AnalysisRunFailureService.class));
    }

    private ProcessingMessageCodec codec() {
        return new ProcessingMessageCodec(new ObjectMapper());
    }

    private void seedRun() {
        analysisRunId = "r103-run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        // t_analysis_run 存在外键约束，先确保评估行存在
        ENV.getJdbcTemplate().update(
                "INSERT INTO t_assessment_result (id, project_id, source_task_id, assessment_date,"
                        + " status, created_at) VALUES (?, ?, ?, NOW(), '待评估', NOW())"
                        + " ON CONFLICT (id) DO NOTHING",
                ASSESSMENT_ID, PROJECT_ID, "r103-seed-" + analysisRunId);
        TransactionTemplate transactions = context.getBean(TransactionTemplate.class);
        transactions.executeWithoutResult(status -> context.getBean(AnalysisRunRepository.class)
                .save(AnalysisRun.start(new AnalysisScope(PROJECT_ID, ASSESSMENT_ID, analysisRunId),
                        LocalDateTime.now())));
    }

    private void seedBehavior() {
        Behavior behavior = Behavior.builder()
                .id("beh-1").projectId(PROJECT_ID).assessmentId(ASSESSMENT_ID)
                .analysisRunId(analysisRunId).sourceDocumentId(1L)
                .description("测试行为描述").action("采购").object("设备")
                .build();
        when(context.getBean(BehaviorDocumentRepository.class).findByScope(
                PROJECT_ID, ASSESSMENT_ID, analysisRunId))
                .thenReturn(Arrays.asList(behavior));
    }

    private IndicatorCalculationTaskMessage indicatorMessage() {
        return new IndicatorCalculationTaskMessage(
                "r103-i-" + analysisRunId, String.valueOf(System.currentTimeMillis()),
                "trace-" + analysisRunId, 8L, PROJECT_ID, ASSESSMENT_ID, analysisRunId);
    }

    private String encodeIndicator(IndicatorCalculationTaskMessage message) {
        return codec().encodeIndicator(message);
    }

    private AnalysisScope scope() {
        return new AnalysisScope(PROJECT_ID, ASSESSMENT_ID, analysisRunId);
    }

    private AnalysisRunStatus runStatus() {
        return context.getBean(AnalysisRunRepository.class)
                .findByAnalysisRunIdAndAssessmentIdAndProjectId(
                        analysisRunId, ASSESSMENT_ID, PROJECT_ID)
                .orElseThrow(() -> new AssertionError("运行不存在")).getStatus();
    }

    private long auditCount(String retrievalStatus) {
        Long count = ENV.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM t_retrieval_audit WHERE analysis_run_id = ? AND retrieval_status = ?",
                Long.class, analysisRunId, retrievalStatus);
        return count == null ? 0L : count;
    }

    private String workState(String taskKey) {
        return ENV.getJdbcTemplate().queryForObject(
                "SELECT state FROM t_durable_work WHERE namespace = ? AND task_key = ?",
                String.class, NAMESPACE, taskKey);
    }

    private long workRowCount(String taskKey) {
        Long count = ENV.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM t_durable_work WHERE namespace = ? AND task_key = ?",
                Long.class, NAMESPACE, taskKey);
        return count == null ? 0L : count;
    }

    private String storedPayload(String taskKey) {
        return ENV.getJdbcTemplate().queryForObject(
                "SELECT payload FROM t_durable_work WHERE namespace = ? AND task_key = ?",
                String.class, NAMESPACE, taskKey);
    }

    private DurableWorkProperties workerProperties() {
        DurableWorkProperties properties = new DurableWorkProperties();
        properties.setEnabled(true);
        properties.setNamespace(NAMESPACE);
        properties.setLeaseDuration(Duration.ofSeconds(15));
        properties.setRetryDelay(Duration.ofMillis(100));
        properties.setDefaultMaxAttempts(20);
        return properties;
    }

    private DurableWorkProperties exhaustionProperties() {
        DurableWorkProperties properties = workerProperties();
        properties.setDefaultMaxAttempts(2);
        return properties;
    }

    /** 构造最小 workId 身份供 handler 上下文使用。 */
    private DurableWork fakeWork() {
        return new DurableWork("r103-fake-work", NAMESPACE,
                IndicatorCalculationWorkHandler.KIND, "r103-fake-task", "{}", 1, "worker-test", "lease-test", null);
    }

    private void invokeExecute(DurableWorker worker, DurableWorkHandler handler, DurableWork work) {
        // DurableWorker.execute 为包私有，测试通过反射驱动真实执行路径
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "execute", handler, work);
    }

    private DurableWork claimWithin(String kind, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            DurableWork work = completingStore.claim(kind, "worker-retry");
            if (work != null) {
                return work;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("重试任务未回到 READY");
    }

    private int waitForMessages(KafkaConsumer<String, String> consumer, String messageId,
                                int expected, int maxSeconds) {
        int matched = 0;
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (matched < expected && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : records) {
                String value = record.value();
                if (value != null && value.contains("\"messageId\":\"" + messageId + "\"")) {
                    matched++;
                }
            }
        }
        return matched;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T repository(Class<T> repositoryInterface, EntityManagerFactory entityManagerFactory) {
        JpaRepositoryFactory factory = new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
        return factory.getRepository(repositoryInterface);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {

        static final AtomicInteger completeFailures = new AtomicInteger(0);

        @Bean
        public DataSource dataSource() {
            return ENV.getDataSource();
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setPackagesToScan("com.riskwarning.common.po");
            factoryBean.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
            java.util.Map<String, String> properties = new java.util.HashMap<>();
            properties.put("hibernate.hbm2ddl.auto", "none");
            properties.put("hibernate.physical_naming_strategy",
                    "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            properties.put("hibernate.implicit_naming_strategy",
                    "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
            factoryBean.setJpaPropertyMap(properties);
            return factoryBean;
        }

        @Bean
        public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        public AnalysisRunRepository analysisRunRepository(EntityManagerFactory entityManagerFactory) {
            return repository(AnalysisRunRepository.class, entityManagerFactory);
        }

        @Bean
        public RetrievalAuditRepository retrievalAuditRepository(EntityManagerFactory entityManagerFactory) {
            return repository(RetrievalAuditRepository.class, entityManagerFactory);
        }

        @Bean
        public AssessmentFlowLogger flowLogger() {
            return new AssessmentFlowLogger();
        }

        @Bean
        public AnalysisRunFailureService analysisRunFailureService(AnalysisRunRepository repository) {
            return new AnalysisRunFailureService(repository);
        }

        @Bean
        public RetrievalAuditService retrievalAuditService(RetrievalAuditRepository repository) {
            return new RetrievalAuditService(repository);
        }

        @Bean
        public BehaviorQueryTextBuilder behaviorQueryTextBuilder() {
            return new BehaviorQueryTextBuilder();
        }

        @Bean
        public EvidenceQueryService evidenceQueryService() {
            return Mockito.mock(EvidenceQueryService.class);
        }

        @Bean
        public BehaviorDocumentRepository behaviorDocumentRepository() {
            return Mockito.mock(BehaviorDocumentRepository.class);
        }

        @Bean
        public RetrievalClient retrievalClient() {
            return Mockito.mock(RetrievalClient.class);
        }

        @Bean
        public P2ProcessingProperties p2Properties() {
            P2ProcessingProperties properties = new P2ProcessingProperties();
            properties.setMode(P2ProcessingProperties.Mode.P2);
            properties.setBatchSize(8);
            return properties;
        }

        @Bean
        public DurableWorkProperties storeProperties() {
            DurableWorkProperties properties = new DurableWorkProperties();
            properties.setEnabled(true);
            properties.setNamespace(NAMESPACE);
            properties.setLeaseDuration(Duration.ofSeconds(15));
            properties.setRetryDelay(Duration.ofMillis(100));
            properties.setDefaultMaxAttempts(20);
            return properties;
        }

        @Bean
        public DurableWorkStore durableWorkStore(DataSource dataSource,
                                                 PlatformTransactionManager transactionManager,
                                                 DurableWorkProperties storeProperties) {
            return new DurableWorkStore(new JdbcTemplate(dataSource),
                    new TransactionTemplate(transactionManager), storeProperties);
        }

        /** complete 首次失败的包装仓库，模拟发送成功但状态更新前崩溃。 */
        @Bean
        public DurableWorkStore completingStore(DataSource dataSource,
                                                PlatformTransactionManager transactionManager,
                                                DurableWorkProperties storeProperties) {
            DurableWorkStore realStore = new DurableWorkStore(new JdbcTemplate(dataSource),
                    new TransactionTemplate(transactionManager), storeProperties);
            return new DurableWorkStore(new JdbcTemplate(dataSource),
                    new TransactionTemplate(transactionManager), storeProperties) {
                @Override
                public void complete(DurableWork work) {
                    if (completeFailures.getAndDecrement() > 0) {
                        throw new IllegalStateException("worker state update failed");
                    }
                    realStore.complete(work);
                }
            };
        }

        @Bean
        public KafkaOutbox kafkaOutbox(DurableWorkStore durableWorkStore) {
            return new TransactionalKafkaOutbox(durableWorkStore, new KafkaOutboxCodec(), flowLogger());
        }

        @Bean
        public P2CompletionService p2CompletionService(AnalysisRunRepository runs,
                                                       KafkaOutbox outbox,
                                                       AssessmentFlowLogger flowLogger) {
            return new P2CompletionService(runs, outbox, flowLogger);
        }

        @Bean
        public P2RetrievalProcessingService p2RetrievalProcessingService(
                BehaviorDocumentRepository behaviors,
                EvidenceQueryService evidenceQuery,
                RetrievalClient retrievalClient,
                RetrievalAuditService auditService,
                AnalysisRunRepository runs,
                P2CompletionService completion,
                BehaviorQueryTextBuilder queryBuilder,
                P2ProcessingProperties p2Properties,
                AssessmentFlowLogger flowLogger) {
            return new P2RetrievalProcessingService(behaviors, evidenceQuery, retrievalClient,
                    auditService, runs, completion, queryBuilder, p2Properties, flowLogger);
        }

        private static ProcessingMessageCodec codec() {
            return new ProcessingMessageCodec(new ObjectMapper());
        }
    }
}

