package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.config.EmbeddingProviderProperties;
import com.riskwarning.common.po.indicator.Indicator;
import com.riskwarning.common.po.regulation.Regulation;
import com.riskwarning.common.provider.OpenAiCompatibleEmbeddingProvider;
import com.riskwarning.knowledge.config.RetrievalProperties;
import com.riskwarning.knowledge.dto.RetrievalBackfillRequest;
import com.riskwarning.knowledge.dto.RetrievalBackfillResult;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 显式开启的真实 ES 与真实 Embedding 回填验收测试。 */
@EnabledIfSystemProperty(named = "retrieval.integration", matches = "true")
class RetrievalBackfillServiceIntegrationTest {
    private static final String INITIAL_VERSION = "bge-m3@2026-09-16-retrieval-v1";
    private static final String CHANGED_VERSION = INITIAL_VERSION + "-it-rerun";

    @Test
    void backfillsBothTemporaryIndicesAndRecomputesWhenVersionChanges() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String indicatorIndex = "rw_retrieval_backfill_indicator_" + suffix;
        String regulationIndex = "rw_retrieval_backfill_regulation_" + suffix;
        RestClient restClient = null;
        RestClientTransport transport = null;
        ElasticsearchClient client = null;
        try {
            String apiKey = readEnv("EMBEDDING_API_KEY");
            assertNotNull(apiKey, "根 .env 缺少 EMBEDDING_API_KEY");
            assertFalse(apiKey.trim().isEmpty(), "根 .env 的 EMBEDDING_API_KEY 为空");

            restClient = RestClient.builder(HttpHost.create("http://localhost:9200")).build();
            transport = new RestClientTransport(restClient, new JacksonJsonpMapper(new ObjectMapper()));
            client = new ElasticsearchClient(transport);
            createIndex(client, indicatorIndex, true);
            createIndex(client, regulationIndex, false);

            Map<String, Object> indicator = new LinkedHashMap<>();
            indicator.put("name", "安全生产指标");
            indicator.put("description", "检查生产现场安全风险");
            indicator.put("tags", Arrays.asList("安全", "生产"));
            Map<String, Object> regulation = new LinkedHashMap<>();
            regulation.put("name", "安全生产管理办法");
            regulation.put("fullText", "企业应当建立安全生产责任制并定期开展风险排查。");
            client.index(i -> i.index(indicatorIndex).id("indicator-1").document(indicator).refresh(Refresh.True));
            client.index(i -> i.index(regulationIndex).id("regulation-1").document(regulation).refresh(Refresh.True));

            EmbeddingProviderProperties embeddingProperties = new EmbeddingProviderProperties();
            embeddingProperties.setApiKey(apiKey);
            OpenAiCompatibleEmbeddingProvider embedding =
                    new OpenAiCompatibleEmbeddingProvider(embeddingProperties);
            RetrievalProperties retrievalProperties = new RetrievalProperties();
            retrievalProperties.setEmbeddingVersion(INITIAL_VERSION);
            RetrievalBackfillService service = new RetrievalBackfillService(
                    client, embedding, new RetrievalTextBuilder(), retrievalProperties, embeddingProperties);

            RetrievalBackfillRequest request = new RetrievalBackfillRequest();
            request.setTarget(RetrievalBackfillRequest.Target.BOTH);
            request.setIndicatorIndex(indicatorIndex);
            request.setRegulationIndex(regulationIndex);

            RetrievalBackfillResult first = service.backfill(request);
            assertEquals(2, first.getTotal());
            assertEquals(2, first.getUpdated());
            assertEquals(0, first.getSkipped());
            assertEquals(0, first.getFailed());
            assertBackfilled(client, indicatorIndex, "indicator-1", true, embedding.modelId(), INITIAL_VERSION,
                    indicator, regulationText(indicator));
            assertBackfilled(client, regulationIndex, "regulation-1", false, embedding.modelId(), INITIAL_VERSION,
                    regulation, regulationText(regulation));

            RetrievalBackfillResult second = service.backfill(request);
            assertEquals(2, second.getTotal());
            assertEquals(0, second.getUpdated());
            assertEquals(2, second.getSkipped());
            assertEquals(0, second.getFailed());

            retrievalProperties.setEmbeddingVersion(CHANGED_VERSION);
            RetrievalBackfillResult changedVersion = service.backfill(request);
            assertEquals(2, changedVersion.getTotal());
            assertEquals(2, changedVersion.getUpdated());
            assertEquals(0, changedVersion.getSkipped());
            assertEquals(0, changedVersion.getFailed());
            assertBackfilled(client, indicatorIndex, "indicator-1", true, embedding.modelId(), CHANGED_VERSION,
                    indicator, regulationText(indicator));
            assertBackfilled(client, regulationIndex, "regulation-1", false, embedding.modelId(), CHANGED_VERSION,
                    regulation, regulationText(regulation));
        } finally {
            if (client != null) {
                try {
                    client.indices().delete(d -> d.index(indicatorIndex).ignoreUnavailable(true));
                } catch (Exception ignored) {
                    // 仅清理本测试随机命名的索引，删除失败不掩盖主断言。
                }
                try {
                    client.indices().delete(d -> d.index(regulationIndex).ignoreUnavailable(true));
                } catch (Exception ignored) {
                    // 仅清理本测试随机命名的索引，删除失败不掩盖主断言。
                }
            }
            if (transport != null) {
                transport.close();
            } else if (restClient != null) {
                restClient.close();
            }
        }
    }

    private void createIndex(ElasticsearchClient client, String index, boolean indicator) throws Exception {
        client.indices().create(c -> c.index(index).mappings(m -> m
                .properties("name", textProperty())
                .properties("description", textProperty())
                .properties("tags", keywordProperty())
                .properties("fullText", textProperty())
                .properties("retrievalText", textProperty())
                .properties("retrievalVector", p -> p.denseVector(v -> v.dims(1024).similarity("cosine")))
                .properties("retrievalTextHash", keywordProperty())
                .properties("embeddingModel", keywordProperty())
                .properties("embeddingVersion", keywordProperty())));
        assertTrue(client.indices().exists(e -> e.index(index)).value());
    }

    private Property textProperty() {
        return Property.of(p -> p.text(t -> t));
    }

    private Property keywordProperty() {
        return Property.of(p -> p.keyword(k -> k));
    }

    private void assertBackfilled(ElasticsearchClient client, String index, String id, boolean indicator,
                                  String model, String version, Map<String, Object> source, String expectedText)
            throws Exception {
        GetResponse<Map> response = client.get(g -> g.index(index).id(id), Map.class);
        assertTrue(response.found());
        Map<String, Object> actual = response.source();
        assertNotNull(actual);
        for (String field : Arrays.asList("retrievalText", "retrievalVector", "retrievalTextHash",
                "embeddingModel", "embeddingVersion")) {
            assertTrue(actual.containsKey(field), "缺少回填字段 " + field);
            assertNotNull(actual.get(field), "回填字段为空 " + field);
        }
        assertEquals(expectedText, actual.get("retrievalText"));
        assertEquals(1024, ((List<?>) actual.get("retrievalVector")).size());
        assertEquals(model, actual.get("embeddingModel"));
        assertEquals(version, actual.get("embeddingVersion"));
        assertEquals(RetrievalBackfillService.sha256(expectedText), actual.get("retrievalTextHash"));
    }

    private String regulationText(Map<String, Object> source) {
        RetrievalTextBuilder builder = new RetrievalTextBuilder();
        if (source.containsKey("description")) {
            return builder.indicatorRetrievalText(Indicator.builder()
                    .name((String) source.get("name"))
                    .description((String) source.get("description"))
                    .tags((List<String>) source.get("tags"))
                    .build());
        }
        return builder.regulationRetrievalText(Regulation.builder()
                .name((String) source.get("name"))
                .fullText((String) source.get("fullText"))
                .build());
    }

    private static String readEnv(String name) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".env"))) {
            current = current.getParent();
        }
        if (current == null) {
            return null;
        }
        for (String line : Files.readAllLines(current.resolve(".env"), StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0 && line.substring(0, separator).trim().equals(name)) {
                return line.substring(separator + 1).trim();
            }
        }
        return null;
    }
}
