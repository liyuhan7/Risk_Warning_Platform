package com.riskwarning.notification.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.exception.ApiErrorController;
import com.riskwarning.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;

/** 无业务 Controller 的 Servlet 错误入口；不启动 Kafka、数据库或 Netty 后台线程。 */
@SpringBootTest(classes = NotificationServletHttpTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"})
class NotificationServletHttpTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @Import({GlobalExceptionHandler.class, ApiErrorController.class})
    static class Server { }
    @Autowired private TestRestTemplate http;
    @Test void servletUnknownRoutesUseCommonEnvelope() {
        ResponseEntity<JsonNode> reply = http.getForEntity("/missing-notification-route", JsonNode.class);
        assertEquals(404, reply.getStatusCodeValue()); assertNotNull(reply.getBody());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(reply.getHeaders().getContentType()));
        assertEquals(404, reply.getBody().path("code").asInt());
        assertTrue(reply.getBody().has("message")); assertTrue(reply.getBody().has("data"));
        assertTrue(reply.getBody().get("data").isNull());
    }
}
