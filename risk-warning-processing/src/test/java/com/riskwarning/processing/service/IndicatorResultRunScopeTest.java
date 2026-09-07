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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndicatorResultRunScopeTest {

    @Test
    void replacesOnlyTheSameRunResultInsteadOfMergingRetryData() {
        IndicatorResultRepository repository = mock(IndicatorResultRepository.class);
        BehaviorProcessingService service = new BehaviorProcessingService();
        ReflectionTestUtils.setField(service, "indicatorResultRepository", repository);

        IndicatorResult existing = IndicatorResult.builder()
                .id(9L).assessmentId(20L).analysisRunId("run-a").indicatorEsId("indicator-a")
                .calculatedScore(80.0).createdAt(LocalDateTime.of(2026, 9, 5, 10, 0)).build();
        when(repository.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(20L, "run-a", "indicator-a"))
                .thenReturn(Optional.of(existing));

        Map<String, List<RelatedIndicator>> calculations = new HashMap<>();
        calculations.put("indicator-a", Collections.singletonList(RelatedIndicator.builder()
                .indicatorId("behavior-a").score(0.5).build()));
        Map<String, BehaviorProcessingService.IndicatorMetadataDTO> metadata = new HashMap<>();
        metadata.put("indicator-a", new BehaviorProcessingService.IndicatorMetadataDTO(
                "indicator-a", "指标 A", 1, "管理", "定性", 100.0));

        service.batchSaveIndicatorResults(calculations, metadata, 10L, 20L, "run-a");

        ArgumentCaptor<List<IndicatorResult>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(20L, "run-a", "indicator-a");
        verify(repository).saveAll(saved.capture());
        IndicatorResult result = saved.getValue().get(0);
        assertEquals(Long.valueOf(9L), result.getId());
        assertEquals("run-a", result.getAnalysisRunId());
        assertEquals(50.0, result.getCalculatedScore());
        assertEquals(1, result.getCalculationDetails().getRelatedIndicators().size());
    }
}
