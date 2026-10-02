package com.riskwarning.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.result.Result;
import com.riskwarning.common.utils.JwtUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 随机端口真实 WebFlux HTTP 验证生产认证过滤器，令牌解析与下游为替身。 */
class AuthFilterHttpTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JwtUtils JWT = mock(JwtUtils.class);
    private static final AtomicInteger FORWARDED = new AtomicInteger();
    private static DisposableServer server;

    @BeforeAll static void start() {
        AuthFilter filter = new AuthFilter(); ReflectionTestUtils.setField(filter, "jwtUtils", JWT);
        server = HttpServer.create().host("127.0.0.1").port(0).handle(new ReactorHttpHandlerAdapter(
                WebHttpHandlerBuilder.webHandler(exchange -> filter.filter(exchange, accepted -> {
                    FORWARDED.incrementAndGet(); accepted.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    try {
                        byte[] bytes = MAPPER.writeValueAsBytes(Result.success("allowed"));
                        return accepted.getResponse().writeWith(Mono.just(accepted.getResponse().bufferFactory().wrap(bytes)));
                    } catch (Exception failure) { return Mono.error(failure); }
                })).build())).bindNow();
    }
    @AfterAll static void stop() { if (server != null) server.disposeNow(); }
    @BeforeEach void setup() { reset(JWT); FORWARDED.set(0); when(JWT.validateToken("valid")).thenReturn(true); }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Basic token", "Bearer", "Bearer ", "Bearer invalid"})
    void missingMalformedAndInvalidTokensReturnConsistentUnauthorized(String authorization) throws Exception {
        request("/api/processing/analysis/overview/1", authorization, 401);
        assertEquals(0, FORWARDED.get());
    }
    @Test void validTokenForwardsWithoutReplacingDownstreamResponse() throws Exception {
        request("/api/processing/analysis/overview/1", "Bearer valid", 200);
        assertEquals(1, FORWARDED.get()); verify(JWT).validateToken("valid");
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/org/user/login", "/api/org/user/register"})
    void publicAuthenticationEndpointsRemainAccessible(String path) throws Exception {
        request(path, null, 200); assertEquals(1, FORWARDED.get()); verifyNoInteractions(JWT);
    }
    private void request(String path, String authorization, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        try {
            assertEquals(expected, connection.getResponseCode());
            assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(connection.getContentType())));
            try (InputStream body = expected >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                assertNotNull(body); JsonNode response = MAPPER.readTree(StreamUtils.copyToString(body, StandardCharsets.UTF_8));
                assertEquals(expected, response.path("code").asInt()); assertTrue(response.has("message")); assertTrue(response.has("data"));
                if (expected >= 400) assertTrue(response.get("data").isNull());
            }
        } finally { connection.disconnect(); }
    }
}
