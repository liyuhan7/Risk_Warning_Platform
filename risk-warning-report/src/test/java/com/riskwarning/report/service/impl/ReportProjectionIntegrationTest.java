package com.riskwarning.report.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.message.*;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.po.indicator.*;
import com.riskwarning.common.po.risk.Risk;
import com.riskwarning.common.reliability.*;
import com.riskwarning.report.repository.*;
import com.riskwarning.report.service.*;
import com.riskwarning.report.entity.vo.AssessmentGeneralDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL、ES 与生产报告服务组合验证；只写本次创建的随机测试资源。 */
@EnabledIfSystemProperty(named = "analysis.report.projection.test", matches = "true")
class ReportProjectionIntegrationTest {
    @Test void esFailureAndOutboxRollbackKeepOldReportAndRetryProjectsOnce() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String namespace = "report_es_" + suffix, index = "report_projection_it_" + suffix;
        DriverManagerDataSource admin = dataSource(env("ANALYSIS_TEST_PG_URL", "jdbc:postgresql://localhost:5432/risk_warning_platform"));
        JdbcTemplate adminSql = new JdbcTemplate(admin);
        LocalContainerEntityManagerFactoryBean factory = null;
        ElasticsearchClient es = null; boolean indexCreated = false;
        JdbcTemplate sql = null; String definition = null;
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.exists(root.resolve("documents/schema.sql"))) root = root.getParent();
        try {
            adminSql.execute("CREATE SCHEMA " + namespace);
            DriverManagerDataSource scoped = dataSource(admin.getUrl() + (admin.getUrl().contains("?") ? "&" : "?") + "currentSchema=" + namespace);
            sql = new JdbcTemplate(scoped);
            definition = new String(Files.readAllBytes(root.resolve("documents/schema.sql")), StandardCharsets.UTF_8).replace("public.", namespace + ".");
            sql.execute(definition);
            sql.execute("INSERT INTO t_enterprise(id,name,credit_code) VALUES(1,'integration','integration')");
            sql.execute("INSERT INTO t_project(id,enterprise_id,name) VALUES(1,1,'integration')");
            sql.execute("INSERT INTO t_assessment_result(id,project_id,overall_score,overall_risk_level) VALUES(2,1,0.25,'LOW_RISK')");
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
            IndicatorResultRepository indicators = repositories.getRepository(IndicatorResultRepository.class);
            AnalysisScope scope = new AnalysisScope(1L, 2L, "run"); LocalDateTime now = LocalDateTime.now().withNano(0);
            new TransactionTemplate(transactions).execute(status -> {
                AnalysisRun old = AnalysisRun.start(new AnalysisScope(1L, 2L, "old"), now); old.succeed(now); runs.saveAndFlush(old);
                AnalysisRun run = AnalysisRun.start(scope, now, AnalysisExecutionMode.REASONING);
                run.freezeBehaviors(Arrays.asList("b-risk", "b-safe"));
                run.prepareReport(mapper.createObjectNode().put("completionKind", "FULL").put("decisionCount", 2));
                runs.saveAndFlush(run);
                indicators.saveAndFlush(indicator(scope, "risk", 0.9, true, now));
                indicators.saveAndFlush(indicator(scope, "safe", 0.1, false, now)); return null;
            });
            ElasticSearchConfig configuration = new ElasticSearchConfig();
            ReflectionTestUtils.setField(configuration, "esUris", env("ANALYSIS_TEST_ES_URL", "http://localhost:9200"));
            es = configuration.elasticsearchClient();
            assertFalse(es.indices().exists(r -> r.index(index)).value());
            assertTrue(es.indices().create(r -> r.index(index).settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m.properties("assessmentId", p -> p.long_(l -> l)).properties("analysisRunId", p -> p.keyword(k -> k))
                            .properties("createdAt", p -> p.date(d -> d)).properties("description", p -> p.keyword(k -> k)))).acknowledged());
            indexCreated = true;
            ReportServiceImpl reports = new ReportServiceImpl();
            ReflectionTestUtils.setField(reports, "assessmentRepository", assessments); ReflectionTestUtils.setField(reports, "indicatorResultRepository", indicators);
            ReflectionTestUtils.setField(reports, "analysisRunRepository", runs); ReflectionTestUtils.setField(reports, "esClient", es);
            ReflectionTestUtils.setField(reports, "riskIndex", index);
            AssessmentServiceImpl aggregation = new AssessmentServiceImpl();
            ReflectionTestUtils.setField(aggregation, "reportService", reports); ReflectionTestUtils.setField(aggregation, "assessmentRepository", assessments);
            ReflectionTestUtils.setField(aggregation, "indicatorResultRepository", indicators); ReflectionTestUtils.setField(aggregation, "elasticsearchClient", es);
            ReflectionTestUtils.setField(aggregation, "analysisRunRepository", runs); ReflectionTestUtils.setField(aggregation, "riskIndex", index);
            DurableWorkProperties work = new DurableWorkProperties(); work.setNamespace("controlled-report");
            KafkaOutbox durable = new TransactionalKafkaOutbox(new DurableWorkStore(sql, new TransactionTemplate(transactions), work), new KafkaOutboxCodec(), new AssessmentFlowLogger());
            KafkaOutbox failing = new KafkaOutbox() {
                public void enqueue(Message message) { durable.enqueue(message); throw new IllegalStateException("模拟通知入箱后故障"); }
                public boolean isDurable() { return true; }
            };
            AssessmentCompletedEventMessage message = new AssessmentCompletedEventMessage("report", now.toString(), "trace", 3L, 1L, 2L, "run");
            AnalysisRunCompletionService completion = proxy(runs, aggregation, assessments, durable, transactions);
            es.indices().putSettings(r -> r.index(index).settings(s -> s.blocks(b -> b.write(true))));
            assertThrows(RuntimeException.class, () -> completion.aggregateAndComplete(message, scope));
            assertUnpublished(sql, reports);
            es.indices().putSettings(r -> r.index(index).settings(s -> s.blocks(b -> b.write(false))));
            AnalysisRunCompletionService failingCompletion = proxy(runs, aggregation, assessments, failing, transactions);
            assertThrows(IllegalStateException.class, () -> failingCompletion.aggregateAndComplete(message, scope));
            assertUnpublished(sql, reports);
            es.indices().refresh(r -> r.index(index));
            assertEquals(1L, es.count(r -> r.index(index)).count(), "ES 中间投影已写入，但失败运行不得对外发布");
            completion.aggregateAndComplete(message, scope); completion.aggregateAndComplete(message, scope);
            es.indices().refresh(r -> r.index(index));
            assertEquals(1L, es.count(r -> r.index(index)).count(), "重试覆盖稳定 ID，不生成重复风险");
            List<Risk> risks = es.search(r -> r.index(index).size(10), Risk.class).hits().hits().stream()
                    .map(hit -> hit.source()).collect(java.util.stream.Collectors.toList());
            assertEquals(1, risks.size()); Risk risk = risks.get(0);
            assertEquals("run", risk.getAnalysisRunId()); assertEquals(RiskLevelEnum.HIGH_RISK, risk.getRiskLevel());
            assertEquals(now, risk.getCreatedAt()); assertTrue(risk.getDescription().contains("冻结事实-risk"));
            assertEquals("law", risk.getRelatedIndicators().get(0).getRelatedBehaviors().get(0).getRelatedRegulations().get(0).getRegulationId());
            assertEquals(1, reports.assembleRisk(2L).size());
            // 同一真实查询失败必须传播，恢复索引配置后仍能回读已发布风险。
            ReflectionTestUtils.setField(reports, "riskIndex", index + "_missing");
            try {
                assertThrows(IllegalStateException.class, () -> reports.assembleRisk(2L));
            } finally {
                ReflectionTestUtils.setField(reports, "riskIndex", index);
            }
            assertEquals(1, reports.assembleRisk(2L).size());
            assertEquals("SUCCEEDED", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
            assertEquals("SUCCEEDED", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='old'", String.class));
            assertEquals(0.5, assessments.findById(2L).get().getOverallScore());
            assertEquals(1L, sql.queryForObject("SELECT count(*) FROM t_durable_work", Long.class));
            assertEquals(2, indicators.findByAssessmentIdAndAnalysisRunId(2L, "run").size());
            assertTrue(indicators.findByAssessmentIdAndAnalysisRunId(2L, "run").stream().filter(row -> row.getIndicatorEsId().equals("risk"))
                    .allMatch(row -> Boolean.TRUE.equals(row.getRiskTriggered()) && row.getCalculatedScore() == 0.9));
            AssessmentGeneralDetails detail = com.alibaba.fastjson2.JSON.parseObject(assessments.findById(2L).get().getDetails(), AssessmentGeneralDetails.class);
            assertEquals(2, detail.getIndicatorDistributionVO().getTotalCount()); assertEquals(1, detail.getIndicatorDistributionVO().getRiskTriggeredCount());
            assertEquals(1, detail.getIndicatorDistributionVO().getSafeCount());
            assertNotNull(detail.getAssessmentDetailVO().getAssessmentDate());
            
            verifyPartialBulkFailure(sql, reports, completion, repositories, transactions, es, index, now);
        } finally {
            if (factory != null) factory.destroy();
            // 只清理本次创建的精确目标，先保存受控记录与定义供复现和恢复。
            if (!namespace.matches("report_es_[a-f0-9]{32}") || !index.matches("report_projection_it_[a-f0-9]{32}"))
                throw new IllegalStateException("测试清理目标不合法");
            ObjectNode backup = mapper.createObjectNode(); backup.put("schema", namespace); backup.put("index", index);
            if (definition != null) backup.put("definition", definition);
            if (sql != null && definition != null) {
                for (String table : Arrays.asList("t_enterprise", "t_project", "t_assessment_result", "t_analysis_run", "t_indicator_result", "t_durable_work")) {
                    List<String> rows = sql.queryForList("SELECT row_to_json(r)::text FROM " + table + " r", String.class);
                    com.fasterxml.jackson.databind.node.ArrayNode values = backup.putArray(table);
                    for (String row : rows) values.add(mapper.readTree(row));
                }
            }
            if (es != null && indexCreated) {
                backup.set("risks", mapper.valueToTree(es.search(r -> r.index(index).size(100), Risk.class).hits().hits().stream()
                        .map(hit -> hit.source()).collect(java.util.stream.Collectors.toList())));
            }
            Files.createDirectories(root.resolve("tmp"));
            Files.write(root.resolve("tmp/report-projection-" + suffix + "-backup.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(backup));
            if (es != null && indexCreated) {
                assertTrue(es.indices().delete(r -> r.index(index)).acknowledged()); assertFalse(es.indices().exists(r -> r.index(index)).value());
            }
            if (es != null) es._transport().close();
            adminSql.execute("DROP SCHEMA IF EXISTS " + namespace + " CASCADE");
            assertEquals(0L, adminSql.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?", Long.class, namespace));
        }
    }
    /** ES Bulk 单项失败时，其余文档即使写入也不得发布；修正测试映射后稳定覆盖重试。 */
    private void verifyPartialBulkFailure(JdbcTemplate sql, ReportServiceImpl reports, AnalysisRunCompletionService completion,
                                          JpaRepositoryFactory repositories, JpaTransactionManager transactions,
                                          ElasticsearchClient es, String index, LocalDateTime now) throws Exception {
        sql.execute("INSERT INTO t_assessment_result(id,project_id,overall_score,overall_risk_level) VALUES(3,1,0.25,'LOW_RISK')");
        AnalysisRunRepository runs = repositories.getRepository(AnalysisRunRepository.class);
        IndicatorResultRepository indicators = repositories.getRepository(IndicatorResultRepository.class);
        AnalysisScope scope = new AnalysisScope(1L, 3L, "partial");
        new TransactionTemplate(transactions).execute(status -> {
            AnalysisRun old = AnalysisRun.start(new AnalysisScope(1L, 3L, "old-partial"), now); old.succeed(now); runs.saveAndFlush(old);
            AnalysisRun run = AnalysisRun.start(scope, now, AnalysisExecutionMode.REASONING); run.freezeBehaviors(Arrays.asList("b-risk", "b-other"));
            run.prepareReport(new ObjectMapper().createObjectNode().put("completionKind", "FULL").put("decisionCount", 2)); runs.saveAndFlush(run);
            indicators.saveAndFlush(indicator(scope, "risk", 0.9, true, now));
            IndicatorResult oversized = indicator(scope, "other", 0.9, true, now);
            oversized.getCalculationDetails().getTraces().get(0).getAnalysisResult().setEnterpriseFact(String.join("", Collections.nCopies(12000, "超")));
            indicators.saveAndFlush(oversized); return null;
        });
        AssessmentCompletedEventMessage message = new AssessmentCompletedEventMessage("report-partial", now.toString(), "trace", 3L, 1L, 3L, "partial");
        assertThrows(RuntimeException.class, () -> completion.aggregateAndComplete(message, scope));
        es.indices().refresh(r -> r.index(index));
        assertEquals(1L, es.count(r -> r.index(index).query(q -> q.term(t -> t.field("analysisRunId").value("partial")))).count(),
                "同次 Bulk 中一个文档写入成功，另一个因测试 keyword 映射的长度限制失败");
        assertEquals("RUNNING", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='partial'", String.class));
        assertEquals(0.25, sql.queryForObject("SELECT overall_score FROM t_assessment_result WHERE id=3", Double.class));
        assertEquals(0L, sql.queryForObject("SELECT count(*) FROM t_durable_work WHERE task_key='partial:assessment-completed-notification'", Long.class));
        assertTrue(reports.assembleRisk(3L).isEmpty());
        // 只调整随机测试索引的 keyword 忽略长度；冻结事实、决定和指标记录保持不变。
        es.indices().putMapping(r -> r.index(index).properties("description", p -> p.keyword(k -> k.ignoreAbove(8191))));
        completion.aggregateAndComplete(message, scope); completion.aggregateAndComplete(message, scope);
        es.indices().refresh(r -> r.index(index));
        assertEquals(2L, es.count(r -> r.index(index).query(q -> q.term(t -> t.field("analysisRunId").value("partial")))).count());
        assertEquals(2, reports.assembleRisk(3L).size());
        assertEquals("SUCCEEDED", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='partial'", String.class));
        assertEquals(1L, sql.queryForObject("SELECT count(*) FROM t_durable_work WHERE task_key='partial:assessment-completed-notification'", Long.class));
    }
    private void assertUnpublished(JdbcTemplate sql, ReportServiceImpl reports) {
        assertEquals("RUNNING", sql.queryForObject("SELECT status FROM t_analysis_run WHERE analysis_run_id='run'", String.class));
        assertEquals(0.25, sql.queryForObject("SELECT overall_score FROM t_assessment_result WHERE id=2", Double.class));
        assertEquals(0L, sql.queryForObject("SELECT count(*) FROM t_durable_work", Long.class));
        assertTrue(reports.assembleRisk(2L).isEmpty());
    }
    private AnalysisRunCompletionService proxy(AnalysisRunRepository runs, AssessmentService aggregation, AssessmentRepository assessments,
                                                KafkaOutbox outbox, JpaTransactionManager transactions) {
        ProxyFactory proxy = new ProxyFactory(new AnalysisRunCompletionService(runs, aggregation, assessments, outbox, new AssessmentFlowLogger()));
        proxy.setProxyTargetClass(true); proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (AnalysisRunCompletionService) proxy.getProxy();
    }
    private IndicatorResult indicator(AnalysisScope scope, String id, double score, boolean triggered, LocalDateTime now) {
        String behavior = "b-" + id; String hash = EvidenceChunk.sha256(id); RiskLevelEnum level = triggered ? RiskLevelEnum.HIGH_RISK : RiskLevelEnum.NO_RISK;
        AnalysisResult semantic = AnalysisResult.builder().id("semantic-" + id).schemaVersion("1.1").assessmentId(scope.getAssessmentId()).analysisRunId(scope.getAnalysisRunId())
                .behaviorId(behavior).indicatorId(id).evidenceIds(Arrays.asList("e-" + id)).conditionMet(true).complianceStatus(ComplianceStatus.COMPLIANT)
                .enterpriseFact("冻结事实-" + id).reasoning("冻结判断").regulationIds(Arrays.asList("law")).requirement("冻结要求").build();
        RuleInput input = RuleInput.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId()).analysisRunId(scope.getAnalysisRunId())
                .indicatorId(id).analysisResultId(semantic.getId()).behaviorId(behavior).evidenceIds(semantic.getEvidenceIds()).conditionMet(true).complianceStatus(semantic.getComplianceStatus()).build();
        RuleEvaluationResult decision = RuleEvaluationResult.builder().indicatorId(id).status(RuleEvaluationStatus.DECIDED).inputHash(hash)
                .ruleVersion("r").thresholdVersion("t").calculatedScore(score).maxPossibleScore(1d).riskTriggered(triggered).riskLevel(level).conflict(false).build();
        RetrievalCandidateSnapshot law = RetrievalCandidateSnapshot.builder().name("冻结法规").content("冻结原文").contentHash(EvidenceChunk.sha256("冻结原文"))
                .result(RetrievalResult.builder().candidateType(RetrievalCandidateType.REGULATION).candidateId("law").behaviorId(behavior)
                        .assessmentId(scope.getAssessmentId()).analysisRunId(scope.getAnalysisRunId()).build()).build();
        IndicatorResultDetail detail = IndicatorResultDetail.builder().schemaVersion("1.1").analysisMode("REASONING_RULES_V1")
                .ruleCalculation(RuleCalculationDetail.builder().inputHash(hash).ruleVersion("r").thresholdVersion("t").conflict(false).finalDecision(decision).ruleInputs(Arrays.asList(input)).build())
                .traces(Arrays.asList(AnalysisTrace.builder().behaviorId(behavior).evidenceIds(semantic.getEvidenceIds()).analysisResult(semantic).candidates(Arrays.asList(law)).build())).build();
        return IndicatorResult.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId()).analysisRunId(scope.getAnalysisRunId())
                .indicatorEsId(id).indicatorName("indicator-" + id).indicatorLevel(3).dimension(RiskDimensionEnum.PRODUCT_LEGITIMACY_RISK.getDescription())
                .calculatedScore(score).maxPossibleScore(1d).riskTriggered(triggered).riskLevel(level).riskStatus(IndicatorRiskStatus.EVALUATED)
                .usedCalculationRuleType("BINARY").calculationDetails(detail).calculatedAt(now).createdAt(now).build();
    }
    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(); dataSource.setUrl(url);
        dataSource.setUsername(env("ANALYSIS_TEST_PG_USER", "postgres")); dataSource.setPassword(env("ANALYSIS_TEST_PG_PASSWORD", "password")); return dataSource;
    }
    private static String env(String key, String fallback) { String value = System.getenv(key); return value == null || value.isEmpty() ? fallback : value; }
}
