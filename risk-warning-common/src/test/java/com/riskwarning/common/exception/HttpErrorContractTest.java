package com.riskwarning.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.result.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.NoSuchElementException;
import static org.junit.jupiter.api.Assertions.*;

/** 随机端口的真实 Servlet HTTP 验证，无数据库、注册中心或模型依赖。 */
@SpringBootTest(classes = HttpErrorContractTest.Server.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpErrorContractTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;

    @ParameterizedTest
    @CsvSource({
            "200,/probe/ok", "400,/probe/failure/parameter", "401,/probe/failure/auth", "403,/probe/failure/permission",
            "404,/probe/failure/missing", "409,/probe/failure/conflict", "502,/probe/failure/upstream-response",
            "503,/probe/failure/upstream-unavailable", "500,/probe/failure/system", "500,/probe/failure/illegal-state",
            "500,/probe/failure/invalid-code", "400,/probe/value", "400,/probe/value?count=not-number",
            "400,/probe/limit?count=0", "404,/unknown-route"
    })
    void statusAndEnvelopeAgreeOverHttp(int expected, String path) {
        verify(http.getForEntity(url(path), JsonNode.class), expected);
    }
    @Test void bodyValidationAndMalformedJsonAreBadRequest() {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        verify(http.postForEntity(url("/probe/body"), new HttpEntity<>("{}", headers), JsonNode.class), 400);
        verify(http.postForEntity(url("/probe/body"), new HttpEntity<>("{", headers), JsonNode.class), 400);
        verify(http.postForEntity(url("/probe/body"), new HttpEntity<>("", headers), JsonNode.class), 400);
        verify(http.postForEntity(url("/probe/body"), new HttpEntity<>("{\"name\":\"valid\"}", headers), JsonNode.class), 200);
    }
    @Test void methodAndContentTypeErrorsAlsoUseEnvelope() {
        verify(http.exchange(url("/probe/ok"), HttpMethod.PUT, HttpEntity.EMPTY, JsonNode.class), 405);
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_XML);
        verify(http.postForEntity(url("/probe/body"), new HttpEntity<>("<name/>", headers), JsonNode.class), 415);
    }
    @Test void uncategorizedExceptionDoesNotExposeInternalMessage() {
        ResponseEntity<JsonNode> response = http.getForEntity(url("/probe/failure/system"), JsonNode.class);
        verify(response, 500); assertFalse(response.getBody().path("message").asText().contains("内部连接细节"));
    }
    private void verify(ResponseEntity<JsonNode> response, int expected) {
        assertEquals(expected, response.getStatusCodeValue()); assertNotNull(response.getBody());
        assertEquals(expected, response.getBody().path("code").asInt());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        assertTrue(response.getBody().has("message")); assertTrue(response.getBody().has("data"));
    }
    private String url(String path) { return "http://localhost:" + port + path; }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, ValidationAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @Import({GlobalExceptionHandler.class, ApiErrorController.class, ProbeController.class})
    static class Server { }

    @RestController
    @Validated
    public static class ProbeController {
        @GetMapping("/probe/ok") public Result<?> ok() { return Result.success("ok"); }
        @GetMapping("/probe/value") public Result<?> value(@RequestParam int count) { return Result.success(count); }
        @GetMapping("/probe/limit") public Result<?> limit(@RequestParam @Min(1) int count) { return Result.success(count); }
        @PostMapping(value = "/probe/body", consumes = "application/json")
        public Result<?> body(@Valid @RequestBody Input input) { return Result.success(input.name); }
        @GetMapping("/probe/failure/{kind}") public Result<?> failure(@PathVariable String kind) {
            switch (kind) {
                case "parameter": throw new IllegalArgumentException("参数无效");
                case "auth": throw new BusinessException(401, "请登录");
                case "permission": throw new SecurityException("无权访问");
                case "missing": throw new NoSuchElementException("对象不存在");
                case "conflict": throw new AnalysisInputConflictException("输入变化");
                case "upstream-response": throw new BusinessException(502, "上游响应不可接受");
                case "upstream-unavailable": throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "上游不可用");
                case "invalid-code": throw new BusinessException(200, "无效错误码");
                case "illegal-state": throw new IllegalStateException("数据库记录损坏");
                default: throw new RuntimeException("内部连接细节");
            }
        }
    }
    public static class Input { @NotBlank public String name; }
}
