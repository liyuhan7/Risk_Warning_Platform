package com.riskwarning.common.provider;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.riskwarning.common.config.LlmProviderProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider 调用边界的行为验证。
 *
 * 使用 JDK 内置 HttpServer 作为桩，避免为测试引入新的网络依赖；
 * 每个用例断言真实发出的请求内容或请求次数，而非仅断言返回值。
 */
class OpenAiCompatibleChatProviderTest {

    private HttpServer server;

    /** 桩服务收到的请求体，按到达顺序记录 */
    private final List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 成功响应应返回正文，且请求体必须显式携带温度参数。
     */
    @Test
    void shouldReturnContentAndSendTemperature() {
        startStub(exchange -> respond(exchange, 200, chatCompletion("这是模型输出")));
        OpenAiCompatibleChatProvider provider = newProvider(properties());

        String content = provider.chat("测试提示词");

        assertEquals("这是模型输出", content);
        assertEquals(1, requestCount.get());

        JSONObject sentBody = JSONUtil.parseObj(receivedBodies.get(0));
        // 结构化输出依赖确定性，温度必须显式出现在请求体中
        assertEquals(0.0D, sentBody.getDouble("temperature"), 0.0001D);
        assertEquals("test-model", sentBody.getStr("model"));
        assertEquals("测试提示词",
                sentBody.getJSONArray("messages").getJSONObject(0).getStr("content"));
    }

    /**
     * 限流属瞬时故障，应重试并在供应商恢复后成功。
     */
    @Test
    void shouldRetryOnRateLimitAndSucceed() {
        startStub(exchange -> {
            if (requestCount.get() == 1) {
                respond(exchange, 429, "{\"error\":{\"code\":\"rate_limited\"}}");
            } else {
                respond(exchange, 200, chatCompletion("重试后成功"));
            }
        });

        LlmProviderProperties properties = properties();
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoffMillis(10L);

        String content = newProvider(properties).chat("测试提示词");

        assertEquals("重试后成功", content);
        assertEquals(2, requestCount.get());
    }

    /**
     * 持续不可用时应在达到配置的尝试上限后抛出可重试异常，不得静默吞掉。
     */
    @Test
    void shouldThrowRetryableExceptionWhenAttemptsExhausted() {
        startStub(exchange -> respond(exchange, 503, "{\"error\":{\"code\":\"unavailable\"}}"));

        LlmProviderProperties properties = properties();
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoffMillis(10L);

        LlmProviderException e = assertThrows(LlmProviderException.class,
                () -> newProvider(properties).chat("测试提示词"));

        assertEquals(503, e.getHttpStatus());
        assertTrue(e.isRetryable());
        // 失败必须显式抛出而非被静默吞掉，且尝试次数受配置约束
        assertEquals(3, requestCount.get());
    }

    /**
     * 账户欠费属账户状态问题，重发只会得到同样结果，必须一次终止。
     */
    @Test
    void shouldNotRetryOnAccountOverdue() {
        startStub(exchange -> respond(exchange, 403,
                "{\"error\":{\"code\":\"account_overdue\",\"type\":\"access_denied\"}}"));

        LlmProviderProperties properties = properties();
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoffMillis(10L);

        LlmProviderException e = assertThrows(LlmProviderException.class,
                () -> newProvider(properties).chat("测试提示词"));

        assertEquals(403, e.getHttpStatus());
        assertFalse(e.isRetryable());
        // 语义类失败重发只会得到同样结果，必须一次终止
        assertEquals(1, requestCount.get());
        assertTrue(e.getMessage().contains("account_overdue"));
    }

    /**
     * 参数非法属确定性失败，重发无意义。
     */
    @Test
    void shouldNotRetryOnBadRequest() {
        startStub(exchange -> respond(exchange, 400, "{\"error\":{\"code\":\"invalid_request\"}}"));

        LlmProviderProperties properties = properties();
        properties.getRetry().setInitialBackoffMillis(10L);

        LlmProviderException e = assertThrows(LlmProviderException.class,
                () -> newProvider(properties).chat("测试提示词"));

        assertFalse(e.isRetryable());
        assertEquals(1, requestCount.get());
    }

    /**
     * 结构缺失时重发只会得到同样的结构，归入不可重试。
     */
    @Test
    void shouldFailWithoutRetryWhenChoicesMissing() {
        startStub(exchange -> respond(exchange, 200, "{\"id\":\"x\"}"));

        LlmProviderException e = assertThrows(LlmProviderException.class,
                () -> newProvider(properties()).chat("测试提示词"));

        assertFalse(e.isRetryable());
        assertEquals(1, requestCount.get());
        assertTrue(e.getMessage().contains("choices"));
    }

    /**
     * 响应不是合法 JSON 同样属确定性失败。
     */
    @Test
    void shouldFailWithoutRetryOnMalformedJson() {
        startStub(exchange -> respond(exchange, 200, "这不是 JSON"));

        LlmProviderException e = assertThrows(LlmProviderException.class,
                () -> newProvider(properties()).chat("测试提示词"));

        assertFalse(e.isRetryable());
        assertNotNull(e.getMessage());
    }

    /**
     * 启用后缺少凭据应在构造阶段失败，及早暴露配置缺失。
     */
    @Test
    void shouldRejectConstructionWhenApiKeyMissing() {
        LlmProviderProperties properties = properties();
        properties.setApiKey("");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new OpenAiCompatibleChatProvider(properties));

        assertTrue(e.getMessage().contains("llm.api-key"));
    }

    /**
     * 启用后缺少端点同样应在构造阶段失败。
     */
    @Test
    void shouldRejectConstructionWhenBaseUrlMissing() {
        LlmProviderProperties properties = properties();
        properties.setBaseUrl(null);

        assertThrows(IllegalStateException.class,
                () -> new OpenAiCompatibleChatProvider(properties));
    }

    /**
     * 空白提示词应在发出请求前被拒绝。
     */
    @Test
    void shouldRejectBlankPrompt() {
        startStub(exchange -> respond(exchange, 200, chatCompletion("不应到达")));
        OpenAiCompatibleChatProvider provider = newProvider(properties());

        assertThrows(IllegalArgumentException.class, () -> provider.chat("  "));
        assertEquals(0, requestCount.get());
    }

    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void startStub(StubHandler handler) {
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            receivedBodies.add(readBody(exchange.getRequestBody()));
            handler.handle(exchange);
        });
        server.start();
    }

    private LlmProviderProperties properties() {
        LlmProviderProperties properties = new LlmProviderProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        properties.setModel("test-model");
        properties.setApiKey("test-key");
        properties.setTemperature(0.0D);
        return properties;
    }

    private OpenAiCompatibleChatProvider newProvider(LlmProviderProperties properties) {
        return new OpenAiCompatibleChatProvider(properties);
    }

    private static String chatCompletion(String content) {
        JSONObject message = new JSONObject();
        message.set("role", "assistant");
        message.set("content", content);

        JSONObject choice = new JSONObject();
        choice.set("index", 0);
        choice.set("message", message);

        JSONObject body = new JSONObject();
        body.set("id", "test-id");
        body.set("choices", Collections.singletonList(choice));
        return body.toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String readBody(InputStream in) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取桩请求体失败", e);
        }
    }
}
