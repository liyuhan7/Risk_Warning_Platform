package com.riskwarning.processing.service;

import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.risk.RelatedIndicator;
import com.riskwarning.processing.repository.IndicatorResultRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LEGACY 批量落库测试：只替换同 run 结果、关键结果 saveAllAndFlush、
 * flush 后回读校验数量与身份，任何不一致都禁止进入完成流程。
 */
class IndicatorResultRunScopeTest {

    private final IndicatorResultRepository repository = mock(IndicatorResultRepository.class);
    private final BehaviorProcessingService service = new BehaviorProcessingService();

    private Map<String, List<RelatedIndicator>> calculations() {
        Map<String, List<RelatedIndicator>> calculations = new HashMap<>();
        calculations.put("indicator-a", Collections.singletonList(RelatedIndicator.builder()
                .indicatorId("behavior-a").score(0.5).build()));
        return calculations;
    }

    private Map<String, BehaviorProcessingService.IndicatorMetadataDTO> metadata() {
        Map<String, BehaviorProcessingService.IndicatorMetadataDTO> metadata = new HashMap<>();
        metadata.put("indicator-a", new BehaviorProcessingService.IndicatorMetadataDTO(
                "indicator-a", "指标 A", 1, "管理", "定性", 100.0));
        return metadata;
    }

    @Test
    void replacesOnlyTheSameRunResultInsteadOfMergingRetryData() {
        ReflectionTestUtils.setField(service, "indicatorResultRepository", repository);

        IndicatorResult existing = IndicatorResult.builder()
                .id(9L).assessmentId(20L).analysisRunId("run-a").indicatorEsId("indicator-a")
                .calculatedScore(80.0).createdAt(LocalDateTime.of(2026, 9, 5, 10, 0)).build();
        when(repository.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(20L, "run-a", "indicator-a"))
                .thenReturn(Optional.of(existing));
        when(repository.findByAssessmentIdAndAnalysisRunId(20L, "run-a"))
                .thenReturn(Collections.singletonList(IndicatorResult.builder()
                        .indicatorEsId("indicator-a").build()));

        service.batchSaveIndicatorResults(calculations(), metadata(), 10L, 20L, "run-a");

        ArgumentCaptor<List<IndicatorResult>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(20L, "run-a", "indicator-a");
        verify(repository).saveAllAndFlush(saved.capture());
        IndicatorResult result = saved.getValue().get(0);
        assertEquals(Long.valueOf(9L), result.getId());
        assertEquals("run-a", result.getAnalysisRunId());
        assertEquals(50.0, result.getCalculatedScore());
        assertEquals(1, result.getCalculationDetails().getRelatedIndicators().size());
    }

    @Test
    void emptyCalculationInputFailsWithoutSave() {
        ReflectionTestUtils.setField(service, "indicatorResultRepository", repository);

        assertThrows(IllegalStateException.class, () -> service.batchSaveIndicatorResults(
                new HashMap<>(), metadata(), 10L, 20L, "run-a"));

        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void allEmptyListsFailWithoutSave() {
        ReflectionTestUtils.setField(service, "indicatorResultRepository", repository);
        Map<String, List<RelatedIndicator>> calculations = new HashMap<>();
        calculations.put("indicator-a", Collections.emptyList());

        assertThrows(IllegalStateException.class, () -> service.batchSaveIndicatorResults(
                calculations, metadata(), 10L, 20L, "run-a"));

        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void persistedCountMismatchBlocksCompletionGate() {
        ReflectionTestUtils.setField(service, "indicatorResultRepository", repository);
        when(repository.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(20L, "run-a", "indicator-a"))
                .thenReturn(Optional.empty());
        // 回读结果少于预期：部分丢失
        when(repository.findByAssessmentIdAndAnalysisRunId(20L, "run-a"))
                .thenReturn(Collections.emptyList());

        assertThrows(IllegalStateException.class, () -> service.batchSaveIndicatorResults(
                calculations(), metadata(), 10L, 20L, "run-a"));

        verify(repository).saveAllAndFlush(any());
    }

    @Test
    void persistedIdMismatchBlocksCompletionGate() {
        ReflectionTestUtils.setField(service, "indicatorResultRepository", repository);
        when(repository.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(20L, "run-a", "indicator-a"))
                .thenReturn(Optional.empty());
        when(repository.findByAssessmentIdAndAnalysisRunId(20L, "run-a"))
                .thenReturn(Collections.singletonList(IndicatorResult.builder()
                        .indicatorEsId("indicator-b").build()));

        assertThrows(IllegalStateException.class, () -> service.batchSaveIndicatorResults(
                calculations(), metadata(), 10L, 20L, "run-a"));

        verify(repository).saveAllAndFlush(any());
    }

    @Test
    void saveFailurePropagatesToDurableRetry() {
        ReflectionTestUtils.setField(service, "indicatorResultRepository", repository);
        when(repository.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(20L, "run-a", "indicator-a"))
                .thenReturn(Optional.empty());
        when(repository.saveAllAndFlush(any())).thenThrow(new IllegalStateException("数据库不可用"));

        assertThrows(IllegalStateException.class, () -> service.batchSaveIndicatorResults(
                calculations(), metadata(), 10L, 20L, "run-a"));
    }
}
