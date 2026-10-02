package com.riskwarning.bert.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.bert.service.PythonServiceManager;
import com.riskwarning.common.exception.*;
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
import org.springframework.web.client.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际 Servlet HTTP 使用生产 Controller，Python 管理器替身不会启动模型。 */
@SpringBootTest(classes = ClassifierHttpContractTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"})
class ClassifierHttpContractTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @Import({ClassifierController.class, BertServiceController.class, GlobalExceptionHandler.class, ApiErrorController.class})
    static class Server { }
    @Autowired private TestRestTemplate http;
    @MockBean private PythonServiceManager manager;
    @BeforeEach void setup() {
        when(manager.isServiceRunning()).thenReturn(true);
        when(manager.classify(anyMap())).thenReturn(Collections.singletonMap("category", "controlled"));
        when(manager.classifyBatch(anyMap())).thenReturn(Collections.singletonMap("results", Collections.emptyList()));
        when(manager.checkClassifierHealth()).thenReturn(Collections.singletonMap("status", "ok"));
    }
    @Test void successAndStoppedStatusKeepOriginalFields() {
        assertEquals("controlled", post("/classify", "{\"text\":\"controlled\"}", 200).path("category").asText());
        assertTrue(post("/classify/batch", "{\"items\":[{\"text\":\"controlled\"},{\"text\":\" \"}]}", 200).path("results").isArray());
        verify(manager).classifyBatch(argThat(body -> ((java.util.List<?>) body.get("items")).size() == 2));
        assertEquals("ok", get("/classify/health", 200).path("status").asText());
        assertEquals("running", get("/bert/status", 200).path("pythonService").asText());
        assertEquals("UP", get("/bert/health", 200).path("status").asText());
        when(manager.isServiceRunning()).thenReturn(false);
        assertEquals("stopped", get("/bert/status", 200).path("pythonService").asText());
        assertFalse(get("/bert/health", 200).path("pythonServiceRunning").asBoolean());
    }
    @Test void invalidRequestsFailBeforePythonInvocation() {
        for (String json : new String[]{"{}", "null", "{", "{\"text\":1}", "{\"text\":\" \"}",
                "{\"text\":\"controlled\",\"input_type\":null}", "{\"text\":\"controlled\",\"input_type\":\"invalid\"}"}) {
            post("/classify", json, 400);
        }
        for (String json : new String[]{"{}", "{\"items\":123}", "{\"items\":[]}", "{\"items\":[null]}",
                "{\"items\":[{\"text\":1}]}", "{\"items\":[{}]}"}) {
            post("/classify/batch", json, 400);
        }
        verifyNoInteractions(manager);
    }
    @Test void unavailableAndConnectionFaultsAreServiceUnavailable() {
        when(manager.isServiceRunning()).thenReturn(false);
        get("/classify/health", 503);
        post("/classify", "{\"text\":\"controlled\"}", 503);
        post("/classify/batch", "{\"items\":[{\"text\":\"controlled\"}]}", 503);
        verify(manager, never()).classify(anyMap());
        when(manager.isServiceRunning()).thenReturn(true);
        when(manager.classify(anyMap())).thenThrow(new ResourceAccessException("private-connection"));
        when(manager.classifyBatch(anyMap())).thenThrow(new ResourceAccessException("private-connection"));
        when(manager.checkClassifierHealth()).thenThrow(new ResourceAccessException("private-connection"));
        get("/classify/health", 503);
        post("/classify", "{\"text\":\"controlled\"}", 503);
        post("/classify/batch", "{\"items\":[{\"text\":\"controlled\"}]}", 503);
    }
    @Test void upstreamStatusesDoNotExposeBodyOrImpersonateUserAuthentication() {
        for (int status : new int[]{400, 422, 408, 429, 500, 503, 401, 403, 404}) {
            RestClientResponseException failure = new RestClientResponseException("private-upstream", status,
                    "private-status", new HttpHeaders(), "private-body".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            doThrow(failure).when(manager).classify(anyMap());
            doThrow(failure).when(manager).classifyBatch(anyMap());
            doThrow(failure).when(manager).checkClassifierHealth();
            int expected = status == 400 || status == 422 ? 400
                    : status == 408 || status == 429 || status >= 500 ? 503 : 502;
            post("/classify", "{\"text\":\"controlled\"}", expected);
            post("/classify/batch", "{\"items\":[{\"text\":\"controlled\"}]}", expected);
            get("/classify/health", expected == 400 ? 502 : expected);
        }
    }
    @Test void emptyUpstreamIsBadGatewayAndUnknownFaultsAreSanitized() {
        when(manager.classify(anyMap())).thenReturn(null);
        when(manager.classifyBatch(anyMap())).thenReturn(null);
        when(manager.checkClassifierHealth()).thenReturn(null);
        get("/classify/health", 502);
        post("/classify", "{\"text\":\"controlled\"}", 502);
        post("/classify/batch", "{\"items\":[{\"text\":\"controlled\"}]}", 502);
        when(manager.classify(anyMap())).thenThrow(new RestClientException("private-response-conversion"));
        post("/classify", "{\"text\":\"controlled\"}", 502);
        doThrow(new IllegalStateException("private-execution")).when(manager).classify(anyMap());
        post("/classify", "{\"text\":\"controlled\"}", 500);
        when(manager.isServiceRunning()).thenThrow(new IllegalStateException("private-manager"));
        get("/bert/status", 500); get("/bert/health", 500);
        get("/missing-classifier-route", 404);
    }
    private JsonNode get(String path, int expected) {
        return check(http.getForEntity(path, JsonNode.class), expected);
    }
    private JsonNode post(String path, String json, int expected) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        return check(http.postForEntity(path, new HttpEntity<>(json, headers), JsonNode.class), expected);
    }
    private JsonNode check(ResponseEntity<JsonNode> response, int expected) {
        assertEquals(expected, response.getStatusCodeValue(), String.valueOf(response.getBody()));
        assertNotNull(response.getBody());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) {
            assertEquals(expected, response.getBody().path("code").asInt());
            assertTrue(response.getBody().has("data")); assertTrue(response.getBody().get("data").isNull());
            assertFalse(response.getBody().toString().contains("private-"));
        } else {
            assertFalse(response.getBody().has("code"));
        }
        return response.getBody();
    }
}
