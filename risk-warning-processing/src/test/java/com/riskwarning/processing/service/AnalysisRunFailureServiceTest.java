package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AnalysisRunFailureServiceTest {

    @Test
    void marksOnlyMatchingRunningRunAsFailed() {
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
        AnalysisRun run = AnalysisRun.start(scope, LocalDateTime.of(2026, 9, 5, 10, 0));
        AnalysisRunRepository repository = mock(AnalysisRunRepository.class);
        when(repository.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 20L, 10L))
                .thenReturn(Optional.of(run));

        new AnalysisRunFailureService(repository).markFailed(
                scope, LocalDateTime.of(2026, 9, 5, 10, 1));

        assertEquals("FAILED", run.getStatus().name());
        verify(repository).saveAndFlush(run);
    }

    @Test
    void rejectsUnknownRun() {
        AnalysisRunRepository repository = mock(AnalysisRunRepository.class);
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
        when(repository.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 20L, 10L))
                .thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> new AnalysisRunFailureService(repository)
                .markFailed(scope, LocalDateTime.of(2026, 9, 5, 10, 1)));
    }
}
