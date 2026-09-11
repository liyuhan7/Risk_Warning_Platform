package com.riskwarning.processing.service;

import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.processing.repository.EvidenceChunkRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvidenceQueryServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long ASSESSMENT_ID = 20L;
    private static final Long DOCUMENT_ID = 101L;

    @Test
    void listsEvidenceOwnedByRequestedScope() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        EvidenceChunk chunk = chunk(DOCUMENT_ID, PROJECT_ID, ASSESSMENT_ID, 2, 1, "可回溯原文");
        when(repository.findByProjectIdAndAssessmentIdOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
                PROJECT_ID, ASSESSMENT_ID)).thenReturn(Collections.singletonList(chunk));

        List<EvidenceChunk> result = new EvidenceQueryService(repository)
                .listByScope(PROJECT_ID, ASSESSMENT_ID, null);

        assertEquals(1, result.size());
        assertEquals(Integer.valueOf(2), result.get(0).getPageNumber());
        assertEquals("可回溯原文", result.get(0).getText());
    }

    @Test
    void narrowsToListedSourceDocumentWhenProvided() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        when(repository
                .findByProjectIdAndAssessmentIdAndSourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(
                        PROJECT_ID, ASSESSMENT_ID, DOCUMENT_ID))
                .thenReturn(Collections.singletonList(
                        chunk(DOCUMENT_ID, PROJECT_ID, ASSESSMENT_ID, 1, 0, "单文件证据")));

        List<EvidenceChunk> result = new EvidenceQueryService(repository)
                .listByScope(PROJECT_ID, ASSESSMENT_ID, DOCUMENT_ID);

        assertEquals(1, result.size());
        verify(repository, never())
                .findByProjectIdAndAssessmentIdOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
                        anyLong(), anyLong());
    }

    @Test
    void rejectsEvidenceBelongingToAnotherAssessment() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        when(repository.findByProjectIdAndAssessmentIdOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
                PROJECT_ID, ASSESSMENT_ID)).thenReturn(Collections.singletonList(
                        chunk(DOCUMENT_ID, PROJECT_ID, 999L, 1, 0, "越权证据")));

        assertThrows(IllegalStateException.class,
                () -> new EvidenceQueryService(repository).listByScope(PROJECT_ID, ASSESSMENT_ID, null));
    }

    @Test
    void rejectsEvidenceFailingIntegrityCheck() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        EvidenceChunk tampered = mock(EvidenceChunk.class);
        when(tampered.getProjectId()).thenReturn(PROJECT_ID);
        when(tampered.getAssessmentId()).thenReturn(ASSESSMENT_ID);
        when(tampered.getId()).thenReturn("tampered-evidence");
        doThrow(new IllegalStateException("EvidenceChunk 完整性校验失败")).when(tampered).verifyIntegrity();
        when(repository.findByProjectIdAndAssessmentIdOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
                PROJECT_ID, ASSESSMENT_ID)).thenReturn(Collections.singletonList(tampered));

        assertThrows(IllegalStateException.class,
                () -> new EvidenceQueryService(repository).listByScope(PROJECT_ID, ASSESSMENT_ID, null));
    }

    @Test
    void rejectsMissingScopeBeforeQuerying() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        EvidenceQueryService service = new EvidenceQueryService(repository);

        assertThrows(IllegalArgumentException.class, () -> service.listByScope(null, ASSESSMENT_ID, null));
        assertThrows(IllegalArgumentException.class, () -> service.listByScope(PROJECT_ID, 0L, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.listByScope(PROJECT_ID, ASSESSMENT_ID, -1L));
        verifyNoInteractions(repository);
    }

    @Test
    void returnsReferencedEvidenceAndToleratesMissingReferences() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        EvidenceChunk chunk = chunk(DOCUMENT_ID, PROJECT_ID, ASSESSMENT_ID, 3, 2, "被引用的原文");
        when(repository.findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(any()))
                .thenReturn(Collections.singletonList(chunk));

        List<EvidenceChunk> result = new EvidenceQueryService(repository)
                .listByIds(Arrays.asList("ev-found", "ev-missing"), PROJECT_ID, ASSESSMENT_ID);

        assertEquals(1, result.size());
        assertEquals("被引用的原文", result.get(0).getText());
    }

    @Test
    void rejectsReferencedEvidenceOutsideScope() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        when(repository.findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(any()))
                .thenReturn(Collections.singletonList(
                        chunk(DOCUMENT_ID, PROJECT_ID, 999L, 1, 0, "越权引用")));

        assertThrows(IllegalStateException.class, () -> new EvidenceQueryService(repository)
                .listByIds(Collections.singletonList("ev-outside"), PROJECT_ID, ASSESSMENT_ID));
    }

    @Test
    void rejectsEmptyOrOversizedIdBatch() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        EvidenceQueryService service = new EvidenceQueryService(repository);

        assertThrows(IllegalArgumentException.class,
                () -> service.listByIds(Collections.emptyList(), PROJECT_ID, ASSESSMENT_ID));
        assertThrows(IllegalArgumentException.class,
                () -> service.listByIds(Arrays.asList("  ", ""), PROJECT_ID, ASSESSMENT_ID));

        List<String> oversized = IntStream
                .range(0, EvidenceQueryService.MAX_BATCH_IDS + 1)
                .mapToObj(index -> "ev-" + index)
                .collect(Collectors.toList());
        assertThrows(IllegalArgumentException.class,
                () -> service.listByIds(oversized, PROJECT_ID, ASSESSMENT_ID));
        verifyNoInteractions(repository);
    }

    @Test
    void returnsEmptyListWhenScopeHasNoEvidence() {
        EvidenceChunkRepository repository = mock(EvidenceChunkRepository.class);
        when(repository.findByProjectIdAndAssessmentIdOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
                PROJECT_ID, ASSESSMENT_ID)).thenReturn(Collections.emptyList());

        assertTrue(new EvidenceQueryService(repository)
                .listByScope(PROJECT_ID, ASSESSMENT_ID, null).isEmpty());
    }

    private EvidenceChunk chunk(Long documentId, Long projectId, Long assessmentId,
                                Integer pageNumber, Integer segmentIndex, String text) {
        return EvidenceChunk.create(documentId, projectId, assessmentId, "企业安全管理制度.pdf",
                pageNumber, segmentIndex, null, null, text, LocalDateTime.now());
    }
}
