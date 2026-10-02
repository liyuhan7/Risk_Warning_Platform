package com.riskwarning.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.message.*;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.reliability.*;
import com.riskwarning.report.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离 PostgreSQL 验证报告终态、通知及重复完成事务；报告内容投影在独立测试验证。 */
@EnabledIfSystemProperty(named = "analysis.report.persistence.test", matches = "true")
class ReportCompletionIntegrationTest {
    @Test void rollbackReplayAndConcurrentCompletionPreservePublishedAssessment() throws Exception {
        String namespace = "report_tx_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource admin = dataSource(env("ANALYSIS_TEST_PG_URL", "jdbc:postgresql://localhost:5432/risk_warning_platform"));
        JdbcTemplate adminSql = new JdbcTemplate(admin);
        LocalContainerEntityManagerFactoryBean factory = null;
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            adminSql.execute("CREATE SCHEMA " + namespace);
            DriverManagerDataSource scoped = dataSource(admin.getUrl() + (admin.getUrl().contains("?") ? "&" : "?") + "currentSchema=" + namespace);
            JdbcTemplate sql = new JdbcTemplate(scoped);
            Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            if (!Files.exists(root.resolve("documents/schema.sql"))) root = root.getParent();
            sql.execute(new String(Files.readAllBytes(root.resolve("documents/schema.sql")), StandardCharsets.UTF_8).replace("public.", namespace + "."));
            sql.execute("INSERT INTO t_enterprise(id,name,credit_code) VALUES(1,'integration','integration')");
            sql.execute("INSERT INTO t_project(id,enterprise_id,name) VALUES(1,1,'integration')");
            sql.execute("INSERT INTO t_assessment_result(id,project_id,overall_score,overall_risk_level,details) VALUES(2,1,0.25,'LOW_RISK','{\"published\":\"old\"}')");
            factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(scoped);
            factory.setPackagesToScan("com.riskwarning.common.po.analysis", "com.riskwarning.common.po.indicator", "com.riskwarning.common.po.report");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties properties = new Properties(); properties.setProperty("hibernate.hbm2ddl.auto", "none");
            properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
            properties.setProperty("hibernate.physical_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            properties.setProperty("hibernate.default_schema", namespace); factory.setJpaProperties(properties); factory.afterPropertiesSet();
            EntityManagerFactory emf = factory.getObject(); assertNotNull(emf);
            JpaTransactionManager transactions = new JpaTransactionManager(emf);
            JpaRepositoryFactory repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf));
            AnalysisRunRepository runs = repositories.getRepository(AnalysisRunRepository.class);
            AssessmentRepository assessments = repositories.getRepository(AssessmentRepository.class);
            AnalysisScope scope = new AnalysisScope(1L, 2L, "run");
            new TransactionTemplate(transactions).execute(status -> {
                AnalysisRun old = AnalysisRun.start(new AnalysisScope(1L, 2L, "old"), LocalDateTime.now()); old.succeed(LocalDateTime.now()); runs.saveAndFlush(old);
                AnalysisRun run = AnalysisRun.start(scope, LocalDateTime.now(), AnalysisExecutionMode.REASONING);
                run.freezeBehaviors(Arrays.asList("b"));
                run.prepareReport(new ObjectMapper().createObjectNode().put("completionKind", "FULL").put("decisionCount", 1));
                runs.saveAndFlush(run); return null;
            });
            DurableWorkProperties work = new DurableWorkProperties(); work.setNamespace("controlled-report");
            KafkaOutbox durable = new TransactionalKafkaOutbox(new DurableWorkStore(sql, new TransactionTemplate(transactions), work),
                    new KafkaOutboxCodec(), new AssessmentFlowLogger());
            KafkaOutbox failing = new KafkaOutbox() {
                public void enqueue(Message message) { durable.enqueue(message); throw new IllegalStateException("模拟通知入箱后故障"); }
                public boolean isDurable() { return true; }
            };
            AtomicInteger projections = new AtomicInteger();
            CountDownLatch projecting = new CountDownLatch(1), release = new CountDownLatch(1);
            AssessmentService aggregation = (user, project, assessmentId, runId) -> {
                int invocation = projections.incrementAndGet();
                if (invocation == 2) {
                    projecting.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试等待超时"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
                Assessment assessment = assessments.findById(assessmentId).get(); assessment.setOverallScore(0.9);
                assessment.setOverallRiskLevel(RiskLevelEnum.HIGH_RISK); assessment.setDetails("{\"published\":\"run\"}");
                assessments.saveAndFlush(assessment);
            };
            AssessmentCompletedEventMessage message = new AssessmentCompletedEventMessage("report", LocalDateTime.now().toString(), "trace", 3L, 1L, 2L, "run");
            AnalysisRunCompletionService failed = proxy(runs, aggregation, assessments, failing, transactions);
            assertThrows(IllegalStateException.class, () -> failed.aggregateAndComplete(message, scope));
            assertEquals("RUNNING", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
            assertEquals(0.25, sql.queryForObject("SELECT overall_score FROM t_assessment_result WHERE id=2", Double.class));
            assertEquals("old", sql.queryForObject("SELECT details->>'published' FROM t_assessment_result WHERE id=2", String.class));
            assertEquals(0L, sql.queryForObject("SELECT count(*) FROM t_durable_work", Long.class));
            assertEquals("old", runs.findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(2L, AnalysisRunStatus.SUCCEEDED).get().getAnalysisRunId());
            AnalysisRunCompletionService recovered = proxy(runs, aggregation, assessments, durable, transactions);
            Future<?> first = workers.submit(() -> recovered.aggregateAndComplete(message, scope));
            assertTrue(projecting.await(10, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = workers.submit(() -> { secondStarted.countDown(); recovered.aggregateAndComplete(message, scope); });
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            ProxyFactory failureProxy = new ProxyFactory(new AnalysisRunFailureService(runs)); failureProxy.setProxyTargetClass(true);
            failureProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            AnalysisRunFailureService exhausted = (AnalysisRunFailureService) failureProxy.getProxy();
            Future<?> exhaustion = workers.submit(() -> exhausted.markFailed(scope, LocalDateTime.now()));
            assertTrue(awaitDatabaseLock(sql, namespace, 2), "重复完成与耗尽回调均应等待报告完成持有的数据库锁");
            release.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS); exhaustion.get(10, TimeUnit.SECONDS);
            recovered.aggregateAndComplete(message, scope);
            assertEquals(2, projections.get(), "一次失败投影加一次成功投影；重复完成不执行投影");
            assertEquals("SUCCEEDED", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
            assertEquals("SUCCEEDED", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='old'", String.class));
            assertEquals(0.9, sql.queryForObject("SELECT overall_score FROM t_assessment_result WHERE id=2", Double.class));
            assertEquals(1L, sql.queryForObject("SELECT count(*) FROM t_durable_work", Long.class));
            Message notification = new KafkaOutboxCodec().decode(sql.queryForObject("SELECT payload FROM t_durable_work", String.class));
            assertTrue(notification instanceof NotificationMessage); assertEquals("run", notification.getAnalysisRunId());
            
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
            if (factory != null) factory.destroy();
            if (!namespace.matches("report_tx_[a-f0-9]{32}")) throw new IllegalStateException("测试清理目标不合法");
            adminSql.execute("DROP SCHEMA IF EXISTS " + namespace + " CASCADE");
            assertEquals(0L, adminSql.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?", Long.class, namespace));
        }
    }
    private boolean awaitDatabaseLock(JdbcTemplate sql, String namespace, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long blocked = sql.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                    + " AND wait_event_type='Lock' AND query LIKE ?", Long.class, "%" + namespace + ".t_analysis_run%");
            if (blocked != null && blocked >= expected) return true;
            Thread.sleep(25);
        }
        return false;
    }
    private AnalysisRunCompletionService proxy(AnalysisRunRepository runs, AssessmentService aggregation, AssessmentRepository assessments,
                                                KafkaOutbox outbox, JpaTransactionManager transactions) {
        ProxyFactory proxy = new ProxyFactory(new AnalysisRunCompletionService(runs, aggregation, assessments, outbox, new AssessmentFlowLogger()));
        proxy.setProxyTargetClass(true); proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (AnalysisRunCompletionService) proxy.getProxy();
    }
    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(); dataSource.setUrl(url);
        dataSource.setUsername(env("ANALYSIS_TEST_PG_USER", "postgres")); dataSource.setPassword(env("ANALYSIS_TEST_PG_PASSWORD", "password")); return dataSource;
    }
    private static String env(String key, String fallback) {
        String value = System.getenv(key); return value == null || value.isEmpty() ? fallback : value;
    }
}
