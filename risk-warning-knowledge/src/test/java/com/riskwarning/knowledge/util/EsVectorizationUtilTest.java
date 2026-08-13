package com.riskwarning.knowledge.util;

import com.riskwarning.knowledge.config.VectorConfig;
import com.riskwarning.knowledge.repository.ElasticsearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsVectorizationUtilTest {

    @Mock
    private ElasticsearchRepository esRepository;

    @Mock
    private VectorizationUtil vectorizationUtil;

    private EsVectorizationUtil esVectorizationUtil;

    @BeforeEach
    void setUp() {
        VectorConfig vectorConfig = new VectorConfig();
        vectorConfig.setDimension(768);
        esVectorizationUtil = new EsVectorizationUtil(esRepository, vectorizationUtil, vectorConfig);
    }

    @Test
    void vectorizeIndicatorsProcessesMissingDocumentsUntilCoverageIsComplete() {
        when(esRepository.getDocumentCount("t_indicator")).thenReturn(3L);
        when(esRepository.getDocumentCountWithField("t_indicator", "name_vector"))
                .thenReturn(1L, 3L);
        when(esRepository.searchDocumentsMissingField(
                eq("t_indicator"), eq("name"), eq("name_vector"), eq(50), eq(Map.class)))
                .thenReturn(Arrays.asList(document("a", "指标 A"), document("b", "指标 B")))
                .thenReturn(Collections.emptyList());
        when(vectorizationUtil.batchVectorize(Arrays.asList("指标 A", "指标 B")))
                .thenReturn(Arrays.asList(vector(), vector()));
        when(esRepository.batchUpdateVectorFields(eq("t_indicator"), anyList()))
                .thenReturn(new ElasticsearchRepository.BulkUpdateResult(2, 0));

        EsVectorizationUtil.VectorizationResult result = esVectorizationUtil.vectorizeIndicators();

        assertEquals(3, result.getTotalCount());
        assertEquals(3, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
        assertNull(result.getError());
    }

    @Test
    void vectorizeIndicatorsReportsIncompleteCoverageWhenBulkUpdateFails() {
        when(esRepository.getDocumentCount("t_indicator")).thenReturn(2L);
        when(esRepository.getDocumentCountWithField("t_indicator", "name_vector"))
                .thenReturn(0L, 1L);
        when(esRepository.searchDocumentsMissingField(
                eq("t_indicator"), eq("name"), eq("name_vector"), eq(50), eq(Map.class)))
                .thenReturn(Arrays.asList(document("a", "指标 A"), document("b", "指标 B")));
        when(vectorizationUtil.batchVectorize(Arrays.asList("指标 A", "指标 B")))
                .thenReturn(Arrays.asList(vector(), vector()));
        when(esRepository.batchUpdateVectorFields(eq("t_indicator"), anyList()))
                .thenReturn(new ElasticsearchRepository.BulkUpdateResult(1, 1));

        EsVectorizationUtil.VectorizationResult result = esVectorizationUtil.vectorizeIndicators();

        assertEquals(2, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailedCount());
        assertNotNull(result.getError());
    }

    private static ElasticsearchRepository.DocumentWithId<Map> document(
            String id, String name) {
        ElasticsearchRepository.DocumentWithId<Map> document =
                new ElasticsearchRepository.DocumentWithId<>();
        document.setId(id);
        document.setSource(Collections.singletonMap("name", name));
        return document;
    }

    private static float[] vector() {
        return new float[768];
    }
}
