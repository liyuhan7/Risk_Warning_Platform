package com.riskwarning.report.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.report.repository.AnalysisRunRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AnalysisRunCompletionServiceTest {

    private final AnalysisRunRepository analysisRunRepository = mock(AnalysisRunRepository.class);
    private final AssessmentService assessmentService = mock(AssessmentService.class);
    private final AnalysisRunFailureService analysisRunFailureService = mock(AnalysisRunFailureService.class);
    private final AnalysisRunResultCleanupService cleanupService = mock(AnalysisRunResultCleanupService.class);
    private final AnalysisRunCompletionService service = new AnalysisRunCompletionService(
            analysisRunRepository, assessmentService, analysisRunFailureService, cleanupService);
    private final AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");

    @Test
    void aggregatesOnlyMatchingRunningRunThenCompletesIt() throws Exception {
        AnalysisRun run = AnalysisRun.start(scope, LocalDateTime.of(2026, 9, 5, 10, 0));
        when(analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 20L, 10L))
                .thenReturn(Optional.of(run));

        service.aggregateAndComplete(1L, scope);

        verify(assessmentService).aggregateInformation(1L, 10L, 20L, "run-1");
        verify(analysisRunRepository).saveAndFlush(run);
        verify(cleanupService).cleanupAfterSuccess(20L, "run-1");
    }

    @Test
    void ignoresDuplicateCompletedEvent() {
        AnalysisRun run = AnalysisRun.start(scope, LocalDateTime.of(2026, 9, 5, 10, 0));
        run.succeed(LocalDateTime.of(2026, 9, 5, 10, 1));
        when(analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 20L, 10L))
                .thenReturn(Optional.of(run));

        service.aggregateAndComplete(1L, scope);

        verifyNoInteractions(assessmentService);
        verify(analysisRunRepository, never()).saveAndFlush(any());
        verifyNoInteractions(cleanupService);
    }

    @Test
    void rejectsUnknownOrFailedRun() {
        when(analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 20L, 10L))
                .thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.aggregateAndComplete(1L, scope));

        AnalysisRun failed = AnalysisRun.start(scope, LocalDateTime.of(2026, 9, 5, 10, 0));
        failed.fail(LocalDateTime.of(2026, 9, 5, 10, 1));
        when(analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 20L, 10L))
                .thenReturn(Optional.of(failed));
        assertThrows(BusinessException.class, () -> service.aggregateAndComplete(1L, scope));
        verifyNoInteractions(assessmentService);
    }

    @Test
    void marksRunFailedAndSkipsCleanupWhenAggregationFails() {
        AnalysisRun run = AnalysisRun.start(scope, LocalDateTime.of(2026, 9, 5, 10, 0));
        when(analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 20L, 10L))
                .thenReturn(Optional.of(run));
        RuntimeException failure = new RuntimeException("汇总失败");
        doThrow(failure).when(assessmentService).aggregateInformation(1L, 10L, 20L, "run-1");

        assertThrows(RuntimeException.class, () -> service.aggregateAndComplete(1L, scope));

        verify(analysisRunFailureService).markFailed(eq(scope), any(LocalDateTime.class));
        verifyNoInteractions(cleanupService);
    }
}
