package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.processing.entity.dto.DocumentSegmentRecord;
import com.riskwarning.processing.entity.dto.ProcessedDocument;
import com.riskwarning.processing.repository.EvidenceChunkRepository;
import com.riskwarning.processing.repository.ProjectFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class EvidenceExtractionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsStableEvidenceForScopedDocument() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path segments = tempDir.resolve("segments.jsonl");
        Files.write(segments, Collections.singletonList(mapper.writeValueAsString(
                new DocumentSegmentRecord(101L, 2, 3, "  原文\r\n片段  "))), StandardCharsets.UTF_8);
        ProjectFileRepository projectFiles = mock(ProjectFileRepository.class);
        EvidenceChunkRepository evidenceChunks = mock(EvidenceChunkRepository.class);
        ProjectFile source = ProjectFile.builder().id(101L).projectId(10L)
                .assessmentId(20L).filePath("C:\\upload\\source.pdf")
                .originalFileName("企业安全管理制度.pdf").build();
        when(projectFiles.findByIdAndProjectIdAndAssessmentId(101L, 10L, 20L))
                .thenReturn(Optional.of(source));
        when(evidenceChunks.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        EvidenceExtractionService service = new EvidenceExtractionService(evidenceChunks, projectFiles, mapper);

        java.util.List<EvidenceChunk> result = service.extractAndPersist(
                new AnalysisScope(10L, 20L, "run-1"),
                Collections.singletonList(new ProcessedDocument(101L, segments.toString())));

        assertEquals(1, result.size());
        assertEquals("企业安全管理制度.pdf", result.get(0).getSourceFileName());
        assertEquals(Integer.valueOf(2), result.get(0).getPageNumber());
        assertEquals(Integer.valueOf(3), result.get(0).getSegmentIndex());
        result.get(0).verifyIntegrity();
        verify(evidenceChunks).saveAll(anyList());
    }

    @Test
    void rejectsSegmentThatClaimsAnotherDocument() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path segments = tempDir.resolve("segments.jsonl");
        Files.write(segments, Collections.singletonList(mapper.writeValueAsString(
                new DocumentSegmentRecord(999L, 1, 0, "错误归属"))), StandardCharsets.UTF_8);
        ProjectFileRepository projectFiles = mock(ProjectFileRepository.class);
        EvidenceChunkRepository evidenceChunks = mock(EvidenceChunkRepository.class);
        when(projectFiles.findByIdAndProjectIdAndAssessmentId(101L, 10L, 20L))
                .thenReturn(Optional.of(ProjectFile.builder().id(101L).projectId(10L)
                        .assessmentId(20L).filePath("source.pdf").build()));
        EvidenceExtractionService service = new EvidenceExtractionService(evidenceChunks, projectFiles, mapper);

        assertThrows(IllegalStateException.class, () -> service.extractAndPersist(
                new AnalysisScope(10L, 20L, "run-1"),
                Collections.singletonList(new ProcessedDocument(101L, segments.toString()))));
        verifyNoInteractions(evidenceChunks);
    }

    @Test
    void readsOnlyEvidenceOwnedByScopedSourceDocument() {
        ProjectFileRepository projectFiles = mock(ProjectFileRepository.class);
        EvidenceChunkRepository evidenceChunks = mock(EvidenceChunkRepository.class);
        ProjectFile source = ProjectFile.builder().id(101L).projectId(10L)
                .assessmentId(20L).filePath("source.pdf").build();
        EvidenceChunk chunk = EvidenceChunk.create(101L, 10L, 20L, "source.pdf", 1, 0,
                null, null, "可回溯原文", java.time.LocalDateTime.now());
        when(projectFiles.findByIdAndProjectIdAndAssessmentId(101L, 10L, 20L))
                .thenReturn(Optional.of(source));
        when(evidenceChunks.findBySourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(101L))
                .thenReturn(Collections.singletonList(chunk));
        EvidenceExtractionService service = new EvidenceExtractionService(
                evidenceChunks, projectFiles, new ObjectMapper());

        assertEquals(1, service.findBySourceDocument(new AnalysisScope(10L, 20L, "run-2"), 101L).size());
        verify(evidenceChunks).findBySourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(101L);
    }

    @Test
    void fallsBackToStoredPathNameForHistoricalDocument() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path segments = tempDir.resolve("historical-segments.jsonl");
        Files.write(segments, Collections.singletonList(mapper.writeValueAsString(
                new DocumentSegmentRecord(101L, 1, 0, "历史材料"))), StandardCharsets.UTF_8);
        ProjectFileRepository projectFiles = mock(ProjectFileRepository.class);
        EvidenceChunkRepository evidenceChunks = mock(EvidenceChunkRepository.class);
        ProjectFile source = ProjectFile.builder().id(101L).projectId(10L)
                .assessmentId(20L).filePath("C:\\persist\\generated-name.pdf").build();
        when(projectFiles.findByIdAndProjectIdAndAssessmentId(101L, 10L, 20L))
                .thenReturn(Optional.of(source));
        when(evidenceChunks.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        java.util.List<EvidenceChunk> result = new EvidenceExtractionService(
                evidenceChunks, projectFiles, mapper).extractAndPersist(new AnalysisScope(10L, 20L, "run-1"),
                Collections.singletonList(new ProcessedDocument(101L, segments.toString())));

        assertEquals("generated-name.pdf", result.get(0).getSourceFileName());
    }
}
