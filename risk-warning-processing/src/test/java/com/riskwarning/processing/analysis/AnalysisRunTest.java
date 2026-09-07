package com.riskwarning.processing.analysis;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisRunTest {

    private final LocalDateTime start = LocalDateTime.of(2026, 9, 5, 10, 0);

    @Test
    void rejectsMissingOrAmbiguousIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisScope(null, 2L, "run"));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisScope(1L, 0L, "run"));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisScope(1L, 2L, null));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisScope(1L, 2L, " "));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisScope(1L, 2L, " run"));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisScope(1L, 2L, new String(new char[65]).replace('\0', 'a')));
    }

    @Test
    void preservesScopeAndStartsWithoutFinishTime() {
        AnalysisScope scope = new AnalysisScope(1L, 2L, "run-1");
        AnalysisRun run = AnalysisRun.start(scope, start);
        assertEquals(scope, run.toScope());
        assertEquals(AnalysisRunStatus.RUNNING, run.getStatus());
        assertEquals(start, run.getStartedAt());
        assertNull(run.getFinishedAt());
    }

    @Test
    void rejectsInvalidTimesWithoutChangingState() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisRun.start(null, start));
        AnalysisRun run = AnalysisRun.start(new AnalysisScope(1L, 2L, "run"), start);
        assertThrows(IllegalArgumentException.class, () -> run.succeed(start.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> run.fail(null));
        assertEquals(AnalysisRunStatus.RUNNING, run.getStatus());
        assertNull(run.getFinishedAt());
    }

    @Test
    void terminalStateCannotBeOverwritten() {
        AnalysisRun run = AnalysisRun.start(new AnalysisScope(1L, 2L, "run"), start);
        run.succeed(start.plusMinutes(1));
        assertThrows(IllegalStateException.class, () -> run.fail(start.plusMinutes(2)));
        assertThrows(IllegalStateException.class, () -> run.succeed(start.plusMinutes(2)));
        assertEquals(AnalysisRunStatus.SUCCEEDED, run.getStatus());
        assertEquals(start.plusMinutes(1), run.getFinishedAt());
    }

    @Test
    void failedRerunDoesNotAlterPreviousSuccess() {
        AnalysisRun first = AnalysisRun.start(new AnalysisScope(1L, 2L, "run-1"), start);
        first.succeed(start.plusMinutes(1));
        AnalysisRun second = AnalysisRun.start(new AnalysisScope(1L, 2L, "run-2"), start.plusMinutes(2));
        second.fail(start.plusMinutes(3));
        assertEquals(AnalysisRunStatus.SUCCEEDED, first.getStatus());
        assertEquals(AnalysisRunStatus.FAILED, second.getStatus());
        assertNotEquals(first.toScope(), second.toScope());
        assertThrows(IllegalStateException.class, () -> second.succeed(start.plusMinutes(4)));
    }
}
