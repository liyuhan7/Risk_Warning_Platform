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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behavior 写入前置条件测试：分类或向量任一环节失败必须失败整批写入，
 * 防止无向量 Behavior 进入 LEGACY 指标链后把失败误判为成功。
 */
class StructuredBehaviorWriterTest {

    private final BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
    private final ClassifierClient classifier = mock(ClassifierClient.class);
    private final VectorizationClient vectorization = mock(VectorizationClient.class);

    @Test
    void enrichesBeforeWriting() {
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定量", "环境"));
        when(vectorization.batchVectorize(any())).thenReturn(vectorResponse(true,
                Collections.singletonList(Arrays.asList(0.1D, 0.2D))));

        new StructuredBehaviorWriter(repository, classifier, vectorization)
                .enrichAndWrite(Collections.singletonList(behavior));

        assertEquals(Collections.singletonList("供应链"), behavior.getTags());
        assertEquals("定量", behavior.getType());
        assertEquals("环境", behavior.getDimension());
        assertEquals(Arrays.asList(0.1F, 0.2F), behavior.getDescriptionVector());
        verify(repository).writeAll(Collections.singletonList(behavior));
    }

    @Test
    void vectorServiceFailureBlocksBehaviorWrite() {
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));
        when(vectorization.batchVectorize(any())).thenThrow(new RuntimeException("服务不可用"));

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(
                Collections.singletonList(behavior)));

        verifyNoInteractions(repository);
    }

    @Test
    void vectorServiceUnsuccessResponseBlocksBehaviorWrite() {
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));
        when(vectorization.batchVectorize(any())).thenReturn(vectorResponse(false,
                Collections.singletonList(Arrays.asList(0.1D))));

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(
                Collections.singletonList(behavior)));

        verifyNoInteractions(repository);
    }

    @Test
    void vectorCountMismatchBlocksBehaviorWrite() {
        Behavior first = Behavior.builder().id("stable-id-1").description("企业完成审查").build();
        Behavior second = Behavior.builder().id("stable-id-2").description("企业提交报告").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));
        when(vectorization.batchVectorize(any())).thenReturn(vectorResponse(true,
                Collections.singletonList(Arrays.asList(0.1D))));

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(Arrays.asList(first, second)));

        verifyNoInteractions(repository);
    }

    @Test
    void nullOrEmptyVectorBlocksBehaviorWrite() {
        Behavior nullVector = Behavior.builder().id("stable-id-1").description("企业完成审查").build();
        Behavior emptyVector = Behavior.builder().id("stable-id-2").description("企业提交报告").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));
        when(vectorization.batchVectorize(any())).thenReturn(vectorResponse(true, Arrays.asList(
                null,
                Collections.emptyList())));

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(Arrays.asList(nullVector, emptyVector)));

        verifyNoInteractions(repository);
    }

    @Test
    void invalidVectorValueBlocksBehaviorWrite() {
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));
        when(vectorization.batchVectorize(any())).thenReturn(vectorResponse(true,
                Collections.singletonList(Arrays.asList(0.1D, Double.NaN))));

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(
                Collections.singletonList(behavior)));

        verifyNoInteractions(repository);
    }

    @Test
    void behaviorWithoutVectorIsRejectedBeforeWrite() {
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        behavior.setType("定性");
        behavior.setDimension("治理");

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(
                Collections.singletonList(behavior)));

        verifyNoInteractions(vectorization, repository);
    }

    @Test
    void classificationEmptyResponseBlocksBehaviorWrite() {
        Behavior behavior = Behavior.builder().id("stable-id").description("企业完成审查").build();
        when(classifier.classifyBatch(any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(
                Collections.singletonList(behavior)));

        verifyNoInteractions(vectorization, repository);
    }

    @Test
    void classificationResultCountMismatchBlocksBehaviorWrite() {
        Behavior first = Behavior.builder().id("stable-id-1").description("企业完成审查").build();
        Behavior second = Behavior.builder().id("stable-id-2").description("企业提交报告").build();
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));

        assertThrows(IllegalStateException.class, () -> new StructuredBehaviorWriter(
                repository, classifier, vectorization).enrichAndWrite(Arrays.asList(first, second)));

        verifyNoInteractions(vectorization, repository);
    }

    private Map<String, Object> vectorResponse(boolean success, Object vectors) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("vectors", vectors);
        return response;
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
