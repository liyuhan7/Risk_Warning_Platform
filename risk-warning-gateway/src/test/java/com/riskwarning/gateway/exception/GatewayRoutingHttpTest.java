package com.riskwarning.gateway.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.error.ErrorWebFluxAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Gateway 路由和随机端口 HTTP；关闭外部注册、配置和业务存储。 */
@SpringBootTest(classes = GatewayRoutingHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.web-application-type=reactive", "spring.cloud.bootstrap.enabled=false",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false"})
class GatewayRoutingHttpTest {
    @LocalServerPort private int port;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, ReactiveWebServerFactoryAutoConfiguration.class,
            WebFluxAutoConfiguration.class, HttpHandlerAutoConfiguration.class,
            ErrorWebFluxAutoConfiguration.class, GatewayAutoConfiguration.class})
    @Import({GatewayErrorHandler.class, Probe.class})
    static class Application {
        @Bean RouteLocator routes(RouteLocatorBuilder builder) throws Exception {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
            return builder.routes().route("unavailable", route -> route.path("/unavailable/**")
                    .uri("http://127.0.0.1:" + closedPort)).build();
        }
    }

    @RestController
    static class Probe {
        @GetMapping("/internal-error") void internalError() { throw new IllegalStateException("private-storage-secret"); }
        @GetMapping("/provider-error") void providerError() { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "private-provider-secret"); }
    }

    @Test void unmatchedRouteReturnsJsonNotDefaultFrameworkBody() throws Exception { request("/absent", 404); }
    @Test void realGatewayConnectionRefusalReturnsServiceUnavailable() throws Exception { request("/unavailable/probe", 503); }
    @Test void internalFailureIsSanitized() throws Exception { request("/internal-error", 500); }
    @Test void explicitProviderStatusIsPreservedAndSanitized() throws Exception { request("/provider-error", 502); }

    @Test void committedResponsePropagatesOriginalFailure() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/committed"));
        exchange.getResponse().setStatusCode(HttpStatus.ACCEPTED);
        exchange.getResponse().setComplete().block();
        IllegalStateException failure = new IllegalStateException("already committed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new GatewayErrorHandler().handle(exchange, failure).block()));
        assertEquals(HttpStatus.ACCEPTED, exchange.getResponse().getStatusCode());
    }

    private void request(String path, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        try {
            assertEquals(expected, connection.getResponseCode());
            assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(connection.getContentType())));
            try (InputStream body = connection.getErrorStream()) {
                assertNotNull(body);
                String text = StreamUtils.copyToString(body, StandardCharsets.UTF_8);
                JsonNode json = new ObjectMapper().readTree(text);
                assertEquals(expected, json.path("code").asInt());
                assertTrue(json.has("message")); assertTrue(json.has("data")); assertTrue(json.get("data").isNull());
                assertFalse(text.contains("private-"));
            }
        } finally { connection.disconnect(); }
    }
}
