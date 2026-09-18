package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.po.indicator.*;
import com.riskwarning.common.po.risk.*;
import com.riskwarning.common.utils.KafkaUtils;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.processing.client.RetrievalClient;
import com.riskwarning.processing.config.P2ProcessingProperties;
import com.riskwarning.processing.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** P2 固定样本垂直切片；非白名单输入只记录无结论，不生成业务判断。 */
@Slf4j
@Service
public class P2DemoProcessingService {
    private static final String RULE = "p2_mock_fixture_v1";
    private final BehaviorDocumentRepository behaviors;
    private final EvidenceQueryService evidenceQuery;
    private final RetrievalClient retrievalClient;
    private final RetrievalAuditService auditService;
    private final IndicatorResultRepository indicatorResults;
    private final P2ResultPersistenceService resultPersistence;
    private final AnalysisRunNoDecisionService noDecisionService;
    private final BehaviorQueryTextBuilder queryBuilder;
    private final P2ProcessingProperties properties;
    private final KafkaUtils kafka;
    private final ObjectMapper mapper;
    private volatile FixtureFile fixtures;

    public P2DemoProcessingService(BehaviorDocumentRepository behaviors,
                                   EvidenceQueryService evidenceQuery,
                                   RetrievalClient retrievalClient,
                                   RetrievalAuditService auditService,
                                   IndicatorResultRepository indicatorResults,
                                   P2ResultPersistenceService resultPersistence,
                                   AnalysisRunNoDecisionService noDecisionService,
                                   BehaviorQueryTextBuilder queryBuilder,
                                   P2ProcessingProperties properties,
                                   KafkaUtils kafka, ObjectMapper mapper) {
        this.behaviors = behaviors;
        this.evidenceQuery = evidenceQuery;
        this.retrievalClient = retrievalClient;
        this.auditService = auditService;
        this.indicatorResults = indicatorResults;
        this.resultPersistence = resultPersistence;
        this.noDecisionService = noDecisionService;
        this.queryBuilder = queryBuilder;
        this.properties = properties;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public void process(IndicatorCalculationTaskMessage message, AnalysisScope scope) {
        List<Behavior> scoped = behaviors.findByScope(scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId());
        if (scoped.isEmpty()) { throw new IllegalStateException("当前运行没有 Behavior"); }
        try {
            processScoped(message, scope, scoped);
        } catch (RuntimeException failure) {
            writeFailedAudits(scope, scoped, failure);
            throw failure;
        }
    }

    private void processScoped(IndicatorCalculationTaskMessage message, AnalysisScope scope, List<Behavior> scoped) {
        FixtureFile fixtureFile = fixtures();
        scoped = new ArrayList<>(scoped);
        scoped.sort(Comparator.comparing(Behavior::getId));
        Map<Long, FixtureCase> casesByDocument = resolveCases(message.getDocuments());
        List<RetrievalBatchItem> retrieved = retrieve(scope, scoped);
        Map<String, RetrievalBatchItem> byBehavior = retrieved.stream().collect(Collectors.toMap(
                RetrievalBatchItem::getBehaviorId, Function.identity(), (a, b) -> { throw new IllegalStateException("重复检索结果"); }));

        List<Prepared> prepared = new ArrayList<>();
        boolean complete = true;
        for (Behavior behavior : scoped) {
            String query = queryBuilder.buildQueryText(behavior);
            RetrievalBatchItem item = byBehavior.get(behavior.getId());
            if (item == null) { throw new IllegalStateException("检索响应缺少 Behavior: " + behavior.getId()); }
            FixtureCase fixtureCase = casesByDocument.get(behavior.getSourceDocumentId());
            Prepared one = prepare(scope, behavior, query, item, fixtureCase, fixtureFile.fixtureSha256);
            prepared.add(one);
            auditService.save(one.audit);
            if (!one.determined) { complete = false; }
        }

        List<AnalysisResult> validAnalyses = prepared.stream().map(p -> p.analysisResult)
                .filter(Objects::nonNull).collect(Collectors.toList());
        if (!complete) {
            resultPersistence.saveAnalyses(validAnalyses);
            noDecisionService.complete(scope);
            notifyWithoutDecision(message.getUserId(), scope);
            return;
        }
        List<IndicatorResult> output = buildIndicatorResults(scope, prepared);
        resultPersistence.saveSuccess(validAnalyses, output);
        kafka.sendMessage(new AssessmentCompletedEventMessage(StringUtils.generateMessageId(),
                String.valueOf(System.currentTimeMillis()), StringUtils.generateTraceId(), message.getUserId(),
                scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId()));
    }

    /**
     * 无决策终态同样要通知前端。
     *
     * 结束无决策运行时运行状态不再是"进行中"，报告侧的汇总要求运行仍处于进行中，
     * 因此这条路径不会产生评估完成事件；若在此不通知，等待页面将一直停留在等待态。
     * 复用评估完成类型，具体终态由 extraData 的 displayStatus 表达。
     */
    private void notifyWithoutDecision(Long userId, AnalysisScope scope) {
        try {
            NotificationMessage notification = new NotificationMessage(
                    StringUtils.generateMessageId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    StringUtils.generateTraceId(),
                    userId, scope.getProjectId(), scope.getAssessmentId(),
                    NotificationMessage.NotificationType.ASSESSMENT_COMPLETED,
                    "评估完成通知",
                    "材料分析已完成，但未形成决策结论，请在分析概览查看未决策原因。");
            notification.setExtraData(mapper.writeValueAsString(Collections.singletonMap(
                    "displayStatus", AnalysisRunStatus.COMPLETED_WITHOUT_DECISION.name())));
            kafka.sendMessage(notification);
        } catch (Exception failure) {
            // 运行已处于终态，通知失败不能反过来影响已落库的分析结果
            log.warn("无决策完成通知发送失败: assessmentId={}, run={}",
                    scope.getAssessmentId(), scope.getAnalysisRunId(), failure);
        }
    }

    private List<RetrievalBatchItem> retrieve(AnalysisScope scope, List<Behavior> scoped) {
        List<RetrievalBatchItem> all = new ArrayList<>();
        for (int from = 0; from < scoped.size(); from += properties.getBatchSize()) {
            List<Behavior> batch = scoped.subList(from, Math.min(scoped.size(), from + properties.getBatchSize()));
            List<RetrievalRequest> requests = batch.stream().map(b -> RetrievalRequest.builder()
                    .queryText(queryBuilder.buildQueryText(b)).behaviorId(b.getId())
                    .assessmentId(scope.getAssessmentId()).analysisRunId(scope.getAnalysisRunId())
                    .filter(RetrievalFilter.none()).build()).collect(Collectors.toList());
            RetrievalBatchResponse response = retrievalClient.retrieve(RetrievalBatchRequest.builder()
                    .scope(scope).requests(requests).build());
            if (response == null || response.getItems() == null || response.getItems().size() != batch.size()) {
                throw new IllegalStateException("批量检索响应数量不匹配");
            }
            all.addAll(response.getItems());
        }
        return all;
    }

    private void writeFailedAudits(AnalysisScope scope, List<Behavior> scoped, RuntimeException failure) {
        for (Behavior behavior : scoped) {
            RetrievalAudit audit = baseAudit(scope, behavior, queryBuilder.buildQueryText(behavior), Collections.emptyList());
            audit.setRetrievalStatus(RetrievalAuditStatus.FAILED);
            audit.setAnalysisStatus(AnalysisAuditStatus.NOT_ATTEMPTED);
            audit.setErrorCode("P2_RUN_FAILED");
            audit.setErrorType(failure.getClass().getSimpleName());
            audit.setErrorMessage(sanitize(failure.getMessage()));
            auditService.save(audit);
        }
    }

    private Prepared prepare(AnalysisScope scope, Behavior behavior, String query,
                             RetrievalBatchItem item, FixtureCase fixtureCase, String fixtureSha) {
        List<RetrievalCandidateSnapshot> candidates = item.getCandidates() == null
                ? Collections.emptyList() : item.getCandidates();
        RetrievalAudit audit = baseAudit(scope, behavior, query, candidates);
        if (item.getStatus() == RetrievalBatchStatus.NO_CANDIDATES) {
            audit.setRetrievalStatus(RetrievalAuditStatus.NO_CANDIDATES);
            audit.setAnalysisStatus(AnalysisAuditStatus.NOT_ATTEMPTED);
            return Prepared.noDecision(audit);
        }
        audit.setRetrievalStatus(RetrievalAuditStatus.SUCCESS);
        if (fixtureCase == null) {
            audit.setAnalysisStatus(AnalysisAuditStatus.NOT_DEMO_INPUT);
            return Prepared.noDecision(audit);
        }
        List<String> evidenceIds = behavior.getEvidenceIds() == null ? Collections.emptyList() : behavior.getEvidenceIds();
        if (evidenceIds.isEmpty()) { throw new IllegalStateException("Behavior 缺少 evidenceIds"); }
        List<EvidenceChunk> evidence = evidenceQuery.listByIds(evidenceIds, scope.getProjectId(), scope.getAssessmentId());
        Set<String> foundIds = evidence.stream().map(EvidenceChunk::getId).collect(Collectors.toSet());
        if (foundIds.size() != evidenceIds.size() || !foundIds.containsAll(evidenceIds)) {
            throw new IllegalStateException("Behavior 引用的 Evidence 不完整");
        }
        evidence.forEach(EvidenceChunk::verifyIntegrity);
        FixtureFact fact = matchFact(fixtureCase, evidence);
        if (fact == null) {
            audit.setAnalysisStatus(AnalysisAuditStatus.NOT_DEMO_INPUT);
            return Prepared.noDecision(audit);
        }
        Set<String> candidateIds = candidates.stream().map(c -> c.getResult().getCandidateId()).collect(Collectors.toSet());
        if (!candidateIds.contains(fact.expectedIndicatorId) || !candidateIds.containsAll(fact.expectedRegulationIds)) {
            audit.setAnalysisStatus(AnalysisAuditStatus.RECALL_GAP);
            return Prepared.noDecision(audit);
        }
        AnalysisResult result = buildAnalysis(scope, behavior, evidenceIds, fact);
        if (fact.complianceStatus == ComplianceStatus.INSUFFICIENT_EVIDENCE) {
            audit.setAnalysisStatus(AnalysisAuditStatus.INSUFFICIENT_EVIDENCE);
            return Prepared.noDecision(audit, result);
        }
        audit.setAnalysisStatus(AnalysisAuditStatus.SUCCESS);
        RetrievalCandidateSnapshot indicator = candidates.stream()
                .filter(c -> c.getResult().getCandidateType() == RetrievalCandidateType.INDICATOR
                        && fact.expectedIndicatorId.equals(c.getResult().getCandidateId())).findFirst().get();
        List<RetrievalCandidateSnapshot> regulations = candidates.stream()
                .filter(c -> c.getResult().getCandidateType() == RetrievalCandidateType.REGULATION
                        && fact.expectedRegulationIds.contains(c.getResult().getCandidateId())).collect(Collectors.toList());
        return Prepared.determined(audit, result, behavior, indicator, regulations, fixtureCase, fact, fixtureSha);
    }

    private List<IndicatorResult> buildIndicatorResults(AnalysisScope scope, List<Prepared> prepared) {
        Map<String, List<Prepared>> grouped = prepared.stream().collect(Collectors.groupingBy(
                p -> p.fact.expectedIndicatorId, LinkedHashMap::new, Collectors.toList()));
        List<IndicatorResult> output = new ArrayList<>();
        for (Map.Entry<String, List<Prepared>> entry : grouped.entrySet()) {
            List<Prepared> group = entry.getValue();
            Prepared first = group.get(0);
            for (Prepared item : group) {
                if (!Objects.equals(first.fact.expectedScore, item.fact.expectedScore)
                        || !Objects.equals(first.fact.expectedRiskTriggered, item.fact.expectedRiskTriggered)) {
                    throw new IllegalStateException("同一指标的 fixture 决策冲突");
                }
            }
            IndicatorResult target = indicatorResults.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(
                    scope.getAssessmentId(), scope.getAnalysisRunId(), entry.getKey()).orElseGet(IndicatorResult::new);
            target.setProjectId(scope.getProjectId()); target.setAssessmentId(scope.getAssessmentId());
            target.setAnalysisRunId(scope.getAnalysisRunId()); target.setIndicatorEsId(entry.getKey());
            target.setIndicatorName(first.indicator.getName());
            target.setIndicatorLevel(first.indicator.getIndicatorLevel() == null ? 0 : first.indicator.getIndicatorLevel());
            target.setDimension(first.indicator.getDimension()); target.setType(first.indicator.getType());
            target.setCalculatedScore(first.fact.expectedScore); target.setMaxPossibleScore(first.fact.maxScore);
            target.setUsedCalculationRuleType(RULE); target.setRiskTriggered(first.fact.expectedRiskTriggered);
            target.setRiskStatus(IndicatorRiskStatus.NOT_EVALUATED);
            List<RelatedIndicator> related = group.stream().map(this::relatedIndicator).collect(Collectors.toList());
            List<AnalysisTrace> traces = group.stream().map(Prepared::trace).collect(Collectors.toList());
            target.setCalculationDetails(IndicatorResultDetail.builder().schemaVersion("1.0")
                    .analysisMode("MOCK_FIXTURE").fixture(first.fixture()).relatedIndicators(related).traces(traces).build());
            target.setCalculatedAt(LocalDateTime.now());
            if (target.getCreatedAt() == null) { target.setCreatedAt(LocalDateTime.now()); }
            output.add(target);
        }
        return output;
    }

    private RelatedIndicator relatedIndicator(Prepared p) {
        List<RelatedRegulation> regs = p.regulations.stream().map(r -> RelatedRegulation.builder()
                .regulationId(r.getResult().getCandidateId()).regulationName(r.getName())
                .complianceRequirement(r.getContent()).violationType("").build()).collect(Collectors.toList());
        return RelatedIndicator.builder().indicatorId(p.fact.expectedIndicatorId).indicatorName(p.indicator.getName())
                .score(p.fact.expectedScore).maxScore(p.fact.maxScore).isPrimaryTrigger(Boolean.TRUE.equals(p.fact.expectedRiskTriggered))
                .relatedBehaviors(Collections.singletonList(RelatedBehavior.builder().projectId(p.behavior.getProjectId())
                        .description(p.behavior.getDescription()).relatedRegulations(regs).build())).build();
    }

    private AnalysisResult buildAnalysis(AnalysisScope scope, Behavior behavior, List<String> evidenceIds, FixtureFact fact) {
        return AnalysisResult.builder().id(sha256(scope.getAnalysisRunId() + "|" + behavior.getId() + "|" + fact.expectedIndicatorId))
                .schemaVersion("1.0").analysisRunId(scope.getAnalysisRunId()).assessmentId(scope.getAssessmentId())
                .behaviorId(behavior.getId()).evidenceIds(new ArrayList<>(evidenceIds)).indicatorId(fact.expectedIndicatorId)
                .regulationIds(new ArrayList<>(fact.expectedRegulationIds)).applicable(fact.applicable)
                .requirement(fact.requirement).enterpriseFact(fact.enterpriseFact).complianceStatus(fact.complianceStatus)
                .gapType(fact.gapType).confidence(1.0).reasoning("P2 固定样本机械结论")
                .modelVersion("mock-fixture-v1").promptVersion("mock-analysis-v1").analyzedAt(LocalDateTime.now()).build();
    }

    private RetrievalAudit baseAudit(AnalysisScope scope, Behavior behavior, String query,
                                     List<RetrievalCandidateSnapshot> candidates) {
        String version = candidates.stream().map(c -> c.getResult().getEmbeddingVersion())
                .filter(Objects::nonNull).findFirst().orElse(null);
        return RetrievalAudit.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId()).behaviorId(behavior.getId()).queryText(query)
                .queryTemplateVersion(BehaviorQueryTextBuilder.QUERY_TEMPLATE_VERSION).filterVersion(RetrievalFilter.FILTER_VERSION)
                .filterEnabled(false).filterApplied(false).filterExpression("filter-v1 disabled; no predicate applied")
                .embeddingModel(candidates.stream().map(c -> c.getResult().getEmbeddingModel())
                        .filter(Objects::nonNull).findFirst().orElse(null))
                .embeddingVersion(version).candidates(new ArrayList<>(candidates)).build();
    }

    private Map<Long, FixtureCase> resolveCases(List<SourceDocumentRef> documents) {
        if (documents == null || documents.isEmpty()) { return Collections.emptyMap(); }
        Map<String, FixtureCase> byHash = fixtures().cases.stream().collect(Collectors.toMap(c -> c.sourceFileSha256, Function.identity()));
        Map<Long, FixtureCase> result = new HashMap<>();
        for (SourceDocumentRef document : documents) {
            try { result.put(document.getSourceDocumentId(), byHash.get(fileSha256(Paths.get(document.getFilePath())))); }
            catch (Exception failure) { throw new IllegalStateException("演示源文件哈希计算失败", failure); }
        }
        return result;
    }

    private FixtureFact matchFact(FixtureCase fixtureCase, List<EvidenceChunk> evidence) {
        Set<String> hashes = evidence.stream().map(EvidenceChunk::getTextHash).collect(Collectors.toSet());
        List<FixtureFact> matches = fixtureCase.facts.stream()
                .filter(f -> hashes.contains(f.evidenceTextHash)).collect(Collectors.toList());
        if (matches.size() > 1) { throw new IllegalStateException("一个 Behavior 命中多个 fixture 事实"); }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private FixtureFile loadFixture(ObjectMapper mapper, String location) {
        try {
            Path path = Paths.get(location);
            if (!Files.exists(path)) { throw new IllegalStateException("P2 fixture 不存在: " + location); }
            FixtureFile file = mapper.readValue(path.toFile(), FixtureFile.class);
            validateFixture(file, mapper);
            return file;
        } catch (Exception failure) { throw new IllegalStateException("P2 fixture 加载失败", failure); }
    }

    private FixtureFile fixtures() {
        FixtureFile current = fixtures;
        if (current == null) {
            synchronized (this) {
                current = fixtures;
                if (current == null) {
                    current = loadFixture(mapper, properties.getFixturePath());
                    fixtures = current;
                }
            }
        }
        return current;
    }

    static void validateFixture(FixtureFile file, ObjectMapper mapper) throws Exception {
        if (file == null || !"mock-analysis-v1".equals(file.fixtureVersion)
                || file.fixtureSha256 == null || file.cases == null || file.cases.size() != 3) {
            throw new IllegalStateException("P2 fixture 头部契约不合法");
        }
        Map<String, Object> raw = mapper.convertValue(file, new TypeReference<Map<String, Object>>() { });
        raw.remove("fixtureSha256");
        String actualHash = hexStatic(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(canonical(raw))));
        if (!file.fixtureSha256.equals(actualHash)) { throw new IllegalStateException("P2 fixture 自身哈希不匹配"); }
        Set<String> expectedCases = new HashSet<>(Arrays.asList("CASE-001", "CASE-002", "CASE-003"));
        Set<String> caseIds = new HashSet<>(), sourceHashes = new HashSet<>(), factIds = new HashSet<>(), factKeys = new HashSet<>();
        for (FixtureCase item : file.cases) {
            if (item == null || !caseIds.add(item.fixtureId) || !sourceHashes.add(item.sourceFileSha256)
                    || item.facts == null || item.facts.isEmpty()) { throw new IllegalStateException("P2 fixture 案例重复或为空"); }
            for (FixtureFact fact : item.facts) {
                if (fact == null || !factIds.add(fact.factId)
                        || !factKeys.add(item.sourceFileSha256 + "|" + fact.evidenceTextHash)
                        || fact.expectedIndicatorId == null || fact.expectedRegulationIds == null) {
                    throw new IllegalStateException("P2 fixture 事实键重复或字段缺失");
                }
                boolean insufficient = fact.complianceStatus == ComplianceStatus.INSUFFICIENT_EVIDENCE;
                if (insufficient != (fact.expectedScore == null
                        && fact.expectedRiskTriggered == null && fact.expectedRiskLevel == null)) {
                    throw new IllegalStateException("P2 fixture 证据不足字段组合不合法");
                }
                if (!insufficient && (fact.expectedScore == null || fact.maxScore == null
                        || fact.expectedRiskTriggered == null
                        || Boolean.TRUE.equals(fact.expectedRiskTriggered) != (fact.expectedRiskLevel != null))) {
                    throw new IllegalStateException("P2 fixture 决策字段组合不合法");
                }
            }
        }
        if (!caseIds.equals(expectedCases)) { throw new IllegalStateException("P2 fixture 案例白名单不匹配"); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List) {
            return ((List<?>) value).stream().map(P2DemoProcessingService::canonical).collect(Collectors.toList());
        }
        return value;
    }

    private static String hexStatic(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) { result.append(String.format(Locale.ROOT, "%02x", value)); }
        return result.toString();
    }

    private String fileSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) { if (read > 0) digest.update(buffer, 0, read); }
        }
        return hex(digest.digest());
    }
    private String sha256(String value) { try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"))); }
        catch (Exception e) { throw new IllegalStateException(e); } }
    private String hex(byte[] bytes) { return hexStatic(bytes); }
    private String sanitize(String text) { if (text == null) return null; return text.substring(0, Math.min(text.length(), 1000)).replaceAll("(?i)(bearer|api[-_ ]?key)\\s*[:=]?\\s*\\S+", "$1 [REDACTED]"); }

    public static class FixtureFile { public String fixtureVersion; public String fixtureSha256; public List<FixtureCase> cases; }
    public static class FixtureCase { public String fixtureId; public String sourceFileSha256; public List<FixtureFact> facts; }
    public static class FixtureFact {
        public String factId, evidenceTextHash, expectedIndicatorId, requirement, enterpriseFact;
        public List<String> expectedRegulationIds = new ArrayList<>();
        public Applicability applicable; public ComplianceStatus complianceStatus; public GapType gapType;
        public Double expectedScore, maxScore; public Boolean expectedRiskTriggered; public RiskLevelEnum expectedRiskLevel;
    }
    private static class Prepared {
        RetrievalAudit audit; AnalysisResult analysisResult; boolean determined; Behavior behavior;
        RetrievalCandidateSnapshot indicator; List<RetrievalCandidateSnapshot> regulations; FixtureCase fixtureCase;
        FixtureFact fact; String fixtureSha;
        static Prepared noDecision(RetrievalAudit a) { Prepared p=new Prepared();p.audit=a;return p; }
        static Prepared noDecision(RetrievalAudit a, AnalysisResult r) { Prepared p=noDecision(a);p.analysisResult=r;return p; }
        static Prepared determined(RetrievalAudit a, AnalysisResult r, Behavior b, RetrievalCandidateSnapshot i,
                                   List<RetrievalCandidateSnapshot> regs, FixtureCase c, FixtureFact f, String sha) {
            Prepared p=new Prepared();p.audit=a;p.analysisResult=r;p.determined=true;p.behavior=b;p.indicator=i;
            p.regulations=regs;p.fixtureCase=c;p.fact=f;p.fixtureSha=sha;return p;
        }
        FixtureDescriptor fixture() { return FixtureDescriptor.builder().fixtureId(fixtureCase.fixtureId)
                .fixtureVersion("mock-analysis-v1").sourceFileSha256(fixtureCase.sourceFileSha256).fixtureSha256(fixtureSha).build(); }
        AnalysisTrace trace() { return AnalysisTrace.builder().behaviorId(behavior.getId()).evidenceIds(analysisResult.getEvidenceIds())
                .queryText(audit.getQueryText()).queryTemplateVersion(audit.getQueryTemplateVersion())
                .filterVersion(audit.getFilterVersion()).filterEnabled(false).candidates(audit.getCandidates())
                .analysisResult(analysisResult).ruleEvaluation(RuleEvaluation.builder().ruleVersion(RULE)
                        .decision(Boolean.TRUE.equals(fact.expectedRiskTriggered) ? "RISK" : "NO_RISK")
                        .calculatedScore(fact.expectedScore).maxPossibleScore(fact.maxScore)
                        .riskTriggered(fact.expectedRiskTriggered).riskLevel(fact.expectedRiskLevel).build()).build(); }
    }
}
