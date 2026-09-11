package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.dto.BehaviorListVO;
import com.riskwarning.processing.dto.StructuredBehaviorVO;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BehaviorQueryServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long ASSESSMENT_ID = 20L;
    private static final String RUN_ID = "run-1";

    @Test
    void resolvesCurrentSuccessfulRunAndReturnsMappedBehaviors() {
        BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        AnalysisRun run = succeededRun(RUN_ID);
        when(runs.findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(
                ASSESSMENT_ID, AnalysisRunStatus.SUCCEEDED)).thenReturn(Optional.of(run));
        when(behaviors.findByScope(PROJECT_ID, ASSESSMENT_ID, RUN_ID))
                .thenReturn(Collections.singletonList(behavior()));

        BehaviorListVO result = new BehaviorQueryService(behaviors, runs)
                .listByScope(PROJECT_ID, ASSESSMENT_ID, null);

        assertTrue(result.isHasSuccessfulRun());
        assertEquals(RUN_ID, result.getAnalysisRunId());
        assertEquals(1, result.getBehaviors().size());
        StructuredBehaviorVO vo = result.getBehaviors().get(0);
        assertEquals("企业与供应商A签订采购合同", vo.getDescription());
        assertEquals("企业", vo.getSubject());
        assertEquals("签订采购合同", vo.getAction());
        assertEquals("供应商A采购合同", vo.getObject());
        assertEquals("COMPLETED", vo.getStatus());
        assertEquals(Double.valueOf(120.0), vo.getQuantitativeValue());
        assertEquals("万元", vo.getQuantitativeUnit());
        assertEquals("2024-03-15 00:00:00", vo.getBehaviorDate());
        assertEquals("2026-09-06 21:19:11", vo.getCreatedAt());
        assertEquals(Collections.singletonList("ev-1"), vo.getEvidenceIds());
        assertEquals("fact-extract-v1.0", vo.getExtractionPromptVersion());
    }

    @Test
    void returnsExplicitEmptyStateWhenAssessmentHasNoSuccessfulRun() {
        BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(
                ASSESSMENT_ID, AnalysisRunStatus.SUCCEEDED)).thenReturn(Optional.empty());

        BehaviorListVO result = new BehaviorQueryService(behaviors, runs)
                .listByScope(PROJECT_ID, ASSESSMENT_ID, null);

        assertFalse(result.isHasSuccessfulRun());
        assertNull(result.getAnalysisRunId());
        assertTrue(result.getBehaviors().isEmpty());
        // 没有成功运行就不该向 ES 发查询，避免把在途或失败运行的数据当结果返回
        verifyNoInteractions(behaviors);
    }

    @Test
    void queriesExplicitRunBelongingToAssessment() {
        BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        AnalysisRun run = succeededRun(RUN_ID);
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(RUN_ID, ASSESSMENT_ID, PROJECT_ID))
                .thenReturn(Optional.of(run));
        when(behaviors.findByScope(PROJECT_ID, ASSESSMENT_ID, RUN_ID))
                .thenReturn(Collections.singletonList(behavior()));

        BehaviorListVO result = new BehaviorQueryService(behaviors, runs)
                .listByScope(PROJECT_ID, ASSESSMENT_ID, RUN_ID);

        assertTrue(result.isHasSuccessfulRun());
        assertEquals(RUN_ID, result.getAnalysisRunId());
        verify(runs).findByAnalysisRunIdAndAssessmentIdAndProjectId(RUN_ID, ASSESSMENT_ID, PROJECT_ID);
    }

    @Test
    void rejectsExplicitRunNotOwnedByProjectOrAssessment() {
        BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(RUN_ID, ASSESSMENT_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new BehaviorQueryService(behaviors, runs)
                .listByScope(PROJECT_ID, ASSESSMENT_ID, RUN_ID));
        verifyNoInteractions(behaviors);
    }

    @Test
    void rejectsMissingScopeBeforeAnyQuery() {
        BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        BehaviorQueryService service = new BehaviorQueryService(behaviors, runs);

        assertThrows(IllegalArgumentException.class, () -> service.listByScope(null, ASSESSMENT_ID, null));
        assertThrows(IllegalArgumentException.class, () -> service.listByScope(PROJECT_ID, 0L, null));
        verifyNoInteractions(behaviors, runs);
        verify(runs, never())
                .findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(ASSESSMENT_ID, AnalysisRunStatus.SUCCEEDED);
    }

    private AnalysisRun succeededRun(String runId) {
        LocalDateTime started = LocalDateTime.of(2026, 9, 6, 21, 0, 0);
        AnalysisRun run = AnalysisRun.start(new AnalysisScope(PROJECT_ID, ASSESSMENT_ID, runId), started);
        run.succeed(started.plusMinutes(30));
        return run;
    }

    private Behavior behavior() {
        return Behavior.builder()
                .id("stable-behavior-id")
                .projectId(PROJECT_ID)
                .assessmentId(ASSESSMENT_ID)
                .analysisRunId(RUN_ID)
                .sourceDocumentId(101L)
                .subject("企业")
                .action("签订采购合同")
                .object("供应商A采购合同")
                .status("COMPLETED")
                .description("企业与供应商A签订采购合同")
                .quantitativeData(120.0)
                .quantitativeUnit("万元")
                .confidence(0.92)
                .evidenceIds(Collections.singletonList("ev-1"))
                .extractionModel("deepseek-chat")
                .extractionPromptVersion("fact-extract-v1.0")
                .schemaVersion("1.0")
                .createdAt(LocalDateTime.of(2026, 9, 6, 21, 19, 11))
                .behaviorDate(LocalDateTime.of(2024, 3, 15, 0, 0))
                .build();
    }
}