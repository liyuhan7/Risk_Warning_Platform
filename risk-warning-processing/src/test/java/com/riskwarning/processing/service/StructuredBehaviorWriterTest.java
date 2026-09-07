package com.riskwarning.processing.service;

import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ClassifierClient;
import com.riskwarning.processing.client.VectorizationClient;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StructuredBehaviorWriterTest {

    @Test
    void enrichesBeforeWriting() {
        BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
        ClassifierClient classifier = mock(ClassifierClient.class);
        VectorizationClient vectorization = mock(VectorizationClient.class);
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定量", "环境"));
        Map<String, Object> vectorResponse = new HashMap<>();
        vectorResponse.put("success", true);
        vectorResponse.put("vectors", Collections.singletonList(Arrays.asList(0.1D, 0.2D)));
        when(vectorization.batchVectorize(any())).thenReturn(vectorResponse);

        new StructuredBehaviorWriter(repository, classifier, vectorization)
                .enrichAndWrite(Collections.singletonList(behavior));

        assertEquals(Collections.singletonList("供应链"), behavior.getTags());
        assertEquals("定量", behavior.getType());
        assertEquals("环境", behavior.getDimension());
        assertEquals(Arrays.asList(0.1F, 0.2F), behavior.getDescriptionVector());
        verify(repository).writeAll(Collections.singletonList(behavior));
    }

    @Test
    void vectorFailureDoesNotBlockBehaviorWrite() {
        BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
        ClassifierClient classifier = mock(ClassifierClient.class);
        VectorizationClient vectorization = mock(VectorizationClient.class);
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));
        when(vectorization.batchVectorize(any())).thenThrow(new RuntimeException("服务不可用"));

        new StructuredBehaviorWriter(repository, classifier, vectorization)
                .enrichAndWrite(Collections.singletonList(behavior));

        assertNull(behavior.getDescriptionVector());
        verify(repository).writeAll(Collections.singletonList(behavior));
    }

    @Test
    void classificationEmptyResponseBlocksBehaviorWrite() {
        BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
        ClassifierClient classifier = mock(ClassifierClient.class);
        VectorizationClient vectorization = mock(VectorizationClient.class);
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        when(classifier.classifyBatch(any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(
                Collections.singletonList(behavior)));

        verifyNoInteractions(vectorization, repository);
    }

    @Test
    void classificationResultCountMismatchBlocksBehaviorWrite() {
        BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
        ClassifierClient classifier = mock(ClassifierClient.class);
        VectorizationClient vectorization = mock(VectorizationClient.class);
        Behavior first = Behavior.builder().id("stable-id-1").description("企业完成审查").build();
        Behavior second = Behavior.builder().id("stable-id-2").description("企业提交报告").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(Arrays.asList(first, second)));

        verifyNoInteractions(vectorization, repository);
    }

    private Map<String, Object> classification(String type, String dimension) {
        Map<String, Object> item = new HashMap<>();
        item.put("tags", Collections.singletonList("供应链"));
        item.put("type", type);
        item.put("dimension", dimension);
        Map<String, Object> response = new HashMap<>();
        response.put("results", Collections.singletonList(item));
        return response;
    }
}
