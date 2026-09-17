package com.riskwarning.common.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.config.EmbeddingProviderProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 HTTP 桩验证协议、批次、失败分类和响应校验，不访问供应商。 */
class OpenAiCompatibleEmbeddingProviderTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<JsonNode> bodies = new ArrayList<>();
    private int firstStatus = 200;
    private boolean abortConnection;
    private java.util.function.Function<JsonNode, String> response;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        response = request -> valid(request.path("input").size());
        server.createContext("/embeddings", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            bodies.add(request);
            if (abortConnection) {
                calls.incrementAndGet();
                exchange.close();
                return;
            }
            int status = calls.incrementAndGet() == 1 ? firstStatus : 200;
            byte[] body = (status == 200 ? response.apply(request) : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private OpenAiCompatibleEmbeddingProvider provider() {
        EmbeddingProviderProperties p = new EmbeddingProviderProperties();
        p.setApiKey("test-key");
        p.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings");
        p.setInitialBackoffMillis(0);
        return new OpenAiCompatibleEmbeddingProvider(p);
    }

    private String valid(int count) {
        ArrayNode data = mapper.createArrayNode();
        for (int i = count - 1; i >= 0; i--) {
            ArrayNode vector = mapper.createArrayNode();
            for (int j = 0; j < 1024; j++) { vector.add(j == i ? 2.0 : 0.0); }
            data.add(mapper.createObjectNode().put("index", i).set("embedding", vector));
        }
        return mapper.createObjectNode().set("data", data).toString();
    }

    @Test
    void restoresOrderNormalizesAndPreservesRoleText() {
        List<List<Float>> vectors = provider().embed(Arrays.asList("查询一", "查询二"), EmbeddingRole.QUERY);
        assertEquals(2, vectors.size());
        assertEquals(1.0f, vectors.get(0).get(0), 1e-6);
        assertEquals(1.0f, vectors.get(1).get(1), 1e-6);
        assertEquals(1024, vectors.get(0).size());
        assertEquals("查询一", bodies.get(0).path("input").get(0).asText());
        assertFalse(bodies.get(0).has("role"));
        assertEquals("BAAI/bge-m3", bodies.get(0).path("model").asText());
    }

    @Test
    void splitsAtSixteenWithoutChangingDocumentText() {
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 17; i++) { input.add("文档" + i); }
        assertEquals(17, provider().embed(input, EmbeddingRole.DOCUMENT).size());
        assertEquals(2, calls.get());
        assertEquals(16, bodies.get(0).path("input").size());
        assertEquals("文档16", bodies.get(1).path("input").get(0).asText());
    }

    @Test
    void rejectsEmptyInputBeforeHttp() {
        OpenAiCompatibleEmbeddingProvider p = provider();
        assertThrows(IllegalArgumentException.class, () -> p.embed(null, EmbeddingRole.QUERY));
        assertThrows(IllegalArgumentException.class, () -> p.embed(Collections.emptyList(), EmbeddingRole.QUERY));
        assertThrows(IllegalArgumentException.class, () -> p.embed(Arrays.asList("正常", " "), EmbeddingRole.QUERY));
        assertThrows(IllegalArgumentException.class, () -> p.embed(Arrays.asList("正常", null), EmbeddingRole.QUERY));
        assertThrows(IllegalArgumentException.class, () -> p.embed(Collections.singletonList("正常"), null));
        assertEquals(0, calls.get());
    }

    @Test
    void retriesTransientStatuses() {
        firstStatus = 429;
        assertEquals(1, provider().embed(Collections.singletonList("查询"), EmbeddingRole.QUERY).size());
        assertEquals(2, calls.get());
        calls.set(0);
        firstStatus = 503;
        provider().embed(Collections.singletonList("查询"), EmbeddingRole.QUERY);
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRetryAuthenticationOrBadRequest() {
        for (int status : new int[] {400, 401}) {
            calls.set(0);
            firstStatus = status;
            EmbeddingProviderException e = assertThrows(EmbeddingProviderException.class,
                    () -> provider().embed(Collections.singletonList("查询"), EmbeddingRole.QUERY));
            assertFalse(e.isRetryable());
            assertEquals(status, e.getHttpStatus());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void retriesNetworkFailureUntilLimit() {
        abortConnection = true;
        EmbeddingProviderException failure = assertThrows(EmbeddingProviderException.class,
                () -> provider().embed(Collections.singletonList("查询"), EmbeddingRole.QUERY));
        assertTrue(failure.isRetryable());
        assertEquals(3, calls.get());
    }

    private void invalid(java.util.function.Consumer<ObjectNode> mutation, int count) {
        response = request -> {
            try {
                ObjectNode root = (ObjectNode) mapper.readTree(valid(count));
                mutation.accept(root);
                return root.toString();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        EmbeddingProviderException failure = assertThrows(EmbeddingProviderException.class,
                () -> provider().embed(Collections.nCopies(count, "查询"), EmbeddingRole.QUERY));
        assertFalse(failure.isRetryable());
        assertEquals(1, calls.get());
    }

    @Test void rejectsWrongCount() { invalid(root -> ((ArrayNode) root.get("data")).remove(0), 1); }
    @Test void rejectsDuplicateIndex() { invalid(root -> ((ObjectNode) root.get("data").get(0)).put("index", 0), 2); }
    @Test void rejectsOutOfBoundsIndex() { invalid(root -> ((ObjectNode) root.get("data").get(0)).put("index", 2), 1); }
    @Test void rejectsFractionalIndex() { invalid(root -> ((ObjectNode) root.get("data").get(0)).put("index", 0.5), 1); }
    @Test void rejectsWrongDimension() { invalid(root -> ((ArrayNode) root.get("data").get(0).get("embedding")).remove(0), 1); }
    @Test void rejectsZeroVector() { invalid(root -> ((ArrayNode) root.get("data").get(0).get("embedding")).set(0, DoubleNode.valueOf(0)), 1); }
    @Test void rejectsNonNumericValue() { invalid(root -> ((ArrayNode) root.get("data").get(0).get("embedding")).set(0, TextNode.valueOf("NaN")), 1); }
    @Test void rejectsOverflowValue() {
        response = request -> valid(1).replace("2.0", "1e999");
        assertThrows(EmbeddingProviderException.class, () -> provider().embed(Collections.singletonList("查询"), EmbeddingRole.QUERY));
        assertEquals(1, calls.get());
    }
    @Test void rejectsMalformedResponse() {
        response = request -> "not json";
        assertThrows(EmbeddingProviderException.class, () -> provider().embed(Collections.singletonList("查询"), EmbeddingRole.QUERY));
        assertEquals(1, calls.get());
    }
}
