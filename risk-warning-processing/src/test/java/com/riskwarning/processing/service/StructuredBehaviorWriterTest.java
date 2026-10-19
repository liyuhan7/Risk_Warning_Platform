package com.riskwarning.processing.service;

import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ClassifierClient;
import com.riskwarning.processing.config.AnalysisProcessingProperties;
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
    private final AnalysisProcessingProperties properties = new AnalysisProcessingProperties();
    private final StructuredBehaviorWriter writer = new StructuredBehaviorWriter(repository, classifier, properties);

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

    @Test void largeFileIsClassifiedInBoundedBatchesWithoutChangingOrder() {
        List<Integer> batchSizes = new ArrayList<>();
        when(classifier.classifyBatch(any())).thenAnswer(invocation -> {
            Map<String, Object> request = invocation.getArgument(0);
            List<?> items = (List<?>) request.get("items");
            batchSizes.add(items.size());
            if (items.size() > 16) throw new IllegalStateException("分类请求超过可处理批量");
            return classificationFor(request);
        });
        List<Behavior> facts = facts(35);

        writer.enrichAndWrite(facts);

        assertEquals(Arrays.asList(16, 16, 3), batchSizes);
        for (int i = 0; i < facts.size(); i++) {
            assertEquals(Collections.singletonList("事实-" + i), facts.get(i).getTags());
        }
        verify(repository, times(1)).writeAll(facts);
    }

    @Test void laterClassificationBatchFailurePreventsAllWriting() {
        List<Integer> batchSizes = new ArrayList<>();
        when(classifier.classifyBatch(any())).thenAnswer(invocation -> {
            Map<String, Object> request = invocation.getArgument(0);
            batchSizes.add(((List<?>) request.get("items")).size());
            if (batchSizes.size() == 2) throw new IllegalStateException("第二批分类超时");
            return classificationFor(request);
        });

        assertThrows(IllegalStateException.class, () -> writer.enrichAndWrite(facts(35)));

        assertEquals(Arrays.asList(16, 16), batchSizes);
        verifyNoInteractions(repository);
    }

    @Test void configuredBatchSizeIsApplied() {
        properties.setBatchSize(3);
        List<Integer> sizes = new ArrayList<>();
        when(classifier.classifyBatch(any())).thenAnswer(invocation -> {
            Map<String, Object> request = invocation.getArgument(0);
            sizes.add(((List<?>) request.get("items")).size());
            return classificationFor(request);
        });
        List<Behavior> facts = facts(7);

        writer.enrichAndWrite(facts);

        assertEquals(Arrays.asList(3, 3, 1), sizes);
        verify(repository).writeAll(facts);
    }

    private List<Behavior> facts(int count) {
        List<Behavior> facts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            facts.add(Behavior.builder().id("b-" + i).description("事实-" + i).build());
        }
        return facts;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> classificationFor(Map<String, Object> request) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, String> input : (List<Map<String, String>>) request.get("items")) {
            Map<String, Object> item = new HashMap<>();
            item.put("tags", Collections.singletonList(input.get("text")));
            item.put("type", "定性");
            item.put("dimension", "劳务");
            results.add(item);
        }
        return Collections.singletonMap("results", results);
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
