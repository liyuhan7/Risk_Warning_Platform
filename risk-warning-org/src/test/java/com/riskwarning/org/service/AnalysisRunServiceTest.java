package com.riskwarning.org.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.org.repository.AnalysisRunRepository;
import com.riskwarning.org.repository.AssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalysisRunServiceTest {

    private AnalysisRunRepository runRepository;
    private AssessmentRepository assessmentRepository;
    private AnalysisRunService service;
    private final AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 5, 13, 0);

    @BeforeEach
    void setUp() {
        runRepository = mock(AnalysisRunRepository.class);
        assessmentRepository = mock(AssessmentRepository.class);
        service = new AnalysisRunService(runRepository, assessmentRepository);
    }

    @Test
    void startsRunOnlyForAssessmentOwnedByProject() {
        Assessment assessment = Assessment.builder().id(20L).projectId(10L).build();
        when(assessmentRepository.findById(20L)).thenReturn(assessment);
        when(runRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AnalysisRun run = service.start(scope, now);

        assertEquals(scope, run.toScope());
        assertEquals(AnalysisRunStatus.RUNNING, run.getStatus());
        verify(runRepository).existsByAssessmentIdAndStatus(20L, AnalysisRunStatus.RUNNING);
    }

    @Test
    void rejectsMissingWrongProjectOrExistingRun() {
        assertThrows(BusinessException.class, () -> service.start(null, now));
        assertThrows(BusinessException.class, () -> service.start(scope, null));
        when(assessmentRepository.findById(20L))
                .thenReturn(Assessment.builder().id(20L).projectId(99L).build());
        assertThrows(BusinessException.class, () -> service.start(scope, now));

        when(assessmentRepository.findById(20L))
                .thenReturn(Assessment.builder().id(20L).projectId(10L).build());
        when(runRepository.existsByAssessmentIdAndStatus(20L, AnalysisRunStatus.RUNNING))
                .thenReturn(true);
        assertThrows(BusinessException.class, () -> service.start(scope, now));
        verify(runRepository, never()).saveAndFlush(any());
    }

    @Test
    void marksOnlyRunningDispatchAsFailed() {
        AnalysisRun run = AnalysisRun.start(scope, now);
        when(runRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId("run-1", 20L, 10L))
                .thenReturn(Optional.of(run));

        service.markDispatchFailed(scope, now.plusMinutes(1));

        assertEquals(AnalysisRunStatus.FAILED, run.getStatus());
        verify(runRepository).saveAndFlush(run);
        service.markDispatchFailed(scope, now.plusMinutes(2));
        verify(runRepository, times(1)).saveAndFlush(run);
    }
}
