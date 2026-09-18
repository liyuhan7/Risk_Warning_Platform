package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.provider.*;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.knowledge.config.RetrievalProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** 双库检索服务；失败显式上抛，成功无候选才返回空集合，不静默切回旧链。 */
@Service
@ConditionalOnProperty(prefix = "retrieval", name = "enabled", havingValue = "true")
public class RetrievalService {
    private static final double TOLERANCE = 1e-6;
    private final ElasticsearchClient client;
    private final AiEmbeddingProvider embedding;
    private final RetrievalProperties properties;
    private final RetrievalFilterQueryBuilder filters = new RetrievalFilterQueryBuilder();

    public RetrievalService(ElasticsearchClient client, AiEmbeddingProvider embedding, RetrievalProperties properties) {
        this.client = client;
        this.embedding = embedding;
        this.properties = properties;
        if (embedding.dimension() != 1024 || !StringUtils.hasText(properties.getEmbeddingVersion())
                || properties.getNumCandidates() < 1 || properties.getNumCandidates() > 10000
                || properties.getFusionWindow() < 1 || properties.getFusionWindow() > properties.getNumCandidates()
                || properties.getIndicatorTopK() < 1 || properties.getRegulationTopK() < 1
                || properties.getDenseWeight() != 0.5 || properties.getBm25Weight() != 0.5
                || properties.getDenseScoreMode() == null) {
            throw new IllegalArgumentException("检索配置不合法");
        }
    }

    /** 每次查询仅计算一次向量，候选 ID 来自 ES _id，作用域按请求透传。 */
    public List<RetrievalResult> retrieve(RetrievalRequest request) {
        validateRequest(request);
        List<Float> vector = embedding.embed(Collections.singletonList(request.getQueryText()), EmbeddingRole.QUERY).get(0);
        return retrieveWithVector(request, vector);
    }

    /** 批内最多 16 条，一次生成全部查询向量；任一失败时整批失败。 */
    public RetrievalBatchResponse retrieveBatch(RetrievalBatchRequest batch) {
        if (batch == null || !"1.0".equals(batch.getSchemaVersion()) || batch.getScope() == null
                || batch.getRequests() == null || batch.getRequests().isEmpty() || batch.getRequests().size() > 16) {
            throw new IllegalArgumentException("批量检索请求不合法");
        }
        Set<String> behaviorIds = new HashSet<>();
        List<String> texts = new ArrayList<>();
        for (RetrievalRequest request : batch.getRequests()) {
            validateRequest(request);
            if (!behaviorIds.add(request.getBehaviorId())
                    || !batch.getScope().getAssessmentId().equals(request.getAssessmentId())
                    || !batch.getScope().getAnalysisRunId().equals(request.getAnalysisRunId())) {
                throw new IllegalArgumentException("批内作用域不一致或 behaviorId 重复");
            }
            texts.add(request.getQueryText());
        }
        List<List<Float>> vectors = embedding.embed(texts, EmbeddingRole.QUERY);
        if (vectors.size() != batch.getRequests().size()) {
            throw new IllegalStateException("批量向量数量不匹配");
        }
        List<RetrievalBatchItem> items = new ArrayList<>();
        for (int i = 0; i < batch.getRequests().size(); i++) {
            RetrievalRequest request = batch.getRequests().get(i);
            List<RetrievalResult> results = retrieveWithVector(request, vectors.get(i));
            List<RetrievalCandidateSnapshot> snapshots;
            RetrievalBatchStatus status;
            try {
                snapshots = hydrate(results);
                status = snapshots.isEmpty() ? RetrievalBatchStatus.NO_CANDIDATES : RetrievalBatchStatus.SUCCESS;
            } catch (SnapshotUnavailableException failure) {
                // ID 与评分来自已完成的真实检索；回读失败只保留检索记录，不伪造候选正文。
                snapshots = new ArrayList<>();
                for (RetrievalResult result : results) {
                    snapshots.add(RetrievalCandidateSnapshot.builder().result(result).build());
                }
                status = RetrievalBatchStatus.SNAPSHOT_UNAVAILABLE;
            }
            items.add(RetrievalBatchItem.builder().behaviorId(request.getBehaviorId())
                    .status(status).embeddingModel(embedding.modelId())
                    .embeddingVersion(properties.getEmbeddingVersion()).matchedFilters(filters.describe(request.getFilter()))
                    .candidates(snapshots).build());
        }
        return RetrievalBatchResponse.builder().items(items).build();
    }

    private void validateRequest(RetrievalRequest request) {
        if (request == null || !StringUtils.hasText(request.getQueryText())
                || !StringUtils.hasText(request.getBehaviorId()) || !StringUtils.hasText(request.getAnalysisRunId())
                || request.getAssessmentId() == null || request.getAssessmentId() <= 0) {
            throw new IllegalArgumentException("检索文本及作用域不得为空");
        }
    }

    private List<RetrievalResult> retrieveWithVector(RetrievalRequest request, List<Float> vector) {
        int indicatorK = request.getIndicatorTopK() == null ? properties.getIndicatorTopK() : request.getIndicatorTopK();
        int regulationK = request.getRegulationTopK() == null ? properties.getRegulationTopK() : request.getRegulationTopK();
        if (indicatorK < 1 || indicatorK > properties.getNumCandidates()
                || regulationK < 1 || regulationK > properties.getFusionWindow()) {
            throw new IllegalArgumentException("Top-K 超出检索窗口");
        }
        List<Query> constraints = new ArrayList<>();
        constraints.add(Query.of(q -> q.term(t -> t.field("embeddingModel").value(embedding.modelId()))));
        constraints.add(Query.of(q -> q.term(t -> t.field("embeddingVersion").value(properties.getEmbeddingVersion()))));
        constraints.addAll(filters.build(request.getFilter()));
        try {
            Map<String, Double> indicators = dense(properties.getIndicatorIndex(), vector, indicatorK, constraints);
            Map<String, Double> regulations = dense(properties.getRegulationIndex(), vector, properties.getFusionWindow(), constraints);
            Map<String, Double> bm25 = lexical(request.getQueryText(), constraints);
            List<RetrievalResult> output = new ArrayList<>();
            LocalDateTime time = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            append(output, indicators, indicatorK, RetrievalCandidateType.INDICATOR, ScoreType.COSINE_SIMILARITY, request, time);
            append(output, fuse(regulations, bm25), regulationK, RetrievalCandidateType.REGULATION, ScoreType.HYBRID, request, time);
            return output;
        } catch (IOException failure) {
            throw new IllegalStateException("ES 检索失败", failure);
        }
    }

    private List<RetrievalCandidateSnapshot> hydrate(List<RetrievalResult> results) {
        Map<String, RetrievalResult> indicators = new LinkedHashMap<>();
        Map<String, RetrievalResult> regulations = new LinkedHashMap<>();
        for (RetrievalResult result : results) {
            (result.getCandidateType() == RetrievalCandidateType.INDICATOR ? indicators : regulations)
                    .put(result.getCandidateId(), result);
        }
        List<RetrievalCandidateSnapshot> output = new ArrayList<>();
        try {
            output.addAll(hydrateIndex(properties.getIndicatorIndex(), indicators, true));
            output.addAll(hydrateIndex(properties.getRegulationIndex(), regulations, false));
        } catch (IOException failure) {
            throw new SnapshotUnavailableException("候选快照回读失败", failure);
        }
        output.sort(Comparator.comparing((RetrievalCandidateSnapshot s) -> s.getResult().getCandidateType())
                .thenComparing(s -> s.getResult().getRank()));
        if (output.size() != results.size()) {
            throw new SnapshotUnavailableException("检索候选在快照回读时缺失", null);
        }
        return output;
    }

    private List<RetrievalCandidateSnapshot> hydrateIndex(String index,
                                                           Map<String, RetrievalResult> scored,
                                                           boolean indicator) throws IOException {
        if (scored.isEmpty()) { return Collections.emptyList(); }
        SearchResponse<Map> response = client.search(s -> s.index(index).size(scored.size())
                .query(q -> q.ids(ids -> ids.values(new ArrayList<>(scored.keySet())))), Map.class);
        List<RetrievalCandidateSnapshot> output = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map source = hit.source();
            RetrievalResult result = scored.get(hit.id());
            if (source == null || result == null) { continue; }
            Object max = source.get("maxScore");
            Object level = source.get("indicatorLevel");
            String content = string(source.get(indicator ? "description" : "fullText"));
            output.add(RetrievalCandidateSnapshot.builder().result(result)
                    .name(string(source.get("name")))
                    .content(content).retrievalTextHash(string(source.get("retrievalTextHash")))
                    .contentHash(!indicator && content != null ? EvidenceChunk.sha256(content) : null)
                    .indicatorLevel(level instanceof Number ? ((Number) level).intValue() : null)
                    .dimension(string(source.get("dimension"))).type(string(source.get("type")))
                    .maxScore(max instanceof Number ? ((Number) max).doubleValue() : null).build());
        }
        return output;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static class SnapshotUnavailableException extends IllegalStateException {
        SnapshotUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    private Map<String, Double> dense(String index, List<Float> vector, int k, List<Query> constraints) throws IOException {
        SearchResponse<Map> response = client.search(s -> s.index(index).size(k)
                .source(src -> src.fetch(false)).knn(n -> n.field("retrievalVector").queryVector(vector)
                        .k(k).numCandidates(properties.getNumCandidates()).filter(constraints)), Map.class);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Hit<Map> hit : response.hits().hits()) { scores.put(hit.id(), normalizeDense(hit.score())); }
        return scores;
    }

    private Map<String, Double> lexical(String text, List<Query> constraints) throws IOException {
        SearchResponse<Map> response = client.search(s -> s.index(properties.getRegulationIndex())
                .size(properties.getFusionWindow()).source(src -> src.fetch(false))
                .query(q -> q.bool(b -> b.filter(constraints).must(m -> m.match(t -> t.field("retrievalTextIk").query(text))))), Map.class);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Hit<Map> hit : response.hits().hits()) {
            if (hit.score() == null || !Double.isFinite(hit.score()) || hit.score() < 0) {
                throw new IllegalStateException("BM25 分数非法");
            }
            scores.put(hit.id(), hit.score());
        }
        return scores;
    }

    /** 整路使用显式模式；ES 8.11 cosine KNN 已映射到 [0,1]，不得二次转换。 */
    double normalizeDense(Double score) {
        if (score == null || !Double.isFinite(score)) { throw new IllegalStateException("dense 分数非法"); }
        double normalized = properties.getDenseScoreMode() == RetrievalProperties.DenseScoreMode.RAW_COSINE
                ? (1 + score) / 2 : score;
        if (normalized < -TOLERANCE || normalized > 1 + TOLERANCE) {
            throw new IllegalStateException("dense 分数超出配置模式区间");
        }
        return Math.max(0, Math.min(1, normalized));
    }

    /**
     * 按 dense top-N ∪ BM25 top-N 合并后的窗口 max 归一化，而非各自列表 max。
     * 任一路未召回按 0 处理；窗口最大 BM25 不存在或不大于 0 时整路为 0。
     */
    Map<String, Double> fuse(Map<String, Double> dense, Map<String, Double> bm25) {
        Set<String> union = new LinkedHashSet<>(dense.keySet());
        union.addAll(bm25.keySet());
        double max = union.stream().mapToDouble(id -> bm25.getOrDefault(id, 0.0)).max().orElse(0);
        Map<String, Double> output = new LinkedHashMap<>();
        for (String id : union) {
            output.put(id, properties.getDenseWeight() * dense.getOrDefault(id, 0.0)
                    + properties.getBm25Weight() * (max <= 0 ? 0 : bm25.getOrDefault(id, 0.0) / max));
        }
        return output;
    }

    void append(List<RetrievalResult> output, Map<String, Double> scores, int k,
                RetrievalCandidateType type, ScoreType scoreType, RetrievalRequest request, LocalDateTime time) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (int i = 0; i < Math.min(k, entries.size()); i++) {
            Map.Entry<String, Double> entry = entries.get(i);
            output.add(RetrievalResult.builder().candidateType(type).candidateId(entry.getKey())
                    .score(entry.getValue()).scoreType(scoreType).rank(i + 1)
                    .matchedFilters(filters.describe(request.getFilter())).embeddingModel(embedding.modelId())
                    .embeddingVersion(properties.getEmbeddingVersion()).behaviorId(request.getBehaviorId())
                    .analysisRunId(request.getAnalysisRunId()).assessmentId(request.getAssessmentId()).retrievedAt(time).build());
        }
    }
}
