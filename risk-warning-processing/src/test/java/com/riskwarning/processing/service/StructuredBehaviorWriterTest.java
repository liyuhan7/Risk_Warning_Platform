package com.riskwarning.processing.service;

import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ClassifierClient;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 分类完整性决定事实能否落库，事实写入不依赖旧描述向量。 */
class StructuredBehaviorWriterTest {
    private final BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
    private final ClassifierClient classifier = mock(ClassifierClient.class);
    private final StructuredBehaviorWriter writer = new StructuredBehaviorWriter(repository, classifier);

    @Test void classifiedFactsWithoutDescriptionVectorsAreWritten() {
        Behavior behavior = fact("stable-id");
        when(classifier.classifyBatch(any())).thenReturn(classification("定量", "环境"));
        writer.enrichAndWrite(Collections.singletonList(behavior));
        assertEquals(Collections.singletonList("供应链"), behavior.getTags());
        assertEquals("定量", behavior.getType());
        assertEquals("环境", behavior.getDimension());
        assertNull(behavior.getDescriptionVector());
        verify(repository).writeAll(Collections.singletonList(behavior));
    }

    @Test void unavailableClassificationPreventsWriting() {
        when(classifier.classifyBatch(any())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> writer.enrichAndWrite(Collections.singletonList(fact("b"))));
        verifyNoInteractions(repository);
    }

    @Test void missingClassificationPreventsWriting() {
        when(classifier.classifyBatch(any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> writer.enrichAndWrite(Collections.singletonList(fact("b"))));
        verifyNoInteractions(repository);
    }

    @Test void resultCountMismatchCannotWritePartialBatch() {
        when(classifier.classifyBatch(any())).thenReturn(classification("定性", "治理"));
        assertThrows(IllegalStateException.class, () -> writer.enrichAndWrite(Arrays.asList(fact("b"), fact("c"))));
        verifyNoInteractions(repository);
    }

    @Test void incompleteClassificationPreventsWriting() {
        for (Map<String, Object> response : Arrays.asList(classification(null, "治理"), classification("定性", " "))) {
            when(classifier.classifyBatch(any())).thenReturn(response);
            assertThrows(IllegalStateException.class, () -> writer.enrichAndWrite(Collections.singletonList(fact("b"))));
        }
        verifyNoInteractions(repository);
    }

    @Test void invalidIdentityIsRejectedBeforeClassification() {
        assertThrows(IllegalArgumentException.class, () -> writer.enrichAndWrite(Collections.singletonList(fact(null))));
        verifyNoInteractions(classifier, repository);
    }

    @Test void emptyBatchHasNoExternalCalls() {
        writer.enrichAndWrite(Collections.emptyList());
        verifyNoInteractions(classifier, repository);
    }

    private Behavior fact(String id) {
        return Behavior.builder().id(id).description("企业完成审查").build();
    }

    private Map<String, Object> classification(String type, String dimension) {
        Map<String, Object> item = new HashMap<>();
        item.put("tags", Collections.singletonList("供应链"));
        item.put("type", type); item.put("dimension", dimension);
        Map<String, Object> response = new HashMap<>();
        response.put("results", Collections.singletonList(item));
        return response;
    }
}
