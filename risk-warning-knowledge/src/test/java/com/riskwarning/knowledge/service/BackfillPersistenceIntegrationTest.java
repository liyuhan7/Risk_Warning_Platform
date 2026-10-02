package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.config.EmbeddingProviderProperties;
import com.riskwarning.common.provider.*;
import com.riskwarning.common.result.Result;
import com.riskwarning.knowledge.config.RetrievalProperties;
import com.riskwarning.knowledge.controller.RetrievalBackfillController;
import com.riskwarning.knowledge.dto.*;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.ResponseEntity;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 随机隔离 ES 索引验证回填覆盖和部分失败，向量在本地确定性生成。 */
@EnabledIfSystemProperty(named = "retrieval.backfill.persistence.test", matches = "true")
class BackfillPersistenceIntegrationTest {
    @Test void partialFailureRetryAndSkipMatchActualPersistedFields() throws Exception {
        String index = "backfill_contract_it_" + UUID.randomUUID().toString().replace("-", "");
        String endpoint = System.getenv("ANALYSIS_TEST_ES_URL");
        RestClientTransport transport = new RestClientTransport(RestClient.builder(
                HttpHost.create(endpoint == null ? "http://localhost:9200" : endpoint)).build(), new JacksonJsonpMapper());
        ElasticsearchClient es = new ElasticsearchClient(transport);
        boolean created = false;
        try {
            assertFalse(es.indices().exists(r -> r.index(index)).value());
            es.indices().create(r -> r.index(index).mappings(m -> m.properties("retrievalVector",
                    p -> p.denseVector(v -> v.dims(1024).index(true).similarity("cosine")))));
            created = true;
            es.index(r -> r.index(index).id("good").document(Collections.singletonMap("name", "受控指标"))
                    .refresh(Refresh.True));
            es.index(r -> r.index(index).id("bad").document(Collections.emptyMap()).refresh(Refresh.True));
            AiEmbeddingProvider embedding = new AiEmbeddingProvider() {
                @Override public List<List<Float>> embed(List<String> texts, EmbeddingRole role) {
                    List<Float> vector = new ArrayList<>(Collections.nCopies(1024, 0.0f)); vector.set(0, 1.0f);
                    return Collections.nCopies(texts.size(), vector);
                }
                @Override public String modelId() { return "controlled-embedding"; }
                @Override public int dimension() { return 1024; }
            };
            RetrievalBackfillController controller = new RetrievalBackfillController(new RetrievalBackfillService(es,
                    embedding, new RetrievalTextBuilder(), new RetrievalProperties(), new EmbeddingProviderProperties()));
            RetrievalBackfillRequest request = new RetrievalBackfillRequest();
            request.setTarget(RetrievalBackfillRequest.Target.INDICATOR); request.setIndicatorIndex(index);
            ResponseEntity<?> partial = controller.backfill(request);
            assertEquals(500, partial.getStatusCodeValue());
            RetrievalBackfillResult first = (RetrievalBackfillResult) ((Result<?>) partial.getBody()).getData();
            assertEquals(2, first.getTotal()); assertEquals(1, first.getUpdated()); assertEquals(1, first.getFailed());
            assertEquals(1, coverage(es, index));
            Map good = es.get(r -> r.index(index).id("good"), Map.class).source();
            assertNotNull(good); assertEquals(1024, ((List<?>) good.get("retrievalVector")).size());
            assertEquals("controlled-embedding", good.get("embeddingModel"));
            assertNotNull(good.get("retrievalTextHash")); assertNotNull(good.get("embeddingVersion"));
            assertNotNull(good.get("retrievalText"));
            assertEquals(good.get("retrievalText"), good.get("retrievalTextIk"));
            assertEquals(1, es.count(r -> r.index(index).query(q -> q.match(m -> m
                    .field("retrievalTextIk").query((String) good.get("retrievalText"))))).count());
            RetrievalBackfillResult replay = (RetrievalBackfillResult) ((Result<?>) controller.backfill(request).getBody()).getData();
            assertEquals(0, replay.getUpdated()); assertEquals(1, replay.getSkipped()); assertEquals(1, replay.getFailed());
            es.index(r -> r.index(index).id("bad").document(Collections.singletonMap("name", "补齐文本的受控指标"))
                    .refresh(Refresh.True));
            ResponseEntity<?> recovered = controller.backfill(request);
            assertEquals(200, recovered.getStatusCodeValue());
            RetrievalBackfillResult finalResult = (RetrievalBackfillResult) recovered.getBody();
            assertEquals(1, finalResult.getUpdated()); assertEquals(1, finalResult.getSkipped()); assertEquals(0, finalResult.getFailed());
            assertEquals(2, coverage(es, index));
        } finally {
            try {
                if (created) {
                    Map<String, Object> backup = new LinkedHashMap<>(); backup.put("index", index);
                    backup.put("good", es.get(r -> r.index(index).id("good"), Map.class).source());
                    backup.put("bad", es.get(r -> r.index(index).id("bad"), Map.class).source());
                    backup.put("vectorDimensions", 1024); backup.put("vectorSimilarity", "cosine");
                    Path root = Paths.get("").toAbsolutePath();
                    while (!Files.isDirectory(root.resolve("risk-warning-knowledge"))) root = root.getParent();
                    Files.createDirectories(root.resolve("tmp"));
                    Files.write(root.resolve("tmp/" + index + "-backup.json"),
                            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(backup));
                    assertTrue(es.indices().delete(r -> r.index(index)).acknowledged());
                    assertFalse(es.indices().exists(r -> r.index(index)).value());
                }
            } finally { transport.close(); }
        }
    }
    private long coverage(ElasticsearchClient es, String index) throws Exception {
        return es.count(r -> r.index(index).query(q -> q.bool(b -> b
                .filter(f -> f.exists(e -> e.field("retrievalVector")))
                .filter(f -> f.exists(e -> e.field("retrievalTextHash")))
                .filter(f -> f.exists(e -> e.field("retrievalText")))
                .filter(f -> f.exists(e -> e.field("retrievalTextIk")))
                .filter(f -> f.exists(e -> e.field("embeddingModel")))
                .filter(f -> f.exists(e -> e.field("embeddingVersion")))))).count();
    }
}
