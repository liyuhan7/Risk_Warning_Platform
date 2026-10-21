package com.riskwarning.processing.rule.v2;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.RequirementFinding.ConditionValue;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.service.scoring.*;
import com.riskwarning.processing.service.scoring.fact.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.riskwarning.processing.service.AnalysisContextService;
import com.riskwarning.processing.service.ComplianceAuditWriter;
import com.riskwarning.processing.client.ScoringDatasetClient;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.provider.AiChatProvider;
import com.riskwarning.common.reliability.*;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.message.*;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
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
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.riskwarning.processing.rule.v2.ScoringTestData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在独立验证数据库保留评分样本，验证要求表、指标结果与 ES 查询中的业务分数一致。 */
@EnabledIfSystemProperty(named = "scoring.integration.env", matches = ".+")
class RequirementPersistenceIntegrationTest {
    @Test void requirementScoresSurviveDatabaseAndSearchProjection() throws Exception {
        Properties settings = new Properties();
        try (Reader reader = Files.newBufferedReader(Paths.get(System.getProperty("scoring.integration.env")), StandardCharsets.UTF_8)) {
            settings.load(reader);
        }
        String url = settings.getProperty("SCORING_PG_URL");
        assertTrue(url.endsWith("/scoring_verification"), "集成测试只允许访问独立验证数据库");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String namespace = "scoring_v2_" + suffix;
        String index = "scoring_test_" + suffix;
        DriverManagerDataSource admin = dataSource(settings, url);
        new JdbcTemplate(admin).execute("CREATE SCHEMA " + namespace);
        JdbcTemplate sql = new JdbcTemplate(dataSource(settings, url + "?currentSchema=" + namespace));
        Path root = Files.exists(Paths.get("documents/schema.sql")) ? Paths.get("").toAbsolutePath() : Paths.get("..").toAbsolutePath();
        sql.execute(read(root.resolve("documents/schema.sql")).replace("public.", namespace + "."));
        sql.execute("INSERT INTO t_enterprise(id,name,credit_code) VALUES(1,'评分业务验证','scoring-verification')");
        sql.execute("INSERT INTO t_project(id,enterprise_id,name) VALUES(1,1,'评分业务验证')");
        sql.execute("INSERT INTO t_assessment_result(id,project_id) VALUES(2,1)");
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource(settings, url + "?currentSchema=" + namespace));
        factory.setPackagesToScan("com.riskwarning.common.po.analysis", "com.riskwarning.common.po.indicator", "com.riskwarning.common.po.evidence");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties(); properties.setProperty("hibernate.hbm2ddl.auto", "none");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
        properties.setProperty("hibernate.default_schema", namespace); factory.setJpaProperties(properties); factory.afterPropertiesSet();
        try (RestClientTransport transport = new RestClientTransport(RestClient.builder(HttpHost.create(settings.getProperty("SCORING_ES_URL"))).build(), new JacksonJsonpMapper())) {
            EntityManagerFactory emf = factory.getObject(); assertNotNull(emf);
            JpaTransactionManager transactions = new JpaTransactionManager(emf);
            JpaRepositoryFactory repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf));
            AnalysisRunRepository runs = repositories.getRepository(AnalysisRunRepository.class);
            ScoringRunSnapshotRepository snapshots = repositories.getRepository(ScoringRunSnapshotRepository.class);
            AnalysisResultRepository analysis = repositories.getRepository(AnalysisResultRepository.class);
            RequirementEvaluationRepository evaluations = repositories.getRepository(RequirementEvaluationRepository.class);
            RuleEvaluationAuditRepository audits = repositories.getRepository(RuleEvaluationAuditRepository.class);
            IndicatorResultRepository indicators = repositories.getRepository(IndicatorResultRepository.class);
            RetrievalAuditRepository retrievals = repositories.getRepository(RetrievalAuditRepository.class);
            ProxyFactory proxy = new ProxyFactory(new ScoringPersistenceService(runs, snapshots, analysis, evaluations, audits, indicators, retrievals));
            proxy.setProxyTargetClass(true); proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            ScoringPersistenceService writer = (ScoringPersistenceService) proxy.getProxy();
            AnalysisScope scope = new AnalysisScope(1L, 2L, "run-" + suffix);
            LocalDateTime now = LocalDateTime.now();
            new TransactionTemplate(transactions).execute(status -> {
                AnalysisRun run = AnalysisRun.start(scope, now, AnalysisExecutionMode.REASONING);
                run.freezeBehaviors(Collections.singletonList("b")); run.freezeBehaviorInput(ScoringJson.hash("material"));
                runs.saveAndFlush(run); return null;
            });
            ScoringSourceBundle source = bundle("r1", "r2", "r3");
            ObjectNode r1 = requirement("r1", "FORBIDDEN").put("weight", 1.5).put("minimumRiskLevel", "HIGH_RISK");
            ObjectNode r2 = requirement("r2", "FORBIDDEN").put("weight", 1.5);
            ObjectNode r3 = requirement("r3", "REQUIRED").put("weight", 1);
            ScoringRuleRegistry registry = ScoringRuleRegistry.fromJson(registry(source, r1, r2, r3).toString(), true);
            ScoringRunSnapshot snapshot = new ScoringRunSnapshot(); snapshot.setAnalysisRunId(scope.getAnalysisRunId());
            snapshot.setProjectId(1L); snapshot.setAssessmentId(2L); snapshot.setDatasetVersion(source.getDatasetVersion());
            snapshot.setRegistryVersion(registry.version()); snapshot.setSnapshotHash(ScoringJson.hash("snapshot")); snapshot.setCreatedAt(now);
            snapshot.setSourceBundles(Collections.singletonMap("i", source)); snapshot.setRuleDefinitions(Collections.singletonMap("i", registry.require("i", source)));
            snapshot.setIndicatorBehaviors(Collections.singletonMap("i", Collections.singletonList("b"))); writer.freeze(scope, snapshot);
            List<RequirementFinding> facts = Arrays.asList(fact("r1", true, true), fact("r2", false, false), fact("r3", true, false));
            AnalysisResult semantic = AnalysisResult.builder().id("semantic-" + suffix).schemaVersion("1.2")
                    .analysisRunId(scope.getAnalysisRunId()).assessmentId(2L).behaviorId("b").indicatorId("i")
                    .applicable(Applicability.APPLICABLE).complianceStatus(ComplianceStatus.NON_COMPLIANT).gapType(GapType.PROHIBITED_ACTION)
                    .enterpriseFact("一项禁止行为发生，另一禁止项未发生，必要措施已完成").evidenceIds(Collections.singletonList("e"))
                    .requirementFindings(facts).confidence(0.95).reasoning("业务集成样例").modelVersion("controlled-fixture")
                    .promptVersion("compliance-analysis-v1.2").analyzedAt(now).build();
            writer.analysis(scope, semantic);
            ScoringDecision decision = new RequirementScoringEngine(registry, new UniversalConditionEvaluator()).evaluate(scope, "i", source,
                    Collections.singletonMap("b", Collections.singleton("e")), facts, Collections.emptyMap());
            List<AnalysisTrace> traces = Collections.singletonList(AnalysisTrace.builder().behaviorId("b").evidenceIds(Collections.singletonList("e")).analysisResult(semantic).build());
            writer.decision(scope, source, registry.require("i", source), decision, traces);
            writer.decision(scope, source, registry.require("i", source), decision, traces);
            assertEquals(3L, sql.queryForObject("SELECT count(*) FROM t_requirement_evaluation", Long.class));
            assertEquals(1L, sql.queryForObject("SELECT count(*) FROM t_indicator_result", Long.class));
            assertEquals(0, new BigDecimal("62.5").compareTo(sql.queryForObject("SELECT sum(earned) FROM t_requirement_evaluation", BigDecimal.class)));
            assertEquals(62.5, indicators.findByAssessmentIdAndAnalysisRunId(2L, scope.getAnalysisRunId()).get(0).getCalculatedScore());
            assertEquals(RiskLevelEnum.HIGH_RISK, indicators.findByAssessmentIdAndAnalysisRunId(2L, scope.getAnalysisRunId()).get(0).getRiskLevel());
            assertEquals(3, analysis.findByAssessmentIdAndAnalysisRunId(2L, scope.getAnalysisRunId()).get(0).getRequirementFindings().size());
            assertEquals("PROHIBITED_ACTION", sql.queryForObject("SELECT gap_type FROM t_requirement_evaluation WHERE requirement_id='r1'", String.class));
            assertEquals(source.bundleHash(), snapshots.findById(scope.getAnalysisRunId()).get().getSourceBundles().get("i").bundleHash());
            ElasticsearchClient es = new ElasticsearchClient(transport);
            RequirementEvaluationProjection projection = new RequirementEvaluationProjection(es, evaluations, index);
            projection.project(scope); projection.project(scope); es.indices().refresh(request -> request.index(index));
            List<co.elastic.clients.elasticsearch.core.search.Hit<Map>> hits = es.search(request -> request.index(index)
                    .query(query -> query.term(term -> term.field("analysisRunId").value(scope.getAnalysisRunId()))).size(100), Map.class).hits().hits();
            assertEquals(3, hits.size()); BigDecimal earned = BigDecimal.ZERO;
            for (co.elastic.clients.elasticsearch.core.search.Hit<Map> hit : hits) {
                assertEquals(scope.getAnalysisRunId(), hit.source().get("analysisRunId"));
                assertEquals(Collections.singletonList("e"), hit.source().get("evidenceIds"));
                if ("r1".equals(hit.source().get("requirementId"))) assertEquals("PROHIBITED_ACTION", hit.source().get("gapType"));
                earned = earned.add(new BigDecimal(hit.source().get("earned").toString()));
            }
            assertEquals(0, new BigDecimal("62.5").compareTo(earned));
            Map<String, Object> report = new TreeMap<>(); report.put("database", "scoring_verification"); report.put("schema", namespace);
            report.put("index", index); report.put("analysisRunId", scope.getAnalysisRunId()); report.put("requirementRows", 3);
            report.put("esDocuments", hits.size()); report.put("score", decision.getCalculatedScore()); report.put("riskLevel", decision.getRiskLevel().name());
            report.put("replayedWithoutDuplicates", true); report.put("dataRetained", true); report.put("model", "controlled-fixture");
            report.put("scope", "组件级真实 PostgreSQL 与 Elasticsearch；未经过上传或真实模型");
            Path evidence = root.resolve("documents/local/scoring-evidence/persistence-" + suffix + ".json");
            Files.write(evidence, M.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
            verifyReviewedBusinessPipeline(repositories, transactions, sql, es, writer, root, namespace, suffix, false);
            verifyReviewedBusinessPipeline(repositories, transactions, sql, es, writer, root, namespace, suffix, true);
        } finally { factory.destroy(); }
    }

    /** 使用发布后的真实业务规则验证主编排至报告任务；模型响应受控，数据库与 ES 为实际读写。 */
    private void verifyReviewedBusinessPipeline(JpaRepositoryFactory repositories, JpaTransactionManager transactions, JdbcTemplate sql,
            ElasticsearchClient es, ScoringPersistenceService writer, Path root, String namespace, String suffix, boolean behaviorMode) throws Exception {
        long assessmentId = behaviorMode ? 4L : 3L;
        sql.update("INSERT INTO t_assessment_result(id,project_id) VALUES(?,1)", assessmentId);
        sql.update("INSERT INTO t_project_file(id,project_id,assessment_id,original_file_name) VALUES(?,1,?,'竞业限制核验.txt')", assessmentId, assessmentId);
        AnalysisScope scope = new AnalysisScope(1L, assessmentId, (behaviorMode ? "labor-facts-" : "labor-") + suffix); LocalDateTime now = LocalDateTime.now();
        String indicator = "482dec23-9dd6-4273-a431-9c0c5fa75a95";
        EvidenceChunk chunk = EvidenceChunk.create(assessmentId, 1L, assessmentId, "竞业限制核验.txt", 1, 0, null, null,
                "本次候选人属于负有保密义务的技术人员。劳动合同解除后的竞业限制约定期限为25个月，协议范围和保密事项已核对。", now);
        EvidenceChunkRepository evidence = repositories.getRepository(EvidenceChunkRepository.class);
        AnalysisRunRepository runs = repositories.getRepository(AnalysisRunRepository.class);
        RetrievalAuditRepository retrievals = repositories.getRepository(RetrievalAuditRepository.class);
        new TransactionTemplate(transactions).execute(status -> {
            AnalysisRun run = AnalysisRun.start(scope, now, AnalysisExecutionMode.REASONING); run.freezeBehaviors(Collections.singletonList("labor-behavior"));
            run.freezeBehaviorInput(ScoringJson.hash(chunk.getText())); runs.saveAndFlush(run); evidence.saveAndFlush(chunk);
            retrievals.saveAndFlush(RetrievalAudit.builder().projectId(1L).assessmentId(assessmentId).analysisRunId(scope.getAnalysisRunId()).behaviorId("labor-behavior")
                    .queryText(chunk.getText()).queryTemplateVersion("business-verification").filterVersion("business-verification").filterEnabled(false)
                    .filterApplied(false).filterExpression("{}").retrievalStatus(RetrievalAuditStatus.SUCCESS).analysisStatus(AnalysisAuditStatus.WAITING_ANALYSIS)
                    .candidates(Collections.emptyList()).createdAt(now).updatedAt(now).build()); return null;
        });
        ScoringDataset corpus = new ScoringDataset(root.resolve("documents/data").toString());
        ScoringSourceBundle source = corpus.bundle((String)corpus.manifest().get("datasetVersion"), indicator);
        ScoringRuleRegistry registry = new ScoringRuleRegistry("classpath:rules/business-catalog-test-v2.json", true);
        ObjectNode response = M.createObjectNode().put("schemaVersion", "1.2").put("indicatorId", indicator);
        com.fasterxml.jackson.databind.node.ArrayNode findings = response.putArray("requirementFindings");
        for (com.riskwarning.common.dto.scoring.ScoringRuleSet.Requirement requirement : registry.require(indicator, source).getRequirements()) {
            boolean numeric = requirement.getValueType() == com.riskwarning.common.dto.scoring.ScoringRuleSet.ValueType.NUMBER;
            ObjectNode finding = findings.addObject().put("requirementId", requirement.getRequirementId()).put("applicability", "APPLICABLE")
                    .put("complianceStatus", numeric ? "NON_COMPLIANT" : "COMPLIANT").put("confidence", 0.95).put("reasoning", numeric ? "原文明确约定25个月" : "原文有协议范围和保密事项核对记录");
            finding.putArray("evidenceIds").add(chunk.getId());
            ObjectNode value = finding.putObject("conditionValues").putObject(requirement.getCondition().getConditionId());
            value.putArray("evidenceIds").add(chunk.getId());
            if (numeric) value.put("observedValue", 25).put("observedUnit", "MONTHS"); else value.put("conditionMet", true);
        }
        AiChatProvider model = mock(AiChatProvider.class); when(model.modelId()).thenReturn("controlled-business-model"); when(model.chat(anyString())).thenReturn(response.toString());
        ComplianceAuditWriter auditTarget = new ComplianceAuditWriter(repositories.getRepository(AnalysisAttemptRepository.class), repositories.getRepository(AnalysisResultRepository.class), retrievals, runs);
        RequirementAnalysisService semantic = new RequirementAnalysisService(Optional.of(model), evidence, transactionProxy(auditTarget, transactions));
        BehaviorAnalysisContextVO context = BehaviorAnalysisContextVO.builder().behaviorId("labor-behavior").description(chunk.getText()).sourceDocumentId(assessmentId)
                .evidenceIds(Collections.singletonList(chunk.getId())).retrievalStatus("SUCCESS").evidenceComplete(true).snapshotAvailable(true)
                .indicatorCandidates(Collections.singletonList(RetrievalCandidateVO.builder().candidateId(indicator).snapshotAvailable(true).build()))
                .regulationCandidates(Collections.emptyList()).build();
        AnalysisContextService contexts = mock(AnalysisContextService.class);
        when(contexts.context(assessmentId, 1L, scope.getAnalysisRunId())).thenReturn(AnalysisContextVO.builder().items(Collections.singletonList(context)).build());
        ScoringDatasetClient datasets = mock(ScoringDatasetClient.class); when(datasets.current()).thenReturn(corpus.manifest());
        when(datasets.bundle(source.getDatasetVersion(), indicator)).thenReturn(source);
        DurableWorkProperties settings = new DurableWorkProperties(); settings.setNamespace("scoring-business-verification");
        KafkaOutbox outbox = new TransactionalKafkaOutbox(new DurableWorkStore(sql, new TransactionTemplate(transactions), settings), new KafkaOutboxCodec(), new AssessmentFlowLogger());
        ScoringReportPreparationService reports = transactionProxy(new ScoringReportPreparationService(runs, repositories.getRepository(ScoringRunSnapshotRepository.class),
                repositories.getRepository(RuleEvaluationAuditRepository.class), repositories.getRepository(IndicatorResultRepository.class),
                repositories.getRepository(RequirementEvaluationRepository.class), outbox), transactions);
        String index = (behaviorMode ? "behavior_facts_" : "labor_pipeline_") + suffix;
        ScoringReportPreparationService reportEntry = reports;
        if (behaviorMode) {
            reportEntry = mock(ScoringReportPreparationService.class);
            java.util.concurrent.atomic.AtomicInteger prepared = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                if (prepared.getAndIncrement() == 0) throw new IllegalStateException("模拟事实保存后、报告准备前中断");
                reports.prepare(call.getArgument(0), call.getArgument(1), call.getArgument(2)); return null;
            }).when(reportEntry).prepare(any(), any(), anyBoolean());
        }
        ScoringOrchestrator orchestrator = new ScoringOrchestrator(datasets, registry, new RequirementScoringEngine(registry, new UniversalConditionEvaluator()), semantic,
                writer, contexts, repositories.getRepository(ScoringRunSnapshotRepository.class), repositories.getRepository(AnalysisResultRepository.class), retrievals,
                reportEntry, new RequirementEvaluationProjection(es, repositories.getRepository(RequirementEvaluationRepository.class), index));
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage("business-" + suffix, now.toString(), "business-verification", 1L, 1L, 3L, scope.getAnalysisRunId());
        BehaviorFactExecutor factExecutor = null;
        if (behaviorMode) {
            when(model.chat(anyString())).thenAnswer(call -> {
                String prompt = call.getArgument(0);
                com.fasterxml.jackson.databind.JsonNode input = M.readTree(prompt.substring(prompt.indexOf("{\"subject\"")));
                ObjectNode factsResponse = M.createObjectNode(); com.fasterxml.jackson.databind.node.ArrayNode answers = factsResponse.putArray("answers");
                for (com.fasterxml.jackson.databind.JsonNode question : input.path("questions")) {
                    ObjectNode answer = answers.addObject().put("questionId", question.path("id").asText()).put("status", "KNOWN").put("confidence", 0.95);
                    answer.putArray("evidenceIds").add("E1");
                    String type = question.path("definition").path("type").asText();
                    if ("APPLICABILITY".equals(type)) answer.put("applicability", "APPLICABLE");
                    else if ("NUMBER".equals(type)) answer.put("numberValue", 25).put("unit", "MONTHS");
                    else answer.put("booleanValue", true);
                }
                return factsResponse.toString();
            });
            BehaviorFactProperties properties = new BehaviorFactProperties(); properties.setFactMode(BehaviorFactProperties.Mode.BEHAVIOR);
            SharedFactCatalog catalog = new SharedFactCatalog(properties);
            factExecutor = new BehaviorFactExecutor(properties);
            RequirementScoringEngine engine = new RequirementScoringEngine(registry, new UniversalConditionEvaluator());
            BehaviorFactAnalysisService factAnalysis = new BehaviorFactAnalysisService(properties,
                    new BehaviorFactPlanner(catalog, registry, properties, evidence, Optional.of(model)),
                    new LlmBehaviorFactExtractor(Optional.of(model), transactionProxy(auditTarget, transactions), repositories.getRepository(AnalysisAttemptRepository.class)),
                    new BehaviorFactMapper(engine), factExecutor, writer, repositories.getRepository(AnalysisResultRepository.class));
            ReflectionTestUtils.setField(orchestrator, "factAnalysis", factAnalysis);
        }
        try {
            if (behaviorMode) assertThrows(IllegalStateException.class, () -> orchestrator.process(message, scope,
                    Collections.singletonList(Behavior.builder().id("labor-behavior").evidenceIds(Collections.singletonList(chunk.getId())).build())));
            orchestrator.process(message, scope, Collections.singletonList(Behavior.builder().id("labor-behavior").evidenceIds(Collections.singletonList(chunk.getId())).build()));
        } finally { if (factExecutor != null) factExecutor.destroy(); }
        verify(model, times(1)).chat(anyString());
        if (behaviorMode) {
            ScoringRunSnapshot persistedPlan = repositories.getRepository(ScoringRunSnapshotRepository.class).findById(scope.getAnalysisRunId()).get();
            assertEquals("BEHAVIOR", persistedPlan.getFactExtractionPlan().getMode());
            assertEquals(4, persistedPlan.getFactExtractionPlan().getBehaviors().get("labor-behavior").getQuestions().size());
            assertEquals("FACTS_DERIVED", repositories.getRepository(AnalysisResultRepository.class).findByAssessmentIdAndAnalysisRunId(assessmentId, scope.getAnalysisRunId()).get(0).getRequirementFindings().get(0).getJudgmentSource());
        }
        com.riskwarning.common.po.indicator.IndicatorResult result = repositories.getRepository(IndicatorResultRepository.class).findByAssessmentIdAndAnalysisRunId(assessmentId, scope.getAnalysisRunId()).get(0);
        assertEquals(100.0 * 0.2 / 1.7, result.getCalculatedScore(), 0.000001); assertEquals(RiskLevelEnum.HIGH_RISK, result.getRiskLevel());
        assertEquals(2L, sql.queryForObject("SELECT count(*) FROM t_requirement_evaluation WHERE assessment_id=?", Long.class, assessmentId));
        assertEquals(1L, sql.queryForObject("SELECT count(*) FROM t_analysis_attempt WHERE assessment_id=? AND status='VALID'", Long.class, assessmentId));
        assertEquals("FULL", runs.findById(scope.getAnalysisRunId()).get().getAnalysisSummary().path("completionKind").asText());
        java.util.List<Message> messages = sql.queryForList("SELECT payload FROM t_durable_work", String.class).stream().map(new KafkaOutboxCodec()::decode)
                .filter(item -> scope.getAnalysisRunId().equals(item.getAnalysisRunId())).collect(java.util.stream.Collectors.toList());
        assertEquals(1, messages.size()); Message queued = messages.get(0);
        assertTrue(queued instanceof AssessmentCompletedEventMessage); assertEquals(scope.getAnalysisRunId(), queued.getAnalysisRunId());
        es.indices().refresh(request -> request.index(index));
        long persisted = es.count(request -> request.index(index).query(query -> query.term(term -> term.field("analysisRunId").value(scope.getAnalysisRunId())))).count();
        assertEquals(2, persisted);
        Map<String, Object> proof = new TreeMap<>(); proof.put("schema", namespace); proof.put("index", index); proof.put("analysisRunId", scope.getAnalysisRunId());
        proof.put("factMode", behaviorMode ? "BEHAVIOR" : "INDICATOR"); proof.put("replayedWithoutExtraCalls", behaviorMode);
        proof.put("registryVersion", registry.version()); proof.put("score", result.getCalculatedScore()); proof.put("riskLevel", result.getRiskLevel().name());
        proof.put("requirements", 2); proof.put("esDocuments", persisted); proof.put("reportTasks", 1); proof.put("completionKind", "FULL");
        proof.put("scope", "已审核业务规则主编排、原文和评分实际落库、ES投影、报告任务；检索上下文与模型响应受控，未走上传和真实模型");
        Files.write(root.resolve("documents/local/scoring-evidence/" + (behaviorMode ? "behavior-facts-pipeline-" : "labor-pipeline-") + suffix + ".json"), M.writerWithDefaultPrettyPrinter().writeValueAsBytes(proof), StandardOpenOption.CREATE_NEW);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactionProxy(T target, JpaTransactionManager transactions) {
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource())); return (T)proxy.getProxy();
    }

    private static RequirementFinding fact(String requirement, boolean value, boolean violation) {
        RequirementFinding finding = new RequirementFinding(); finding.setBehaviorId("b"); finding.setIndicatorId("i"); finding.setRequirementId(requirement);
        finding.setApplicability(Applicability.APPLICABLE); finding.setComplianceStatus(violation ? ComplianceStatus.NON_COMPLIANT : ComplianceStatus.COMPLIANT);
        finding.setEvidenceIds(Collections.singletonList("e")); finding.setConfidence(new BigDecimal("0.95")); finding.setReasoning("材料中的明确行为记录");
        ConditionValue condition = new ConditionValue(); condition.setConditionMet(value); condition.setEvidenceIds(finding.getEvidenceIds());
        finding.setConditionValues(Collections.singletonMap("c-" + requirement, condition)); return finding;
    }
    private static DriverManagerDataSource dataSource(Properties settings, String url) {
        DriverManagerDataSource out = new DriverManagerDataSource(); out.setUrl(url); out.setUsername(settings.getProperty("SCORING_PG_USER"));
        out.setPassword(settings.getProperty("SCORING_PG_PASSWORD")); return out;
    }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
}
