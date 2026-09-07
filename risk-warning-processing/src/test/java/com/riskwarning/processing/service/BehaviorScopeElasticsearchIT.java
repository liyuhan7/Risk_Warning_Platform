package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 只连接显式提供的专用 ES；测试索引随机命名，绝不读取或删除 t_behavior。
 */
@EnabledIfEnvironmentVariable(named = "P1_ES_TEST_URL", matches = ".+")
class BehaviorScopeElasticsearchIT {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient client;
    private String index;

    @BeforeEach
    void createTestIndex() throws Exception {
        URI uri = URI.create(System.getenv("P1_ES_TEST_URL"));
        int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        client = RestClient.builder(new HttpHost(uri.getHost(), port, uri.getScheme())).build();
        index = "p1_behavior_scope_" + UUID.randomUUID().toString().replace("-", "");
        request("PUT", "/" + index, mappingJson());
    }

    @AfterEach
    void deleteTestIndex() throws Exception {
        if (client != null) {
            try {
                if (index != null && index.startsWith("p1_behavior_scope_")) {
                    request("DELETE", "/" + index, null);
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    void persistsScopeFieldsAndExcludesOtherRunsAndLegacyDocuments() throws Exception {
        JsonNode mapping = request("GET", "/" + index + "/_mapping", null);
        JsonNode properties = mapping.path(index).path("mappings").path("properties");
        assertEquals("long", properties.path("assessmentId").path("type").asText());
        assertEquals("keyword", properties.path("analysisRunId").path("type").asText());
        assertEquals("long", properties.path("sourceDocumentId").path("type").asText());
        assertEquals("keyword", properties.path("evidenceIds").path("type").asText());
        assertEquals("float", properties.path("confidence").path("type").asText());

        indexDocument("scope-a1", "{\"projectId\":10,\"assessmentId\":20,\"analysisRunId\":\"run-a1\","
                + "\"sourceDocumentId\":101,\"description\":\"A1\",\"evidenceIds\":[\"evidence-a\"],"
                + "\"confidence\":0.9,\"subject\":\"企业\",\"action\":\"保存\"}");
        indexDocument("scope-a2", "{\"projectId\":10,\"assessmentId\":20,\"analysisRunId\":\"run-a2\","
                + "\"sourceDocumentId\":102,\"description\":\"A2\"}");
        indexDocument("scope-b1", "{\"projectId\":10,\"assessmentId\":21,\"analysisRunId\":\"run-b1\","
                + "\"sourceDocumentId\":103,\"description\":\"B1\"}");
        indexDocument("legacy", "{\"projectId\":10,\"description\":\"历史行为\"}");

        JsonNode result = request("POST", "/" + index + "/_search", scopedQueryJson(10L, 20L, "run-a1"));
        assertEquals(1, result.path("hits").path("total").path("value").asInt());
        JsonNode source = result.path("hits").path("hits").get(0).path("_source");
        assertNotNull(source);
        assertEquals(20L, source.path("assessmentId").asLong());
        assertEquals("run-a1", source.path("analysisRunId").asText());
        assertEquals(101L, source.path("sourceDocumentId").asLong());
        assertFalse(source.path("evidenceIds").isMissingNode());
    }

    private void indexDocument(String id, String document) throws Exception {
        request("PUT", "/" + index + "/_doc/" + id + "?refresh=true", document);
    }

    private JsonNode request(String method, String endpoint, String body) throws Exception {
        Request request = new Request(method, endpoint);
        if (body != null) {
            request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        }
        Response response = client.performRequest(request);
        return response.getEntity() == null ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.getEntity().getContent());
    }

    private String mappingJson() {
        return "{\"mappings\":{\"properties\":{"
                + "\"projectId\":{\"type\":\"long\"},"
                + "\"assessmentId\":{\"type\":\"long\"},"
                + "\"analysisRunId\":{\"type\":\"keyword\"},"
                + "\"sourceDocumentId\":{\"type\":\"long\"},"
                + "\"description\":{\"type\":\"text\"},"
                + "\"subject\":{\"type\":\"keyword\"},"
                + "\"action\":{\"type\":\"keyword\"},"
                + "\"object\":{\"type\":\"keyword\"},"
                + "\"quantitativeUnit\":{\"type\":\"keyword\"},"
                + "\"confidence\":{\"type\":\"float\"},"
                + "\"evidenceIds\":{\"type\":\"keyword\"},"
                + "\"extractionModel\":{\"type\":\"keyword\"},"
                + "\"extractionPromptVersion\":{\"type\":\"keyword\"}}}}";
    }

    private String scopedQueryJson(Long projectId, Long assessmentId, String analysisRunId) {
        return "{\"track_total_hits\":true,\"query\":{\"bool\":{\"must\":["
                + "{\"term\":{\"projectId\":" + projectId + "}},"
                + "{\"term\":{\"assessmentId\":" + assessmentId + "}},"
                + "{\"term\":{\"analysisRunId\":\"" + analysisRunId + "\"}}]}}}";
    }
}
