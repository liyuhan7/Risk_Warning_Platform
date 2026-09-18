package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.riskwarning.common.dto.retrieval.RetrievalBatchItem;
import com.riskwarning.common.dto.retrieval.RetrievalBatchStatus;
import com.riskwarning.common.dto.retrieval.RetrievalCandidateType;
import com.riskwarning.common.dto.retrieval.RetrievalBatchRequest;
import com.riskwarning.common.dto.retrieval.RetrievalFilter;
import com.riskwarning.common.dto.retrieval.RetrievalRequest;
import com.riskwarning.common.dto.retrieval.RetrievalResult;
import com.riskwarning.common.dto.retrieval.ScoreType;
import com.riskwarning.common.provider.AiEmbeddingProvider;
import com.riskwarning.knowledge.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalServiceTest {
    private RetrievalService service(AiEmbeddingProvider embedding) {
        when(embedding.dimension()).thenReturn(1024);
        when(embedding.modelId()).thenReturn("BAAI/bge-m3");
        return new RetrievalService(mock(ElasticsearchClient.class), embedding, new RetrievalProperties());
    }

    private RetrievalService service(RetrievalProperties.DenseScoreMode mode) {
        AiEmbeddingProvider embedding = mock(AiEmbeddingProvider.class);
        when(embedding.dimension()).thenReturn(1024);
        when(embedding.modelId()).thenReturn("BAAI/bge-m3");
        RetrievalProperties properties = new RetrievalProperties();
        properties.setDenseScoreMode(mode);
        return new RetrievalService(mock(ElasticsearchClient.class), embedding, properties);
    }

    @Test
    void keepsEsNormalizedScoreAndConvertsWholeRawCosineLane() {
        assertEquals(0.75, service(RetrievalProperties.DenseScoreMode.ES_NORMALIZED).normalizeDense(0.75), 1e-9);
        assertEquals(0.0, service(RetrievalProperties.DenseScoreMode.RAW_COSINE).normalizeDense(-1.0), 1e-9);
        assertEquals(0.5, service(RetrievalProperties.DenseScoreMode.RAW_COSINE).normalizeDense(0.0), 1e-9);
        assertEquals(1.0, service(RetrievalProperties.DenseScoreMode.RAW_COSINE).normalizeDense(1.0), 1e-9);
        assertThrows(IllegalStateException.class,
                () -> service(RetrievalProperties.DenseScoreMode.ES_NORMALIZED).normalizeDense(-0.1));
        assertThrows(IllegalStateException.class,
                () -> service(RetrievalProperties.DenseScoreMode.RAW_COSINE).normalizeDense(1.1));
        assertThrows(IllegalStateException.class,
                () -> service(RetrievalProperties.DenseScoreMode.ES_NORMALIZED).normalizeDense(Double.NaN));
    }

    @Test
    void fusesOverUnionUsingBm25MaximumAndZeroForMissingLane() {
        Map<String, Double> dense = new LinkedHashMap<>();
        dense.put("dense-only", 0.8);
        dense.put("both", 0.6);
        Map<String, Double> bm25 = new LinkedHashMap<>();
        bm25.put("both", 10.0);
        bm25.put("bm25-only", 5.0);

        Map<String, Double> result = service(RetrievalProperties.DenseScoreMode.ES_NORMALIZED).fuse(dense, bm25);
        assertEquals(new LinkedHashSet<>(Arrays.asList("dense-only", "both", "bm25-only")), result.keySet());
        assertEquals(0.4, result.get("dense-only"), 1e-9);
        assertEquals(0.8, result.get("both"), 1e-9);
        assertEquals(0.25, result.get("bm25-only"), 1e-9);
    }

    @Test
    void zeroOrAbsentBm25MakesWholeLexicalLaneZero() {
        Map<String, Double> dense = Collections.singletonMap("a", 0.8);
        RetrievalService service = service(RetrievalProperties.DenseScoreMode.ES_NORMALIZED);
        assertEquals(0.4, service.fuse(dense, Collections.emptyMap()).get("a"), 1e-9);
        assertEquals(0.4, service.fuse(dense, Collections.singletonMap("a", 0.0)).get("a"), 1e-9);
    }

    @Test
    void sortsTiedScoresByCandidateIdAndAssignsContinuousRanks() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("candidate-z", 0.8);
        scores.put("candidate-b", 0.8);
        scores.put("candidate-high", 0.9);
        scores.put("candidate-a", 0.8);

        RetrievalRequest request = RetrievalRequest.builder()
                .queryText("设备召回评估")
                .behaviorId("behavior-1")
                .analysisRunId("run-1")
                .assessmentId(1L)
                .filter(RetrievalFilter.none())
                .build();
        List<RetrievalResult> output = new ArrayList<>();

        service(RetrievalProperties.DenseScoreMode.ES_NORMALIZED).append(
                output, scores, 4, RetrievalCandidateType.INDICATOR,
                ScoreType.COSINE_SIMILARITY, request, LocalDateTime.of(2026, 9, 17, 12, 0));

        assertEquals(Arrays.asList("candidate-high", "candidate-a", "candidate-b", "candidate-z"),
                Arrays.asList(output.get(0).getCandidateId(), output.get(1).getCandidateId(),
                        output.get(2).getCandidateId(), output.get(3).getCandidateId()));
        assertEquals(Arrays.asList(1, 2, 3, 4),
                Arrays.asList(output.get(0).getRank(), output.get(1).getRank(),
                        output.get(2).getRank(), output.get(3).getRank()));
    }

    @Test
    void rejectsOversizedOrDuplicateBatchBeforeEmbedding() {
        AiEmbeddingProvider embedding = mock(AiEmbeddingProvider.class);
        RetrievalService service = service(embedding);
        List<RetrievalRequest> oversized = new ArrayList<>();
        for (int i = 0; i < 17; i++) { oversized.add(request("behavior-" + i)); }

        assertThrows(IllegalArgumentException.class, () -> service.retrieveBatch(batch(oversized)));
        assertThrows(IllegalArgumentException.class, () -> service.retrieveBatch(
                batch(Arrays.asList(request("same"), request("same")))));
        verify(embedding, never()).embed(anyList(), any());
    }

    @Test
    void rejectsEnvelopeScopeMismatchBeforeEmbedding() {
        AiEmbeddingProvider embedding = mock(AiEmbeddingProvider.class);
        RetrievalService service = service(embedding);
        RetrievalRequest mismatched = request("behavior-1");
        mismatched.setAnalysisRunId("other-run");

        assertThrows(IllegalArgumentException.class,
                () -> service.retrieveBatch(batch(Collections.singletonList(mismatched))));
        verify(embedding, never()).embed(anyList(), any());
    }

    private RetrievalBatchRequest batch(List<RetrievalRequest> requests) {
        return RetrievalBatchRequest.builder()
                .scope(new com.riskwarning.common.dto.analysis.AnalysisScope(1L, 2L, "run-1"))
                .requests(requests).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptySearchRetainsModelVersionAndActualFilterState() throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        AiEmbeddingProvider embedding = mock(AiEmbeddingProvider.class);
        when(embedding.dimension()).thenReturn(1024);
        when(embedding.modelId()).thenReturn("BAAI/bge-m3");
        when(embedding.embed(anyList(), any())).thenReturn(Collections.singletonList(vector()));
        SearchResponse<Map> empty = new SearchResponse.Builder<Map>().took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(Collections.emptyList())).build();
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(empty);
        RetrievalProperties properties = new RetrievalProperties();
        RetrievalBatchItem item = new RetrievalService(client, embedding, properties)
                .retrieveBatch(batch(Collections.singletonList(request("b")))).getItems().get(0);
        assertEquals(RetrievalBatchStatus.NO_CANDIDATES, item.getStatus());
        assertEquals("BAAI/bge-m3", item.getEmbeddingModel());
        assertEquals(properties.getEmbeddingVersion(), item.getEmbeddingVersion());
        assertEquals("false", item.getMatchedFilters().get("applied"));
        assertTrue(item.getCandidates().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotReadFailurePreservesRealScoredIdsWithExplicitUnavailableStatus() throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        AiEmbeddingProvider embedding = mock(AiEmbeddingProvider.class);
        when(embedding.dimension()).thenReturn(1024);
        when(embedding.modelId()).thenReturn("BAAI/bge-m3");
        when(embedding.embed(anyList(), any())).thenReturn(Collections.singletonList(vector()));
        SearchResponse<Map> scored = new SearchResponse.Builder<Map>().took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hit -> hit.index("knowledge").id("real-es-id").score(0.8))).build();
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(scored, scored, scored)
                .thenThrow(new IOException("snapshot read failed"));
        RetrievalBatchItem item = new RetrievalService(client, embedding, new RetrievalProperties())
                .retrieveBatch(batch(Collections.singletonList(request("b")))).getItems().get(0);
        assertEquals(RetrievalBatchStatus.SNAPSHOT_UNAVAILABLE, item.getStatus());
        assertEquals(2, item.getCandidates().size());
        item.getCandidates().forEach(candidate -> {
            assertEquals("real-es-id", candidate.getResult().getCandidateId());
            assertNotNull(candidate.getResult().getScore());
            assertNull(candidate.getName());
            assertNull(candidate.getContent());
        });
    }

    private List<Float> vector() {
        List<Float> vector = new ArrayList<>(Collections.nCopies(1024, 0.0f));
        vector.set(0, 1.0f);
        return vector;
    }

    private RetrievalRequest request(String behaviorId) {
        return RetrievalRequest.builder().queryText("设备召回评估").behaviorId(behaviorId)
                .assessmentId(2L).analysisRunId("run-1").filter(RetrievalFilter.none()).build();
    }
}
