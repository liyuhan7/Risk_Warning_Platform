package com.riskwarning.processing.analysis;

import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.indicator.*;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.service.RuleEvaluationAuditWriter;
import com.riskwarning.processing.service.ReportPreparationService;
import com.riskwarning.processing.service.AnalysisCompletionPolicy;
import com.riskwarning.common.reliability.*;
import com.riskwarning.common.message.*;
import com.riskwarning.common.observability.AssessmentFlowLogger;
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
import static org.junit.jupiter.api.Assertions.*;

/** 真实 JPA 短事务验证；只创建和清理本测试的随机 schema，不访问业务表。 */
@EnabledIfSystemProperty(named = "analysis.persistence.test", matches = "true")
class RulePersistenceIntegrationTest {
    @Test void auditAndIndicatorAreAtomicAndReplayDoesNotOverwrite() throws Exception {
        String namespace = "analysis_tx_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource admin = dataSource(env("ANALYSIS_TEST_PG_URL", "jdbc:postgresql://localhost:5432/risk_warning_platform"));
        JdbcTemplate adminSql = new JdbcTemplate(admin);
        LocalContainerEntityManagerFactoryBean factory = null;
        try {
            adminSql.execute("CREATE SCHEMA " + namespace);
            DriverManagerDataSource scoped = dataSource(admin.getUrl() + (admin.getUrl().contains("?") ? "&" : "?") + "currentSchema=" + namespace);
            JdbcTemplate sql = new JdbcTemplate(scoped);
            Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            if (!Files.exists(root.resolve("documents/schema.sql"))) root = root.getParent();
            sql.execute(new String(Files.readAllBytes(root.resolve("documents/schema.sql")), StandardCharsets.UTF_8).replace("public.", namespace + "."));
            sql.execute("INSERT INTO t_enterprise(id,name,credit_code) VALUES(1,'integration','integration')");
            sql.execute("INSERT INTO t_project(id,enterprise_id,name) VALUES(1,1,'integration')");
            sql.execute("INSERT INTO t_assessment_result(id,project_id) VALUES(2,1)");
            factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(scoped);
            factory.setPackagesToScan("com.riskwarning.common.po.analysis", "com.riskwarning.common.po.indicator");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties properties = new Properties(); properties.setProperty("hibernate.hbm2ddl.auto", "none");
            properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
            properties.setProperty("hibernate.default_schema", namespace); factory.setJpaProperties(properties); factory.afterPropertiesSet();
            EntityManagerFactory emf = factory.getObject(); assertNotNull(emf);
            JpaTransactionManager transactions = new JpaTransactionManager(emf);
            JpaRepositoryFactory repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf));
            AnalysisRunRepository runs = repositories.getRepository(AnalysisRunRepository.class);
            RuleEvaluationAuditRepository audits = repositories.getRepository(RuleEvaluationAuditRepository.class);
            IndicatorResultRepository results = repositories.getRepository(IndicatorResultRepository.class);
            AnalysisScope scope = new AnalysisScope(1L, 2L, "run"); LocalDateTime now = LocalDateTime.now();
            String hash = String.join("", Collections.nCopies(64, "a"));
            new TransactionTemplate(transactions).execute(status -> {
                AnalysisRun run = AnalysisRun.start(scope, now, AnalysisExecutionMode.REASONING);
                run.freezeBehaviors(Arrays.asList("b")); run.freezeBehaviorInput(hash); runs.saveAndFlush(run); return null;
            });
            ProxyFactory proxy = new ProxyFactory(new RuleEvaluationAuditWriter(runs, audits, results));
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            RuleEvaluationAuditWriter writer = (RuleEvaluationAuditWriter) proxy.getProxy();
            RuleEvaluationResult decision = RuleEvaluationResult.builder().indicatorId("i").inputHash(hash).status(RuleEvaluationStatus.DECIDED)
                    .ruleType(RuleTypeEnum.BINARY).ruleVersion("v1").thresholdVersion("t1").aggregationOperator(AggregationOperator.ALL)
                    .calculatedScore(1d).maxPossibleScore(1d).riskTriggered(false).riskLevel(RiskLevelEnum.NO_RISK).decision("NO_RISK").conflict(false).build();
            RuleCalculationDetail calculation = RuleCalculationDetail.builder().inputHash(hash).ruleVersion("v1").thresholdVersion("t1")
                    .aggregationOperator("ALL").conflict(false).finalDecision(decision).ruleInputs(Arrays.asList(RuleInput.builder()
                            .projectId(1L).assessmentId(2L).analysisRunId("run").indicatorId("i").behaviorId("b").analyzedAt(now).build())).build();
            IndicatorResultDetail detail = IndicatorResultDetail.builder().schemaVersion("1.1").analysisMode("REASONING_RULES_V1").ruleCalculation(calculation).build();
            RuleEvaluationAudit audit = RuleEvaluationAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").indicatorId("i")
                    .inputHash(hash).ruleVersion("v1").thresholdVersion("t1").aggregationOperator("ALL").status(RuleEvaluationStatus.DECIDED)
                    .conflict(false).calculationDetails(detail).createdAt(now).updatedAt(now).build();
            IndicatorResult result = IndicatorResult.builder().projectId(1L).assessmentId(2L).analysisRunId("run").indicatorEsId("i")
                    .indicatorLevel(3).calculatedScore(1d).maxPossibleScore(1d).usedCalculationRuleType("BINARY").calculationDetails(detail)
                    .riskTriggered(false).riskLevel(RiskLevelEnum.NO_RISK).riskStatus(IndicatorRiskStatus.EVALUATED).calculatedAt(now).createdAt(now).build();
            // 指标名称缺失，在审计已 flush 后失败，验证整个事务回滚而不是只检查异常。
            assertThrows(RuntimeException.class, () -> writer.save(scope, audit, result));
            assertEquals(0L, sql.queryForObject("SELECT count(*) FROM t_rule_evaluation_audit", Long.class));
            assertEquals(0L, sql.queryForObject("SELECT count(*) FROM t_indicator_result", Long.class));
            audit.setId(null); result.setId(null); result.setIndicatorName("controlled indicator");
            assertTrue(writer.save(scope, audit, result));
            assertEquals(1L, sql.queryForObject("SELECT count(*) FROM t_rule_evaluation_audit", Long.class));
            assertEquals(1L, sql.queryForObject("SELECT count(*) FROM t_indicator_result", Long.class));
            assertFalse(writer.save(scope, audit, result));
            assertEquals("RUNNING", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
            assertEquals(hash, sql.queryForObject("SELECT input_hash FROM t_rule_evaluation_audit", String.class));
            assertEquals("NO_RISK", sql.queryForObject("SELECT risk_level FROM t_indicator_result", String.class));
            assertEquals(hash, runs.findById("run").get().getBehaviorInputHash());
            verifyReportPreparation(repositories, transactions, sql, runs, scope, now);
        } finally {
            if (factory != null) factory.destroy();
            if (!namespace.matches("analysis_tx_[a-f0-9]{32}")) throw new IllegalStateException("测试清理目标不合法");
            adminSql.execute("DROP SCHEMA IF EXISTS " + namespace + " CASCADE");
            assertEquals(0L, adminSql.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?", Long.class, namespace));
        }
    }

    /** 在真实完成策略、JPA 和 JDBC Outbox 之间验证同一个数据库事务。 */
    private void verifyReportPreparation(JpaRepositoryFactory repositories, JpaTransactionManager transactions,
                                         JdbcTemplate sql, AnalysisRunRepository runs, AnalysisScope scope, LocalDateTime now) {
        RetrievalAuditRepository retrievals = repositories.getRepository(RetrievalAuditRepository.class);
        AnalysisResultRepository semanticResults = repositories.getRepository(AnalysisResultRepository.class);
        new TransactionTemplate(transactions).execute(status -> {
            retrievals.saveAndFlush(RetrievalAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").behaviorId("b")
                    .queryText("controlled fact").queryTemplateVersion("v1").filterVersion("v1").filterEnabled(false)
                    .filterApplied(false).filterExpression("{}").retrievalStatus(RetrievalAuditStatus.SUCCESS)
                    .analysisStatus(AnalysisAuditStatus.ANALYZED).candidates(Collections.emptyList()).createdAt(now).updatedAt(now).build());
            semanticResults.saveAndFlush(AnalysisResult.builder().id("semantic").schemaVersion("1.1").assessmentId(2L)
                    .analysisRunId("run").behaviorId("b").indicatorId("i").evidenceIds(Arrays.asList("e"))
                    .applicable(Applicability.APPLICABLE).enterpriseFact("controlled fact").complianceStatus(ComplianceStatus.COMPLIANT)
                    .conditionMet(true).confidence(1d).reasoning("controlled reasoning").modelVersion("controlled")
                    .promptVersion("v1").analyzedAt(now).build());
            return null;
        });
        DurableWorkProperties properties = new DurableWorkProperties(); properties.setNamespace("controlled-report");
        KafkaOutbox durable = new TransactionalKafkaOutbox(new DurableWorkStore(sql, new TransactionTemplate(transactions), properties),
                new KafkaOutboxCodec(), new AssessmentFlowLogger());
        KafkaOutbox failing = new KafkaOutbox() {
            public void enqueue(Message message) {
                durable.enqueue(message);
                throw new IllegalStateException("模拟入箱后故障");
            }
            public boolean isDurable() { return true; }
        };
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage("m", now.toString(), "trace", 3L, 1L, 2L, "run");
        ReportPreparationService failedPreparation = preparationProxy(repositories, transactions, failing);
        assertThrows(IllegalStateException.class, () -> failedPreparation.prepare(message, scope, false));
        assertEquals(0L, sql.queryForObject("SELECT count(*) FROM t_durable_work", Long.class));
        assertNull(sql.queryForObject("SELECT analysis_summary::text FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
        assertEquals("RUNNING", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
        ReportPreparationService preparation = preparationProxy(repositories, transactions, durable);
        preparation.prepare(message, scope, false);
        preparation.prepare(message, scope, false);
        assertEquals(1L, sql.queryForObject("SELECT count(*) FROM t_durable_work", Long.class));
        assertEquals("FULL", sql.queryForObject("SELECT analysis_summary->>'completionKind' FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
        assertEquals("RUNNING", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
        Message report = new KafkaOutboxCodec().decode(sql.queryForObject("SELECT payload FROM t_durable_work", String.class));
        assertTrue(report instanceof AssessmentCompletedEventMessage);
        assertEquals(scope.getAnalysisRunId(), report.getAnalysisRunId());
        assertEquals(scope.getAssessmentId(), report.getAssessmentId());
        assertEquals(scope.getProjectId(), report.getProjectId());
    }

    private ReportPreparationService preparationProxy(JpaRepositoryFactory repositories, JpaTransactionManager transactions, KafkaOutbox outbox) {
        ReportPreparationService target = new ReportPreparationService(repositories.getRepository(AnalysisRunRepository.class),
                repositories.getRepository(RetrievalAuditRepository.class), repositories.getRepository(AnalysisResultRepository.class),
                repositories.getRepository(AnalysisAttemptRepository.class), repositories.getRepository(RuleEvaluationAuditRepository.class),
                repositories.getRepository(IndicatorResultRepository.class), new AnalysisCompletionPolicy(), outbox);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ReportPreparationService) proxy.getProxy();
    }
    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(); dataSource.setUrl(url);
        dataSource.setUsername(env("ANALYSIS_TEST_PG_USER", "postgres")); dataSource.setPassword(env("ANALYSIS_TEST_PG_PASSWORD", "password"));
        return dataSource;
    }
    private static String env(String key, String fallback) {
        String value = System.getenv(key); return value == null || value.isEmpty() ? fallback : value;
    }
}
