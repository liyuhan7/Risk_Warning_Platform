package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.config.EmbeddingProviderProperties;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.provider.*;
import com.riskwarning.knowledge.config.RetrievalProperties;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启的真实 API + localhost ES 验收；默认单元测试不依赖外部服务。 */
@EnabledIfSystemProperty(named = "retrieval.integration", matches = "true")
class RetrievalServiceIntegrationTest {
    private static RestClientTransport transport;
    private static ElasticsearchClient client;
    private static OpenAiCompatibleEmbeddingProvider embedding;
    private static RetrievalService service;

    @BeforeAll
    static void setup() throws Exception {
        String key = readEnv("EMBEDDING_API_KEY");
        assertNotNull(key, ".env 缺少 EMBEDDING_API_KEY");
        EmbeddingProviderProperties embeddingProperties = new EmbeddingProviderProperties();
        embeddingProperties.setApiKey(key);
        embedding = new OpenAiCompatibleEmbeddingProvider(embeddingProperties);
        transport = new RestClientTransport(RestClient.builder(HttpHost.create("http://localhost:9200")).build(),
                new JacksonJsonpMapper(new ObjectMapper()));
        client = new ElasticsearchClient(transport);
        RetrievalProperties retrieval = new RetrievalProperties();
        retrieval.setEnabled(true);
        retrieval.setIndicatorIndex("t_indicator_test");
        retrieval.setRegulationIndex("t_regulation_test");
        service = new RetrievalService(client, embedding, retrieval);
    }

    @AfterAll static void close() throws Exception { if (transport != null) transport.close(); }

    @Test
    void realProviderSupportsSingleAndBatch() {
        List<List<Float>> one = embedding.embed(Collections.singletonList("设备召回评估"), EmbeddingRole.QUERY);
        List<List<Float>> batch = embedding.embed(Arrays.asList("设备召回评估", "数据出境风险评估"), EmbeddingRole.QUERY);
        assertEquals(1, one.size());
        assertEquals(2, batch.size());
        for (List<Float> vector : batch) {
            assertEquals(1024, vector.size());
            double norm = Math.sqrt(vector.stream().mapToDouble(v -> v * v).sum());
            assertEquals(1.0, norm, 1e-5);
        }
    }

    @Test
    void es811CosineScoreUsesMappedFormula() throws Exception {
        SearchResponse<Map> sample = client.search(s -> s.index("t_indicator_test").size(1)
                .source(src -> src.filter(f -> f.includes("retrievalVector"))), Map.class);
        Hit<Map> hit = sample.hits().hits().get(0);
        List<?> raw = (List<?>) hit.source().get("retrievalVector");
        List<Float> vector = new ArrayList<>();
        List<Float> opposite = new ArrayList<>();
        for (Object value : raw) {
            float number = ((Number) value).floatValue();
            vector.add(number);
            opposite.add(-number);
        }
        assertEquals(1.0, scoreAgainst(hit.id(), vector), 1e-5);
        assertEquals(0.0, scoreAgainst(hit.id(), opposite), 1e-5);
    }

    @Test
    void demoReturnsExistingVersionedCandidatesWithIndependentRanks() throws Exception {
        RetrievalRequest request = RetrievalRequest.builder().queryText("设备召回评估")
                .behaviorId("integration-behavior").analysisRunId(UUID.randomUUID().toString())
                .assessmentId(1L).indicatorTopK(5).regulationTopK(10).filter(RetrievalFilter.none()).build();
        List<RetrievalResult> results = service.retrieve(request);
        assertType(results, RetrievalCandidateType.INDICATOR, ScoreType.COSINE_SIMILARITY, "t_indicator_test", 5);
        assertType(results, RetrievalCandidateType.REGULATION, ScoreType.HYBRID, "t_regulation_test", 10);
        boolean explainableRecall = false;
        for (RetrievalResult result : results) {
            if (result.getCandidateType() != RetrievalCandidateType.REGULATION) { continue; }
            Map source = client.get(g -> g.index("t_regulation_test").id(result.getCandidateId()), Map.class).source();
            String text = String.valueOf(source.get("name")) + String.valueOf(source.get("retrievalText"));
            explainableRecall |= text.contains("召回");
        }
        assertTrue(explainableRecall, "设备召回演示的法规 Top-10 应包含可回读的召回候选");
    }

    @Test
    void enabledPureGeneralScriptExecutesInsideBothSearchLanes() throws Exception {
        RetrievalFilter filter = RetrievalFilter.of(Collections.singleton("不存在的领域"), Collections.emptySet());
        RetrievalRequest request = RetrievalRequest.builder().queryText("设备召回评估")
                .behaviorId("filter-behavior").analysisRunId(UUID.randomUUID().toString())
                .assessmentId(2L).indicatorTopK(2).regulationTopK(2).filter(filter).build();
        List<RetrievalResult> results = service.retrieve(request);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(r -> "true".equals(r.getMatchedFilters().get("applied"))));
        for (RetrievalResult result : results) {
            String index = result.getCandidateType() == RetrievalCandidateType.INDICATOR
                    ? "t_indicator_test" : "t_regulation_test";
            Map source = client.get(g -> g.index(index).id(result.getCandidateId()), Map.class).source();
            Object domains = source.get("complianceDomain");
            assertTrue(domains == null || Collections.singletonList("综合类").equals(domains),
                    "不存在的领域只能保留元数据缺失或纯综合类候选");
        }
    }

    @Test
    void batchEmbedsOnceAndReturnsHydratedCandidateSnapshots() {
        AtomicInteger calls = new AtomicInteger();
        AiEmbeddingProvider counting = new AiEmbeddingProvider() {
            @Override public List<List<Float>> embed(List<String> texts, EmbeddingRole role) {
                calls.incrementAndGet();
                return embedding.embed(texts, role);
            }
            @Override public String modelId() { return embedding.modelId(); }
            @Override public int dimension() { return embedding.dimension(); }
        };
        RetrievalProperties retrieval = new RetrievalProperties();
        retrieval.setIndicatorIndex("t_indicator_test");
        retrieval.setRegulationIndex("t_regulation_test");
        RetrievalService batchService = new RetrievalService(client, counting, retrieval);
        String runId = UUID.randomUUID().toString();
        List<RetrievalRequest> requests = Arrays.asList(
                batchRequest("batch-1", runId, "设备召回评估"),
                batchRequest("batch-2", runId, "数据出境风险评估"));

        RetrievalBatchResponse response = batchService.retrieveBatch(RetrievalBatchRequest.builder()
                .scope(new com.riskwarning.common.dto.analysis.AnalysisScope(1L, 7L, runId))
                .requests(requests).build());

        assertEquals(1, calls.get());
        assertEquals(2, response.getItems().size());
        for (RetrievalBatchItem item : response.getItems()) {
            assertEquals(RetrievalBatchStatus.SUCCESS, item.getStatus());
            assertFalse(item.getCandidates().isEmpty());
            for (RetrievalCandidateSnapshot snapshot : item.getCandidates()) {
                assertNotNull(snapshot.getName());
                assertNotNull(snapshot.getRetrievalTextHash());
                if (snapshot.getResult().getCandidateType() == RetrievalCandidateType.REGULATION) {
                    assertNotNull(snapshot.getContent());
                    assertEquals(com.riskwarning.common.po.evidence.EvidenceChunk.sha256(snapshot.getContent()),
                            snapshot.getContentHash());
                }
            }
        }
    }

    private RetrievalRequest batchRequest(String behaviorId, String runId, String query) {
        return RetrievalRequest.builder().queryText(query).behaviorId(behaviorId)
                .analysisRunId(runId).assessmentId(7L).indicatorTopK(2).regulationTopK(2)
                .filter(RetrievalFilter.none()).build();
    }

    private double scoreAgainst(String id, List<Float> vector) throws Exception {
        Query ids = Query.of(q -> q.ids(i -> i.values(id)));
        SearchResponse<Map> response = client.search(s -> s.index("t_indicator_test").size(1)
                .source(src -> src.fetch(false)).knn(k -> k.field("retrievalVector")
                        .queryVector(vector).k(1).numCandidates(100).filter(ids)), Map.class);
        return response.hits().hits().get(0).score();
    }

    private void assertType(List<RetrievalResult> results, RetrievalCandidateType type, ScoreType scoreType,
                            String index, int expected) throws Exception {
        List<RetrievalResult> typed = new ArrayList<>();
        for (RetrievalResult result : results) { if (result.getCandidateType() == type) typed.add(result); }
        assertEquals(expected, typed.size());
        for (int i = 0; i < typed.size(); i++) {
            RetrievalResult result = typed.get(i);
            assertEquals(i + 1, result.getRank());
            assertEquals(scoreType, result.getScoreType());
            assertTrue(result.getScore() >= 0 && result.getScore() <= 1);
            assertEquals("bge-m3@2026-09-16-retrieval-v1", result.getEmbeddingVersion());
            assertTrue(client.exists(e -> e.index(index).id(result.getCandidateId())).value());
            if (i > 0) { assertTrue(typed.get(i - 1).getScore() >= result.getScore()); }
        }
    }

    private static String readEnv(String name) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".env"))) { current = current.getParent(); }
        if (current == null) { return null; }
        for (String line : Files.readAllLines(current.resolve(".env"), StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0 && line.substring(0, separator).trim().equals(name)) {
                return line.substring(separator + 1).trim();
            }
        }
        return null;
    }
}
