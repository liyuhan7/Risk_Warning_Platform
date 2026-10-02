package com.riskwarning.knowledge.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.exception.*;
import com.riskwarning.common.provider.*;
import com.riskwarning.knowledge.config.RetrievalProperties;
import com.riskwarning.knowledge.service.RetrievalService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际检索 HTTP 使用生产 Controller/Service，Embedding 与 ES 为受控替身。 */
@SpringBootTest(classes = RetrievalHttpContractTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"retrieval.enabled=true", "embedding.enabled=true", "spring.cloud.bootstrap.enabled=false",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false"})
class RetrievalHttpContractTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @ComponentScan(basePackageClasses = RetrievalController.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(org.springframework.web.bind.annotation.RestController.class))
    @Import({RetrievalService.class, RetrievalProperties.class,
            GlobalExceptionHandler.class, ApiErrorController.class})
    static class Server {
        @Bean ControlledEmbedding embedding() { return new ControlledEmbedding(); }
    }
    static class ControlledEmbedding implements AiEmbeddingProvider {
        RuntimeException failure;
        boolean wrongCount;
        int calls;
        @Override public List<List<Float>> embed(List<String> texts, EmbeddingRole role) {
            calls++;
            if (failure != null) throw failure;
            if (wrongCount) return Collections.emptyList();
            List<Float> vector = new ArrayList<>(Collections.nCopies(1024, 0.0f)); vector.set(0, 1.0f);
            return Collections.nCopies(texts.size(), vector);
        }
        @Override public String modelId() { return "controlled-embedding"; }
        @Override public int dimension() { return 1024; }
    }
    @Autowired private TestRestTemplate http;
    @Autowired private ControlledEmbedding embedding;
    @MockBean private ElasticsearchClient es;
    @MockBean private com.riskwarning.knowledge.service.RetrievalBackfillService backfill;

    @Test void removedVectorEndpointsAreNotRegistered() {
        for (String path : Arrays.asList("/test/vectorization/batch", "/test/vectorization/search/indicators", "/vectorization/indicators")) {
            ResponseEntity<JsonNode> response = http.postForEntity(path, Collections.emptyMap(), JsonNode.class);
            assertEquals(404, response.getStatusCodeValue());
            assertEquals(404, response.getBody().path("code").asInt());
        }
    }
    @BeforeEach void setup() throws Exception {
        embedding.failure = null; embedding.wrongCount = false; embedding.calls = 0;
        SearchResponse<Map> empty = new SearchResponse.Builder<Map>().took(1).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(Collections.emptyList())).build();
        doReturn(empty).when(es).search(any(SearchRequest.class), eq(Map.class));
    }
    @Test void successfulNoCandidatesPreservesVersionedBatchDto() {
        JsonNode body = post(batch(), 200);
        assertEquals("1.0", body.path("schemaVersion").asText()); assertFalse(body.has("code"));
        assertEquals("NO_CANDIDATES", body.path("items").get(0).path("status").asText());
        assertTrue(body.path("items").get(0).path("candidates").isEmpty());
        assertEquals(1, embedding.calls);
    }
    @Test void successfulCandidatePreservesRankScopeAndFrozenContent() throws Exception {
        Map<String, Object> source = new HashMap<>(); source.put("name", "受控指标");
        source.put("description", "冻结的候选正文"); source.put("maxScore", 10.0);
        SearchResponse<Map> candidate = new SearchResponse.Builder<Map>().took(1).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(hit -> hit.index("t_indicator").id("indicator").score(0.8).source(source))).build();
        doAnswer(call -> {
            SearchRequest request = call.getArgument(0);
            if (request.index().contains("t_indicator")) return candidate;
            return new SearchResponse.Builder<Map>().took(1).timedOut(false)
                    .shards(shards -> shards.total(1).successful(1).failed(0))
                    .hits(hits -> hits.hits(Collections.emptyList())).build();
        }).when(es).search(any(SearchRequest.class), eq(Map.class));
        JsonNode body = post(batch(), 200);
        JsonNode item = body.path("items").get(0);
        assertEquals("SUCCESS", item.path("status").asText());
        JsonNode snapshot = item.path("candidates").get(0);
        assertEquals("冻结的候选正文", snapshot.path("content").asText());
        assertEquals("indicator", snapshot.path("result").path("candidateId").asText());
        assertEquals(1, snapshot.path("result").path("rank").asInt());
        assertEquals("controlled-run", snapshot.path("result").path("analysisRunId").asText());
        assertEquals(0.8, snapshot.path("result").path("score").asDouble(), 1e-9);
    }
    @Test void malformedAndEmptyBatchesAreBadRequestBeforeProvider() {
        post("{", 400); post("{}", 400); post(null, 400);
        assertEquals(0, embedding.calls); verifyNoInteractions(es);
    }
    @Test void duplicateOrMismatchedScopeIsRejectedBeforeProvider() {
        RetrievalBatchRequest request = batch();
        request.setRequests(Arrays.asList(request.getRequests().get(0), request.getRequests().get(0)));
        post(request, 400);
        request = batch(); request.getRequests().get(0).setAnalysisRunId("foreign"); post(request, 400);
        assertEquals(0, embedding.calls); verifyNoInteractions(es);
    }
    @Test void invalidTopKIsBadRequestAndDoesNotSearchEs() {
        RetrievalBatchRequest request = batch(); request.getRequests().get(0).setIndicatorTopK(0);
        post(request, 400); verifyNoInteractions(es);
    }
    @Test void providerFailuresUseUnavailableOrBadGatewayWithoutLeakingDetails() {
        for (int status : new int[]{-1, 408, 429, 503}) {
            embedding.failure = new EmbeddingProviderException("private-provider", status, true);
            post(batch(), 503);
        }
        for (int status : new int[]{200, 400, 401}) {
            embedding.failure = new EmbeddingProviderException("private-provider", status, false);
            post(batch(), 502);
        }
        verifyNoInteractions(es);
    }
    @Test void esFailureCannotBecomeSuccessfulEmptyCandidates() throws Exception {
        doThrow(new java.io.IOException("private-es-host")).when(es).search(any(SearchRequest.class), eq(Map.class));
        post(batch(), 500);
    }
    @Test void wrongProviderVectorCountIsServerError() {
        embedding.wrongCount = true; post(batch(), 500); verifyNoInteractions(es);
    }
    private RetrievalBatchRequest batch() {
        return RetrievalBatchRequest.builder().scope(new AnalysisScope(1L, 2L, "controlled-run"))
                .requests(Collections.singletonList(RetrievalRequest.builder().assessmentId(2L)
                        .analysisRunId("controlled-run").behaviorId("fact").queryText("受控事实").build())).build();
    }
    private JsonNode post(Object input, int expected) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = http.postForEntity("/retrieval/batch", new HttpEntity<>(input, headers), JsonNode.class);
        assertEquals(expected, response.getStatusCodeValue(), String.valueOf(response.getBody()));
        assertNotNull(response.getBody()); assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) {
            assertEquals(expected, response.getBody().path("code").asInt());
            assertTrue(response.getBody().has("data")); assertTrue(response.getBody().get("data").isNull());
            assertFalse(response.getBody().toString().contains("private-"));
        }
        return response.getBody();
    }
}
