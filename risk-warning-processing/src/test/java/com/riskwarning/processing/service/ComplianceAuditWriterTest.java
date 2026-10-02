package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.processing.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplianceAuditWriterTest {
    private final AnalysisScope scope = new AnalysisScope(1L, 2L, "run");
    private final AnalysisAttemptRepository attempts = mock(AnalysisAttemptRepository.class);
    private final AnalysisResultRepository results = mock(AnalysisResultRepository.class);
    private final RetrievalAuditRepository audits = mock(RetrievalAuditRepository.class);
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final ComplianceAuditWriter writer = new ComplianceAuditWriter(attempts, results, audits, runs);
    private AnalysisRun run;
    private RetrievalAudit audit;
    private AnalysisResult result;
    @BeforeEach void setup() {
        run = AnalysisRun.start(scope, LocalDateTime.now(), AnalysisExecutionMode.REASONING); run.freezeBehaviors(Arrays.asList("b"));
        audit = RetrievalAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").behaviorId("b")
                .analysisStatus(AnalysisAuditStatus.WAITING_ANALYSIS).build();
        result = AnalysisResult.builder().id("r").schemaVersion("1.1").assessmentId(2L).analysisRunId("run").behaviorId("b")
                .indicatorId("i").conditionMet(true).analyzedAt(LocalDateTime.now()).build();
        when(runs.lockScope("run", 2L, 1L)).thenReturn(Optional.of(run));
        when(audits.findByAnalysisRunIdAndBehaviorId("run", "b")).thenReturn(Optional.of(audit));
    }
    @Test void firstValidResultUpdatesResultAndAudit() {
        writer.complete(scope, "b", AnalysisAuditStatus.ANALYZED, result, null, null);
        verify(results).saveAndFlush(result); verify(audits).saveAndFlush(audit);
        assertEquals(AnalysisAuditStatus.ANALYZED, audit.getAnalysisStatus());
    }
    @Test void semanticReplayDoesNotReplaceOriginalTimestamp() {
        audit.setAnalysisStatus(AnalysisAuditStatus.ANALYZED);
        AnalysisResult existing = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().convertValue(result, AnalysisResult.class);
        existing.setAnalyzedAt(result.getAnalyzedAt().minusMinutes(1));
        when(results.findByAnalysisRunIdAndBehaviorIdAndIndicatorId("run", "b", "i")).thenReturn(Optional.of(existing));
        writer.complete(scope, "b", AnalysisAuditStatus.ANALYZED, result, null, null);
        verify(results, never()).saveAndFlush(any()); verify(audits, never()).saveAndFlush(any());
        result.setConditionMet(false);
        assertThrows(com.riskwarning.common.exception.AnalysisInputConflictException.class,
                () -> writer.complete(scope, "b", AnalysisAuditStatus.ANALYZED, result, null, null));
    }
    @Test void terminalAndPreparedRunRejectLateWrites() {
        run.prepareReport(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        assertThrows(IllegalStateException.class, () -> writer.complete(scope, "b", AnalysisAuditStatus.ANALYZED, result, null, null));
        run.fail(LocalDateTime.now());
        assertThrows(IllegalStateException.class, () -> writer.attempt(scope, "b", "hash", "m", "p", "raw",
                AnalysisAttemptStatus.VALID, Collections.emptyList(), null, null));
        verifyNoInteractions(attempts, results, audits);
    }
}
