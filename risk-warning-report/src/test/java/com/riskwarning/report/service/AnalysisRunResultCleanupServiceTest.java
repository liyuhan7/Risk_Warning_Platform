package com.riskwarning.report.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.report.repository.AnalysisResultRepository;
import com.riskwarning.report.repository.IndicatorResultRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AnalysisRunResultCleanupServiceTest {

    @Test
    void removesOnlySupersededBusinessResultsAfterSuccessfulSwitch() throws Exception {
        IndicatorResultRepository indicatorResults = mock(IndicatorResultRepository.class);
        AnalysisResultRepository analysisResults = mock(AnalysisResultRepository.class);
        List<String> deletedScopes = new ArrayList<>();
        AnalysisRunResultCleanupService service = new AnalysisRunResultCleanupService(
                indicatorResults, mock(ElasticsearchClient.class), analysisResults) {
            @Override
            void deleteObsoleteDocuments(String index, Long assessmentId, String currentRunId) {
                deletedScopes.add(index + ":" + assessmentId + ":" + currentRunId);
            }
        };

        service.cleanupAfterSuccess(20L, "run-current");

        verify(indicatorResults).deleteByAssessmentIdAndAnalysisRunIdNot(20L, "run-current");
        verify(analysisResults).deleteByAssessmentIdAndAnalysisRunIdNot(20L, "run-current");
        assertEquals(Arrays.asList(
                ElasticSearchConfig.RISK_INDEX + ":20:run-current",
                "t_behavior:20:run-current"), deletedScopes);
        verifyNoMoreInteractions(indicatorResults, analysisResults);
    }
}
