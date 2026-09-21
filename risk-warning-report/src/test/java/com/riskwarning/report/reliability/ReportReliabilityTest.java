package com.riskwarning.report.reliability;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.reliability.DurableWorkProperties;
import com.riskwarning.common.reliability.DurableWorkStore;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.common.reliability.KafkaOutboxCodec;
import com.riskwarning.common.reliability.TransactionalKafkaOutbox;
import com.riskwarning.report.service.AssessmentService;
import com.riskwarning.report.repository.AnalysisResultRepository;
import com.riskwarning.report.repository.AnalysisRunRepository;
import com.riskwarning.report.repository.AssessmentRepository;
import com.riskwarning.report.repository.IndicatorResultRepository;
import com.riskwarning.report.service.AnalysisRunCompletionService;
import com.riskwarning.report.service.AnalysisRunResultCleanupService;
import com.riskwarning.report.task.ReportMessageCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R1-03 Step G：真实 PostgreSQL 验证报告完成链。
 * cleanup 失败时整体回滚且运行保持 RUNNING；重试成功后运行进入
 * SUCCEEDED、完成通知入箱且只删除被替代运行的结果；重放幂等。
 */
@EnabledIfEnvironmentVariable(named = "R1_03_IT_ENABLED", matches = "true")
class ReportReliabilityTest {

    private static final long PROJECT_ID = 930_888L;
    private static final long ASSESSMENT_ID = 930_889L;

    private static ReliabilityPostgresEnv ENV;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;

    private String analysisRunId;
    private String supersededRunId;

    @BeforeAll
    static void startEnvironment() throws Exception {
        ENV = ReliabilityPostgresEnv.start();
        jdbc = ENV.getJdbcTemplate();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
    }

    @AfterAll
    static void stopEnvironment() {
        context.close();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_indicator_result WHERE assessment_id = ?", ASSESSMENT_ID);
        jdbc.update("DELETE FROM t_analysis_run WHERE assessment_id = ?", ASSESSMENT_ID);
        jdbc.update("DELETE FROM t_assessment_result WHERE id = ?", ASSESSMENT_ID);
        jdbc.update("DELETE FROM t_durable_work WHERE task_key LIKE ?", "r103-rep-%");
    }

    @Test
    void cleanupFailureRollsBackAndRetryCompletesIdempotently() {
        seed();
        String notificationId = analysisRunId + ":assessment-completed-notification";

        // cleanup 首次失败：事务整体回滚，运行保持 RUNNING，通知未入箱
        AnalysisRunCompletionService failing = completionService(new AtomicInteger(1));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> transactions().executeWithoutResult(status ->
                        failing.aggregateAndComplete(eventMessage(), scope())));
        assertEquals(AnalysisRunStatus.RUNNING, runStatus());
        assertEquals(0L, workRowCount(notificationId), "失败不得产生完成通知");
        assertEquals(1L, supersededRowCount(), "回滚不得删除旧结果");
        assertEquals(1L, currentRowCount());

        // 恢复后重试：运行成功、通知入箱、只删除被替代运行的结果
        AnalysisRunCompletionService recovered = completionService(new AtomicInteger(0));
        transactions().executeWithoutResult(status ->
                recovered.aggregateAndComplete(eventMessage(), scope()));
        assertEquals(AnalysisRunStatus.SUCCEEDED, runStatus());
        assertEquals(1L, workRowCount(notificationId));
        assertEquals(0L, supersededRowCount(), "只清理被替代运行的旧结果");
        assertEquals(1L, currentRowCount(), "当前运行结果必须保留");

        // 重放幂等：不重复入箱，不重复删除
        transactions().executeWithoutResult(status ->
                recovered.aggregateAndComplete(eventMessage(), scope()));
        assertEquals(AnalysisRunStatus.SUCCEEDED, runStatus());
        assertEquals(1L, workRowCount(notificationId));
        assertEquals(1L, currentRowCount());
    }

    // ---------- 支撑 ----------

    private TransactionTemplate transactions() {
        return context.getBean(TransactionTemplate.class);
    }

    private void seed() {
        analysisRunId = "r103-rep-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        supersededRunId = "r103-rep-old-" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO t_assessment_result (id, project_id, source_task_id,"
                        + " assessment_date, overall_score, overall_risk_level, status, created_at)"
                        + " VALUES (?, ?, ?, NOW(), 80.5, 'LOW_RISK', '待评估', NOW())"
                        + " ON CONFLICT (id) DO NOTHING",
                ASSESSMENT_ID, PROJECT_ID, "r103-rep-seed-" + analysisRunId);
        seedIndicatorRow(supersededRunId);
        seedIndicatorRow(analysisRunId);
        TransactionTemplate transactions = transactions();
        transactions.executeWithoutResult(status -> context.getBean(AnalysisRunRepository.class)
                .save(AnalysisRun.start(new AnalysisScope(PROJECT_ID, ASSESSMENT_ID, analysisRunId),
                        LocalDateTime.now())));
    }

    private void seedIndicatorRow(String runId) {
        jdbc.update("INSERT INTO t_indicator_result (project_id, assessment_id, analysis_run_id,"
                        + " indicator_es_id, indicator_name, indicator_level, calculated_score,"
                        + " max_possible_score, used_calculation_rule_type)"
                        + " VALUES (?, ?, ?, ?, ?, 1, 60, 100, 'p2_mock_fixture_v1')",
                PROJECT_ID, ASSESSMENT_ID, runId, "ind-" + runId, "测试指标");
    }

    private AnalysisRunCompletionService completionService(AtomicInteger remainingFailures) {
        IndicatorResultRepository indicatorRepository = context.getBean(IndicatorResultRepository.class);
        AnalysisResultRepository analysisResultRepository = context.getBean(AnalysisResultRepository.class);
        AnalysisRunResultCleanupService cleanup =
                new AnalysisRunResultCleanupService(indicatorRepository, null, analysisResultRepository) {
                    @Override
                    public void cleanupAfterSuccess(Long assessmentId, String currentRunId) {
                        // ES 清理在集成测试范围外；仅以 PG 删除验证事务语义
                        if (remainingFailures.getAndDecrement() > 0) {
                            throw new IllegalStateException("cleanup failed once");
                        }
                        indicatorRepository.deleteByAssessmentIdAndAnalysisRunIdNot(
                                assessmentId, currentRunId);
                        analysisResultRepository.deleteByAssessmentIdAndAnalysisRunIdNot(
                                assessmentId, currentRunId);
                    }
                };
        return new AnalysisRunCompletionService(
                context.getBean(AnalysisRunRepository.class),
                Mockito.mock(AssessmentService.class),
                context.getBean(AssessmentRepository.class),
                cleanup,
                context.getBean(KafkaOutbox.class),
                new AssessmentFlowLogger());
    }

    private AssessmentCompletedEventMessage eventMessage() {
        return new AssessmentCompletedEventMessage(
                "r103-rep-" + analysisRunId, String.valueOf(System.currentTimeMillis()),
                "trace-r103", 8L, PROJECT_ID, ASSESSMENT_ID, analysisRunId);
    }

    private com.riskwarning.common.dto.analysis.AnalysisScope scope() {
        return new com.riskwarning.common.dto.analysis.AnalysisScope(
                PROJECT_ID, ASSESSMENT_ID, analysisRunId);
    }

    private com.riskwarning.common.enums.AnalysisRunStatus runStatus() {
        return context.getBean(AnalysisRunRepository.class)
                .findByAnalysisRunIdAndAssessmentIdAndProjectId(
                        analysisRunId, ASSESSMENT_ID, PROJECT_ID)
                .orElseThrow(() -> new AssertionError("运行不存在")).getStatus();
    }

    private long workRowCount(String taskKey) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_durable_work WHERE namespace = ? AND task_key = ?",
                Long.class, TestConfig.NAMESPACE, taskKey);
        return count == null ? 0L : count;
    }

    private long supersededRowCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_indicator_result WHERE assessment_id = ? AND analysis_run_id = ?",
                Long.class, ASSESSMENT_ID, supersededRunId);
        return count == null ? 0L : count;
    }

    private long currentRowCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_indicator_result WHERE assessment_id = ? AND analysis_run_id = ?",
                Long.class, ASSESSMENT_ID, analysisRunId);
        return count == null ? 0L : count;
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

        static final String NAMESPACE = "r103-rep-it";

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
        public AssessmentRepository assessmentRepository(EntityManagerFactory entityManagerFactory) {
            return repository(AssessmentRepository.class, entityManagerFactory);
        }

        @Bean
        public IndicatorResultRepository indicatorResultRepository(EntityManagerFactory entityManagerFactory) {
            return repository(IndicatorResultRepository.class, entityManagerFactory);
        }

        @Bean
        public AnalysisResultRepository analysisResultRepository(EntityManagerFactory entityManagerFactory) {
            return repository(AnalysisResultRepository.class, entityManagerFactory);
        }

        @Bean
        public DurableWorkProperties storeProperties() {
            DurableWorkProperties properties = new DurableWorkProperties();
            properties.setEnabled(true);
            properties.setNamespace(NAMESPACE);
            properties.setLeaseDuration(java.time.Duration.ofSeconds(15));
            properties.setRetryDelay(java.time.Duration.ofMillis(200));
            return properties;
        }

        @Bean
        public DurableWorkStore durableWorkStore(DataSource dataSource,
                                                 PlatformTransactionManager transactionManager,
                                                 DurableWorkProperties storeProperties) {
            return new DurableWorkStore(new JdbcTemplate(dataSource),
                    new TransactionTemplate(transactionManager), storeProperties);
        }

        @Bean
        public KafkaOutbox kafkaOutbox(DurableWorkStore durableWorkStore) {
            return new TransactionalKafkaOutbox(durableWorkStore, new KafkaOutboxCodec(),
                    new AssessmentFlowLogger());
        }
    }
}
