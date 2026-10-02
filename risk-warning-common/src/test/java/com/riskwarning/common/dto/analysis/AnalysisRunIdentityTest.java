package com.riskwarning.common.dto.analysis;

import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.po.analysis.AnalysisRun;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisRunIdentityTest {
    private final AnalysisScope scope = new AnalysisScope(1L, 2L, "run");
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 30, 12, 0);
    @Test void preparationIsFrozenDefensivelyAndDoesNotFinishRun() {
        AnalysisRun run = AnalysisRun.start(scope, now, AnalysisExecutionMode.REASONING);
        run.freezeBehaviors(Arrays.asList("a"));
        com.fasterxml.jackson.databind.node.ObjectNode summary = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        summary.put("completionKind", "FULL"); run.prepareReport(summary);
        summary.put("completionKind", "PARTIAL");
        assertEquals("FULL", run.getAnalysisSummary().path("completionKind").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) run.getAnalysisSummary()).put("completionKind", "NONE");
        assertThrows(IllegalStateException.class, () -> run.prepareReport(summary));
        assertEquals(com.riskwarning.common.enums.AnalysisRunStatus.RUNNING, run.getStatus());
    }

    @Test
    void freezesSortedDefensiveManifestAndRejectsChangedReplay() {
        AnalysisRun run = AnalysisRun.start(scope, now, AnalysisExecutionMode.REASONING);
        List<String> input = new ArrayList<>(Arrays.asList("b", "a"));
        run.freezeBehaviors(input); input.clear();
        assertEquals(Arrays.asList("a", "b"), run.getExpectedBehaviorIds());
        assertThrows(UnsupportedOperationException.class, () -> run.getExpectedBehaviorIds().clear());
        run.freezeBehaviors(Arrays.asList("b", "a"));
        assertThrows(IllegalStateException.class, () -> run.freezeBehaviors(Arrays.asList("a", "c")));
        assertEquals(Arrays.asList("a", "b"), run.getExpectedBehaviorIds());
    }

    @Test
    void rejectsHistoricalModesBadManifestAndTerminalMutation() {
        for (AnalysisExecutionMode mode : new AnalysisExecutionMode[]{null}) {
            AnalysisRun historical = AnalysisRun.start(scope, now, mode);
            assertThrows(IllegalStateException.class, () -> historical.freezeBehaviors(Arrays.asList("a")));
        }
        AnalysisRun run = AnalysisRun.start(scope, now, AnalysisExecutionMode.REASONING);
        assertThrows(IllegalArgumentException.class, () -> run.freezeBehaviors(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> run.freezeBehaviors(Arrays.asList("a", "a")));
        assertThrows(IllegalArgumentException.class, () -> run.freezeBehaviors(Arrays.asList(" ")));
        run.freezeBehaviors(Arrays.asList("a")); run.succeed(now.plusMinutes(1));
        assertThrows(IllegalStateException.class, () -> run.freezeBehaviors(Arrays.asList("b")));
        assertEquals(AnalysisExecutionMode.REASONING, run.getExecutionMode());
    }
}
