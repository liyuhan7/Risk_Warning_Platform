package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisRunModeResolverTest {
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final AnalysisRunModeResolver resolver = new AnalysisRunModeResolver(runs);
    private final AnalysisScope scope = new AnalysisScope(1L, 2L, "run");

    @Test void acceptsExplicitAnalysisIdentity() {
        AnalysisRun run = AnalysisRun.start(scope, LocalDateTime.now());
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.of(run));
        assertEquals(AnalysisExecutionMode.REASONING, resolver.resolve(scope));
    }

    @Test void doesNotUpgradeMissingIdentity() {
        AnalysisRun run = AnalysisRun.start(scope, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "executionMode", null);
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.of(run));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(scope));
        assertNull(run.getExecutionMode());
    }

    @Test void missingRunIsRejected() {
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> resolver.resolve(scope));
    }
}
