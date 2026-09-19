package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.riskwarning.common.config.EmbeddingProviderProperties;
import com.riskwarning.common.provider.AiEmbeddingProvider;
import com.riskwarning.common.provider.EmbeddingRole;
import com.riskwarning.knowledge.config.RetrievalProperties;
import com.riskwarning.knowledge.dto.RetrievalBackfillRequest;
import com.riskwarning.knowledge.dto.RetrievalBackfillResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalBackfillServiceTest {
    private static final String MODEL = "model-1";
    private static final String VERSION = "bge-m3@2026-09-16-retrieval-v1";

    @Test
    void textAndHashAreDeterministic() {
        RetrievalTextBuilder builder = new RetrievalTextBuilder();
        Map<String, Object> source = indicatorSource();
        String text1 = builder.indicatorRetrievalText(com.riskwarning.common.po.indicator.Indicator.builder()
                .name("指标").description("描述").tags(Arrays.asList("安全", "环保")).build());
        String text2 = builder.indicatorRetrievalText(com.riskwarning.common.po.indicator.Indicator.builder()
                .name((String) source.get("name")).description((String) source.get("description"))
                .tags((List<String>) source.get("tags")).build());
        assertEquals("指标\n描述\n安全 环保", text1);
        assertEquals(text1, text2);
        assertEquals(RetrievalBackfillService.sha256(text1), RetrievalBackfillService.sha256(text2));
        assertEquals(64, RetrievalBackfillService.sha256(text1).length());
    }

    @Test
    void sameHashModelVersionAndVectorSkip() throws IOException {
        AiEmbeddingProvider embedding = embedding();
        Map<String, Object> source = indicatorSource();
        String text = "指标\n描述\n安全 环保";
        source.put("retrievalTextHash", RetrievalBackfillService.sha256(text));
        source.put("embeddingModel", MODEL);
        source.put("embeddingVersion", VERSION);
        source.put("retrievalVector", Collections.singletonList(1.0f));
        RetrievalBackfillResult result = service(searchClient("doc-1", source), embedding)
                .backfill(request(false));
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getUpdated());
        verify(embedding, never()).embed(anyList(), any());
    }

    @Test
    void changedHashOrVersionRecomputes() throws IOException {
        for (String field : Arrays.asList("retrievalTextHash", "embeddingVersion")) {
            AiEmbeddingProvider embedding = embedding();
            when(embedding.embed(anyList(), eq(EmbeddingRole.DOCUMENT)))
                    .thenThrow(new IllegalStateException("stop-after-recompute"));
            Map<String, Object> source = indicatorSource();
            source.put("retrievalTextHash", field.equals("retrievalTextHash") ? "old" :
                    RetrievalBackfillService.sha256("指标\n描述\n安全 环保"));
            source.put("embeddingModel", MODEL);
            source.put("embeddingVersion", field.equals("embeddingVersion") ? "old" : VERSION);
            source.put("retrievalVector", Collections.singletonList(1.0f));
            RetrievalBackfillResult result = service(searchClient("doc-1", source), embedding)
                    .backfill(request(false));
            assertEquals(0, result.getSkipped());
            assertEquals(1, result.getFailed());
            verify(embedding).embed(anyList(), eq(EmbeddingRole.DOCUMENT));
        }
    }

    @Test
    void forceRecomputesEvenWhenMetadataMatches() throws IOException {
        AiEmbeddingProvider embedding = embedding();
        when(embedding.embed(anyList(), eq(EmbeddingRole.DOCUMENT)))
                .thenThrow(new IllegalStateException("forced"));
        Map<String, Object> source = indicatorSource();
        source.put("retrievalTextHash", RetrievalBackfillService.sha256("指标\n描述\n安全 环保"));
        source.put("embeddingModel", MODEL);
        source.put("embeddingVersion", VERSION);
        source.put("retrievalVector", Collections.singletonList(1.0f));
        RetrievalBackfillResult result = service(searchClient("forced-id", source), embedding)
                .backfill(request(true));
        assertEquals(0, result.getSkipped());
        assertEquals(1, result.getFailed());
        assertEquals("forced-id", result.getFailures().get(0).getId());
    }

    @Test
    void embeddingFailureReturnsEveryFailedIdAndReason() throws IOException {
        AiEmbeddingProvider embedding = embedding();
        when(embedding.embed(anyList(), eq(EmbeddingRole.DOCUMENT)))
                .thenThrow(new IllegalStateException("provider unavailable"));
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.search(any(SearchRequest.class), eq(Map.class)))
                .thenReturn(searchResponse("a", indicatorSource(), "b", indicatorSource()));
        RetrievalBackfillResult result = service(client, embedding).backfill(request(false));
        assertEquals(2, result.getFailed());
        assertEquals(Arrays.asList("a", "b"), Arrays.asList(
                result.getFailures().get(0).getId(), result.getFailures().get(1).getId()));
        assertTrue(result.getFailures().get(0).getReason().contains("provider unavailable"));
    }

    @Test
    void updateContainsExactlyFiveRequiredFields() {
        List<Float> vector = new ArrayList<>(Collections.nCopies(1024, 0.0f));
        RetrievalBackfillService.Plan plan = new RetrievalBackfillService.Plan(
                "idx", "id", "text", "hash", MODEL, VERSION);
        Map<String, Object> fields = RetrievalBackfillService.updateFields(plan, vector);
        assertEquals(new LinkedHashSet<>(Arrays.asList("retrievalText", "retrievalVector",
                "retrievalTextHash", "embeddingModel", "embeddingVersion")), fields.keySet());
        assertSame(vector, fields.get("retrievalVector"));
        assertEquals(MODEL, fields.get("embeddingModel"));
    }

    @Test
    void rejectsMissingBlankOrSameTargetIndexes() {
        RetrievalBackfillService service = service(mock(ElasticsearchClient.class), embedding());
        RetrievalBackfillRequest missing = new RetrievalBackfillRequest();
        missing.setTarget(RetrievalBackfillRequest.Target.BOTH);
        missing.setIndicatorIndex("ind");
        assertThrows(IllegalArgumentException.class, () -> service.backfill(missing));
        RetrievalBackfillRequest blank = request(false);
        blank.setIndicatorIndex(" ");
        assertThrows(IllegalArgumentException.class, () -> service.backfill(blank));
        RetrievalBackfillRequest same = request(false);
        same.setIndicatorIndex("same");
        same.setRegulationIndex("same");
        assertThrows(IllegalArgumentException.class, () -> service.backfill(same));

        RetrievalBackfillRequest indicatorOnly = request(false);
        indicatorOnly.setRegulationIndex(null);
        assertDoesNotThrow(() -> service.validateRequest(indicatorOnly));
        RetrievalBackfillRequest regulationOnly = new RetrievalBackfillRequest();
        regulationOnly.setTarget(RetrievalBackfillRequest.Target.REGULATION);
        regulationOnly.setRegulationIndex("reg");
        assertDoesNotThrow(() -> service.validateRequest(regulationOnly));
    }

    private RetrievalBackfillService service(ElasticsearchClient client, AiEmbeddingProvider embedding) {
        EmbeddingProviderProperties embeddingProperties = new EmbeddingProviderProperties();
        embeddingProperties.setBatchSize(16);
        embeddingProperties.setMaxAttempts(2);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        return new RetrievalBackfillService(client, embedding, new RetrievalTextBuilder(),
                retrievalProperties, embeddingProperties);
    }

    private AiEmbeddingProvider embedding() {
        AiEmbeddingProvider embedding = mock(AiEmbeddingProvider.class);
        when(embedding.dimension()).thenReturn(1024);
        when(embedding.modelId()).thenReturn(MODEL);
        return embedding;
    }

    private ElasticsearchClient searchClient(String id, Map<String, Object> source) throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(searchResponse(id, source));
        return client;
    }

    private SearchResponse<Map> searchResponse(Object... idSources) {
        SearchResponse.Builder<Map> builder = new SearchResponse.Builder<Map>().took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0));
        return builder.hits(h -> {
            for (int i = 0; i < idSources.length; i += 2) {
                String id = (String) idSources[i];
                Map source = (Map) idSources[i + 1];
                h.hits(hit -> hit.index("ind-index").id(id).source(source));
            }
            return h;
        }).build();
    }

    private RetrievalBackfillRequest request(boolean force) {
        RetrievalBackfillRequest request = new RetrievalBackfillRequest();
        request.setIndicatorIndex("ind-index");
        request.setRegulationIndex("reg-index");
        request.setTarget(RetrievalBackfillRequest.Target.INDICATOR);
        request.setForce(force);
        return request;
    }

    private Map<String, Object> indicatorSource() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", "指标");
        source.put("description", "描述");
        source.put("tags", Arrays.asList("安全", "环保"));
        return source;
    }
}
