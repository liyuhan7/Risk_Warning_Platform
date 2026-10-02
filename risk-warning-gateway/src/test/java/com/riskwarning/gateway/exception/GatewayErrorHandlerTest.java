package com.riskwarning.gateway.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class GatewayErrorHandlerTest {
    @Test void preservesExplicitBusinessConflict() throws Exception {
        verify(new BusinessException(409, "运行身份冲突"), 409, "运行身份冲突");
    }
    @Test void invalidBusinessCodesCannotBecomeSuccessfulResponses() throws Exception {
        verify(new BusinessException(200, "private-secret"), 500, "Internal Server Error");
        verify(new BusinessException(null, "private-secret"), 500, "Internal Server Error");
    }
    @Test void wrappedConnectionAndTimeoutFailuresRemainUnavailable() throws Exception {
        verify(new IllegalStateException("wrapper", new ConnectException("private-host")), 503, "上游服务暂时不可用");
        verify(new TimeoutException("private-host"), 503, "上游服务暂时不可用");
    }

    private void verify(Throwable failure, int status, String message) throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/probe"));
        new GatewayErrorHandler().handle(exchange, failure).block();
        assertEquals(status, exchange.getResponse().getRawStatusCode());
        JsonNode body = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertEquals(status, body.path("code").asInt());
        assertEquals(message, body.path("message").asText());
        assertTrue(body.has("data")); assertTrue(body.get("data").isNull());
    }
}
