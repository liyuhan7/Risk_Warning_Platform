package com.riskwarning.processing.service;

import com.riskwarning.common.po.analysis.AnalysisResult;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.processing.repository.AnalysisResultRepository;
import com.riskwarning.processing.repository.IndicatorResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class P2ResultPersistenceServiceTest {

    @Test
    void successWriteUsesOneTransactionAndFlushesBothRepositories() throws Exception {
        AnalysisResultRepository analyses = mock(AnalysisResultRepository.class);
        IndicatorResultRepository indicators = mock(IndicatorResultRepository.class);
        P2ResultPersistenceService service = new P2ResultPersistenceService(analyses, indicators);
        AnalysisResult analysis = new AnalysisResult();
        IndicatorResult indicator = new IndicatorResult();

        service.saveSuccess(Collections.singletonList(analysis), Collections.singletonList(indicator));

        verify(analyses).saveAll(Collections.singletonList(analysis));
        verify(indicators).saveAll(Collections.singletonList(indicator));
        verify(analyses).flush();
        verify(indicators).flush();
        Method method = P2ResultPersistenceService.class.getMethod("saveSuccess", java.util.List.class,
                java.util.List.class);
        assertNotNull(method.getAnnotation(Transactional.class));
    }
}
