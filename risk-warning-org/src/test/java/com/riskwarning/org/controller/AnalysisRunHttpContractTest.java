package com.riskwarning.org.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.exception.ApiErrorController;
import com.riskwarning.common.exception.GlobalExceptionHandler;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.result.Result;
import com.riskwarning.org.repository.AnalysisRunRepository;
import com.riskwarning.org.repository.AssessmentRepository;
import com.riskwarning.org.service.AnalysisRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 通过测试入口验证生产运行服务与 HTTP 异常处理，仓储为替身。 */
@SpringBootTest(classes = AnalysisRunHttpContractTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"})
class AnalysisRunHttpContractTest {
    @Autowired private TestRestTemplate http;
    @MockBean private AnalysisRunRepository runs;
    @MockBean private AssessmentRepository assessments;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @Import({AnalysisRunService.class, Probe.class, GlobalExceptionHandler.class, ApiErrorController.class})
    static class Server { }

    @RestController
    static class Probe {
        private final AnalysisRunService service;
        Probe(AnalysisRunService service) { this.service = service; }
        @PostMapping("/probe/run") Result<AnalysisRun> start(@RequestBody AnalysisScope scope) {
            return Result.success(service.start(scope, LocalDateTime.now()));
        }
        @PostMapping("/probe/dispatch-failure") Result<Void> fail(@RequestBody AnalysisScope scope) {
            service.markDispatchFailed(scope, LocalDateTime.now()); return Result.success(null);
        }
    }

    @BeforeEach void setup() {
        when(assessments.findById(20L)).thenReturn(Assessment.builder().id(20L).projectId(10L).build());
        when(runs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(anyString(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());
    }
    @Test void validStartPreservesFrozenDefaultMode() {
        JsonNode body = request("/probe/run", new AnalysisScope(10L, 20L, "run-http"), 200);
        assertEquals("REASONING", body.path("data").path("executionMode").asText());
    }
    @Test void missingAssessmentAndWrongProjectAreDistinct() {
        request("/probe/run", new AnalysisScope(10L, 99L, "run-http"), 404);
        request("/probe/run", new AnalysisScope(99L, 20L, "run-http"), 403);
        verify(runs, never()).saveAndFlush(any());
    }
    @Test void existingRunningTaskIsConflict() {
        when(runs.existsByAssessmentIdAndStatus(20L, AnalysisRunStatus.RUNNING)).thenReturn(true);
        request("/probe/run", new AnalysisScope(10L, 20L, "run-http"), 409);
        verify(runs, never()).saveAndFlush(any());
    }
    @Test void knownUniqueConstraintIsConflictButOtherIntegrityFailuresAreServerErrors() {
        for (String constraint : new String[]{"t_analysis_run_pkey", "uq_analysis_run_scope", "uq_analysis_run_assessment"}) {
            doThrow(integrity("23505", constraint)).when(runs).saveAndFlush(any());
            request("/probe/run", new AnalysisScope(10L, 20L, "run-http"), 409);
        }
        for (String[] failure : new String[][]{{"23503", "fk_analysis_run_assessment"},
                {"23514", "ck_analysis_run_time"}, {"23505", "unrecognized_constraint"}, {"23514", "t_analysis_run_pkey"}}) {
            doThrow(integrity(failure[0], failure[1])).when(runs).saveAndFlush(any());
            request("/probe/run", new AnalysisScope(10L, 20L, "run-http"), 500);
        }
    }
    @Test void invalidScopeAndMissingDispatchTargetAreExplicit() {
        request("/probe/run", "{\"projectId\":0,\"assessmentId\":20,\"analysisRunId\":\"x\"}", 400);
        request("/probe/dispatch-failure", new AnalysisScope(10L, 20L, "missing"), 404);
    }
    private DataIntegrityViolationException integrity(String state, String constraint) {
        return new DataIntegrityViolationException("private-storage-detail",
                new org.hibernate.exception.ConstraintViolationException("private-storage-detail",
                        new SQLException("private-storage-detail", state), constraint));
    }
    private JsonNode request(String path, Object input, int expected) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = http.postForEntity(path,
                new org.springframework.http.HttpEntity<>(input, headers), JsonNode.class);
        assertEquals(expected, response.getStatusCodeValue()); assertNotNull(response.getBody());
        assertEquals(expected, response.getBody().path("code").asInt());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) {
            assertTrue(response.getBody().has("data")); assertTrue(response.getBody().get("data").isNull());
            assertFalse(response.getBody().toString().contains("private-storage-detail"));
        }
        return response.getBody();
    }
}
