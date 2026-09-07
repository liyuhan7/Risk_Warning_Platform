package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.dto.fact.FactExtractionCallMetadata;
import com.riskwarning.common.dto.fact.FactExtractionResult;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.processing.fact.FactExtractionException;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FactExtractionPipelineTest {

    @Test
    void writesOnlyAfterEveryDocumentWasExtractedSuccessfully() {
        EvidenceExtractionService evidenceService = mock(EvidenceExtractionService.class);
        FactExtractionService extractionService = mock(FactExtractionService.class);
        StructuredBehaviorWriter writer = mock(StructuredBehaviorWriter.class);
        BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
        EvidenceChunk firstEvidence = chunk(101L, 10L, 20L, "证据一");
        EvidenceChunk secondEvidence = chunk(102L, 10L, 20L, "证据二");
        Behavior first = Behavior.builder().id("b1").build();
        Behavior second = Behavior.builder().id("b2").build();
        when(evidenceService.findBySourceDocument(scope, 101L))
                .thenReturn(Collections.singletonList(firstEvidence));
        when(evidenceService.findBySourceDocument(scope, 102L))
                .thenReturn(Collections.singletonList(secondEvidence));
        when(extractionService.extract(scope, Collections.singletonList(firstEvidence)))
                .thenReturn(success(first));
        when(extractionService.extract(scope, Collections.singletonList(secondEvidence)))
                .thenReturn(success(second));
        FactExtractionPipeline pipeline = new FactExtractionPipeline(
                evidenceService, extractionService, writer, repository);

        List<Behavior> result = pipeline.process(scope, Arrays.asList(
                new SourceDocumentRef(101L, "first.pdf"),
                new SourceDocumentRef(102L, "second.pdf")));

        assertEquals(Arrays.asList(first, second), result);
        verify(writer).enrichAndWrite(Arrays.asList(first, second));
        // 每个文档抽取前必须先清理该 run 下该文档的旧产物（p1-06 D5）
        verify(repository).deleteByAnalysisRunIdAndSourceDocumentId("run-1", 101L);
        verify(repository).deleteByAnalysisRunIdAndSourceDocumentId("run-1", 102L);
    }

    @Test
    void cleansPreviousArtifactsBeforeExtractingEachDocument() {
        EvidenceExtractionService evidenceService = mock(EvidenceExtractionService.class);
        FactExtractionService extractionService = mock(FactExtractionService.class);
        StructuredBehaviorWriter writer = mock(StructuredBehaviorWriter.class);
        BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
        EvidenceChunk evidence = chunk(101L, 10L, 20L, "证据");
        when(evidenceService.findBySourceDocument(scope, 101L))
                .thenReturn(Collections.singletonList(evidence));
        when(extractionService.extract(scope, Collections.singletonList(evidence)))
                .thenReturn(success(Behavior.builder().id("b1").build()));
        FactExtractionPipeline pipeline = new FactExtractionPipeline(
                evidenceService, extractionService, writer, repository);

        pipeline.process(scope, Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));

        InOrder order = inOrder(repository, evidenceService);
        order.verify(repository).deleteByAnalysisRunIdAndSourceDocumentId("run-1", 101L);
        order.verify(evidenceService).findBySourceDocument(scope, 101L);
    }

    @Test
    void doesNotWriteWhenAnyDocumentExtractionFails() {
        EvidenceExtractionService evidenceService = mock(EvidenceExtractionService.class);
        FactExtractionService extractionService = mock(FactExtractionService.class);
        StructuredBehaviorWriter writer = mock(StructuredBehaviorWriter.class);
        BehaviorDocumentRepository repository = mock(BehaviorDocumentRepository.class);
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
        EvidenceChunk evidence = chunk(101L, 10L, 20L, "证据");
        when(evidenceService.findBySourceDocument(scope, 101L))
                .thenReturn(Collections.singletonList(evidence));
        when(extractionService.extract(scope, Collections.singletonList(evidence)))
                .thenThrow(new FactExtractionException("校验失败",
                        new FactExtractionResult(Collections.emptyList(), Collections.emptyList(),
                                new FactExtractionCallMetadata("stub", "v1", 1, 2, 1))));
        FactExtractionPipeline pipeline = new FactExtractionPipeline(
                evidenceService, extractionService, writer, repository);

        assertThrows(FactExtractionException.class, () -> pipeline.process(scope,
                Collections.singletonList(new SourceDocumentRef(101L, "source.pdf"))));
        // 抽取失败禁止写入，但该文档的旧产物清理已执行（下次重跑以干净状态开始）
        verify(repository).deleteByAnalysisRunIdAndSourceDocumentId("run-1", 101L);
        verifyNoInteractions(writer);
    }

    private FactExtractionResult success(Behavior behavior) {
        return new FactExtractionResult(Collections.singletonList(behavior),
                Collections.emptyList(),
                new FactExtractionCallMetadata("stub", "fact-extract-v1.0", 1, 1, 1));
    }

    private EvidenceChunk chunk(Long sourceDocumentId, Long projectId,
                                Long assessmentId, String text) {
        return EvidenceChunk.create(sourceDocumentId, projectId, assessmentId,
                "source.pdf", 1, 0, null, null, text, LocalDateTime.now());
    }
}
