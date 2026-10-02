package com.riskwarning.report.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.exception.*;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.po.risk.Risk;
import com.riskwarning.report.repository.*;
import com.riskwarning.report.service.impl.ReportServiceImpl;
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
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实报告 HTTP 使用生产 Controller/Service，PG 和 ES 客户端为替身。 */
@SpringBootTest(classes = ReportHttpContractTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"})
class ReportHttpContractTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @Import({ReportController.class, ReportServiceImpl.class, GlobalExceptionHandler.class, ApiErrorController.class})
    static class Server { }
    @Autowired private TestRestTemplate http;
    @MockBean private AssessmentRepository assessments;
    @MockBean private AnalysisRunRepository runs;
    @MockBean private IndicatorResultRepository indicators;
    @MockBean private ElasticsearchClient es;

    @BeforeEach void setup() {
        when(assessments.findById(2L)).thenReturn(Optional.of(Assessment.builder()
                .id(2L).projectId(1L).assessmentDate(LocalDateTime.now()).build()));
    }
    @Test void existingAssessmentWithoutPublishedRunRetainsEmptySuccessfulReport() {
        JsonNode distribution = get("/indicatorResult/2", 200);
        assertEquals(0, distribution.path("data").path("totalCount").asInt());
        assertTrue(get("/risk?assessmentId=2", 200).path("data").isArray());
        assertEquals(2, get("/general/2", 200).path("data").path("assessmentId").asInt());
        verifyNoInteractions(es);
    }
    @Test void missingAssessmentReturnsNotFoundOnEveryEndpoint() {
        get("/indicatorResult/99", 404); get("/general/99", 404); get("/risk?assessmentId=99", 404);
        verifyNoInteractions(runs, indicators, es);
    }
    @Test void invalidAndMissingAssessmentParametersAreBadRequest() {
        get("/indicatorResult/0", 400); get("/general/-1", 400); get("/risk?assessmentId=0", 400);
        get("/risk", 400); get("/general/invalid", 400);
        verifyNoInteractions(assessments, runs, indicators, es);
    }
    @Test void databaseFailureIsSanitizedServerError() {
        when(assessments.findById(2L)).thenThrow(new IllegalStateException("private-storage"));
        get("/general/2", 500); get("/risk?assessmentId=2", 500);
    }
    @Test void publishedRiskSearchFailureCannotBecomeEmptySuccess() throws Exception {
        AnalysisRun run = AnalysisRun.start(new AnalysisScope(1L, 2L, "published"), LocalDateTime.now());
        run.succeed(LocalDateTime.now());
        when(runs.findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(anyLong(), any())).thenReturn(Optional.of(run));
        doThrow(new java.io.IOException("private-es-host"))
                .when(es).search(any(SearchRequest.class), eq(Risk.class));
        get("/risk?assessmentId=2", 500); get("/general/2", 500);
    }
    private JsonNode get(String path, int expected) {
        ResponseEntity<JsonNode> response = http.getForEntity(path, JsonNode.class);
        assertEquals(expected, response.getStatusCodeValue(), String.valueOf(response.getBody()));
        assertNotNull(response.getBody()); assertEquals(expected, response.getBody().path("code").asInt());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) {
            assertTrue(response.getBody().has("data")); assertTrue(response.getBody().get("data").isNull());
            assertFalse(response.getBody().toString().contains("private-"));
        }
        return response.getBody();
    }
}
