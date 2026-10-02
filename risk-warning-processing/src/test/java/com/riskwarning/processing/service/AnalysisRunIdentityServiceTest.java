package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisRunIdentityServiceTest {
    private final AnalysisScope scope = new AnalysisScope(1L, 2L, "run");
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final AnalysisRunIdentityService service = new AnalysisRunIdentityService(runs);
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 30, 12, 0);

    private AnalysisRun run(AnalysisExecutionMode mode) {
        AnalysisRun run = AnalysisRun.start(scope, now, mode);
        when(runs.lockScope("run", 2L, 1L)).thenReturn(Optional.of(run));
        return run;
    }

    private Behavior behavior(Long projectId) {
        return Behavior.builder().id("b").projectId(projectId).assessmentId(2L).analysisRunId("run").build();
    }

    @Test
    void freezesOnlyScopedNewReasoningRun() {
        AnalysisRun run = run(AnalysisExecutionMode.REASONING);
        assertTrue(service.freeze(scope, Arrays.asList(behavior(1L))));
        assertEquals(Arrays.asList("b"), run.getExpectedBehaviorIds());
        verify(runs).saveAndFlush(run);
    }

    @Test
    void rejectsScopeDriftBeforeWriting() {
        AnalysisRun run = run(AnalysisExecutionMode.REASONING);
        assertThrows(IllegalArgumentException.class, () -> service.freeze(scope, Arrays.asList(behavior(9L))));
        assertNull(run.getExpectedBehaviorIds());
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUndeclaredHistoryAndSkipsTerminalRun() {
        AnalysisRun run = run(null);
        assertThrows(IllegalStateException.class, () -> service.freeze(scope, Arrays.asList(behavior(1L))));
        run.completeWithoutDecision(now.plusMinutes(1));
        assertFalse(service.freeze(scope, Arrays.asList(behavior(1L))));
        verify(runs, never()).saveAndFlush(any());
    }

    @Test void sameIdentityWithChangedFactIsRejectedWithoutReplacingHash() {
        AnalysisRun run = run(AnalysisExecutionMode.REASONING);
        Behavior behavior = behavior(1L); behavior.setDescription("期限为一天"); behavior.setQuantitativeData(1d);
        service.freeze(scope, Arrays.asList(behavior)); String hash = run.getBehaviorInputHash();
        behavior.setQuantitativeData(2d);
        assertThrows(com.riskwarning.common.exception.AnalysisInputConflictException.class, () -> service.freeze(scope, Arrays.asList(behavior)));
        assertEquals(hash, run.getBehaviorInputHash());
        verify(runs, times(1)).saveAndFlush(run);
    }

    @Test void storageTimestampAndEvidenceOrderingDoNotChangeBusinessFingerprint() {
        AnalysisRun run = run(AnalysisExecutionMode.REASONING);
        Behavior behavior = behavior(1L); behavior.setEvidenceIds(Arrays.asList("e2", "e1"));
        service.freeze(scope, Arrays.asList(behavior)); String hash = run.getBehaviorInputHash();
        behavior.setEvidenceIds(Arrays.asList("e1", "e2")); behavior.setCreatedAt(now.plusDays(1));
        service.freeze(scope, Arrays.asList(behavior)); assertEquals(hash, run.getBehaviorInputHash());
        behavior.setBehaviorDate(now);
        assertThrows(com.riskwarning.common.exception.AnalysisInputConflictException.class, () -> service.freeze(scope, Arrays.asList(behavior)));
    }
}
