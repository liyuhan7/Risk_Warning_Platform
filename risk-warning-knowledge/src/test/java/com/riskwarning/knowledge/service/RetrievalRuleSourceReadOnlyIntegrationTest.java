package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.provider.*;
import com.riskwarning.knowledge.config.RetrievalProperties;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用本地已有索引与已有向量验证实际回读，不调用模型或写入/删除 ES 文档。 */
@EnabledIfSystemProperty(named = "analysis.source.integration", matches = "true")
class RetrievalRuleSourceReadOnlyIntegrationTest {
    @Test
    @SuppressWarnings("unchecked")
    void actualRetrievalFreezesRuleProjectionFromReturnedIndicatorDocument() throws Exception {
        try (RestClientTransport transport = new RestClientTransport(RestClient.builder(HttpHost.create("http://localhost:9200")).build(),
                new JacksonJsonpMapper(new ObjectMapper()))) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            Hit<Map> sample = client.search(s -> s.index("t_indicator_test").size(1)
                    .query(q -> q.ids(ids -> ids.values("4a469621-daba-4ef1-bc0e-5c7983ebc010")))
                    .source(src -> src.filter(f -> f.includes("retrievalVector","embeddingModel","embeddingVersion"))), Map.class)
                    .hits().hits().get(0);
            List<Float> vector = new ArrayList<>();
            for (Object value : (List<?>) sample.source().get("retrievalVector")) vector.add(((Number) value).floatValue());
            AiEmbeddingProvider embedding = mock(AiEmbeddingProvider.class);
            when(embedding.dimension()).thenReturn(1024); when(embedding.modelId()).thenReturn((String) sample.source().get("embeddingModel"));
            when(embedding.embed(anyList(),eq(EmbeddingRole.QUERY))).thenReturn(Collections.singletonList(vector));
            RetrievalProperties properties = new RetrievalProperties();
            properties.setIndicatorIndex("t_indicator_test"); properties.setRegulationIndex("t_regulation_test");
            properties.setEmbeddingVersion((String) sample.source().get("embeddingVersion"));
            properties.setIndicatorTopK(10); properties.setRegulationTopK(1);
            RetrievalRequest request = RetrievalRequest.builder().behaviorId("source-read-only").assessmentId(2L).analysisRunId("source-read-only-run")
                    .queryText("工程来源快照只读验证").filter(RetrievalFilter.none()).build();
            RetrievalBatchItem item = new RetrievalService(client,embedding,properties).retrieveBatch(RetrievalBatchRequest.builder()
                    .scope(new AnalysisScope(1L,2L,"source-read-only-run")).requests(Collections.singletonList(request)).build()).getItems().get(0);
            assertEquals(RetrievalBatchStatus.SUCCESS,item.getStatus());
            int indicatorCount = 0, completeRuleCount = 0;
            for (RetrievalCandidateSnapshot snapshot : item.getCandidates()) {
                if (snapshot.getResult().getCandidateType() != RetrievalCandidateType.INDICATOR) {
                    assertNull(snapshot.getSourceRule()); continue;
                }
                indicatorCount++;
                String id = snapshot.getResult().getCandidateId();
                Hit<Map> hit = client.search(s -> s.index("t_indicator_test").size(1).query(q -> q.ids(ids -> ids.values(id))),Map.class)
                        .hits().hits().get(0);
                assertEquals(IndicatorRuleSourceSnapshot.capture(id,hit.source()),snapshot.getSourceRule());
                assertEquals(id,snapshot.getSourceRule().path("indicatorId").asText());
                if (snapshot.getSourceRule().path("calculationRule").isObject() && snapshot.getSourceRule().path("maxScore").isNumber()) completeRuleCount++;
                assertFalse(snapshot.getSourceRule().has("retrievalVector"));
            }
            assertTrue(indicatorCount > 0); assertTrue(completeRuleCount > 0, "至少一个实际候选含完整计分来源");
            verify(embedding,times(1)).embed(anyList(),eq(EmbeddingRole.QUERY));
        }
    }
}
