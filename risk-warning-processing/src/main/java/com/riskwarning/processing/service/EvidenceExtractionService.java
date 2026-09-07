package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.processing.entity.dto.DocumentSegmentRecord;
import com.riskwarning.processing.entity.dto.ProcessedDocument;
import com.riskwarning.processing.repository.EvidenceChunkRepository;
import com.riskwarning.processing.repository.ProjectFileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 将已完成的文档分段持久化为可复用的 EvidenceChunk。 */
@Service
@Slf4j
public class EvidenceExtractionService {

    private final EvidenceChunkRepository evidenceChunkRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ObjectMapper objectMapper;

    public EvidenceExtractionService(EvidenceChunkRepository evidenceChunkRepository,
                                     ProjectFileRepository projectFileRepository,
                                     ObjectMapper objectMapper) {
        this.evidenceChunkRepository = evidenceChunkRepository;
        this.projectFileRepository = projectFileRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<EvidenceChunk> extractAndPersist(AnalysisScope scope,
                                                  List<ProcessedDocument> documents) {
        if (scope == null || documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("运行作用域和文档分段不能为空");
        }
        List<EvidenceChunk> chunks = new ArrayList<>();
        for (ProcessedDocument document : documents) {
            chunks.addAll(extractDocument(scope, document));
        }
        return evidenceChunkRepository.saveAll(chunks);
    }

    /** 按受校验的源文件读取权威证据，并复核持久化完整性。 */
    @Transactional(readOnly = true)
    public List<EvidenceChunk> findBySourceDocument(AnalysisScope scope, Long sourceDocumentId) {
        if (scope == null || sourceDocumentId == null || sourceDocumentId <= 0) {
            throw new IllegalArgumentException("运行作用域和源文件身份不能为空");
        }
        projectFileRepository.findByIdAndProjectIdAndAssessmentId(sourceDocumentId,
                        scope.getProjectId(), scope.getAssessmentId())
                .orElseThrow(() -> new IllegalArgumentException("源文件不属于当前项目或评估"));
        List<EvidenceChunk> chunks = evidenceChunkRepository
                .findBySourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(sourceDocumentId);
        for (EvidenceChunk chunk : chunks) {
            chunk.verifyIntegrity();
        }
        return chunks;
    }

    private List<EvidenceChunk> extractDocument(AnalysisScope scope, ProcessedDocument document) {
        if (document == null || document.getSourceDocumentId() == null
                || document.getSourceDocumentId() <= 0 || document.getInternalFilePath() == null
                || document.getInternalFilePath().trim().isEmpty()) {
            throw new IllegalArgumentException("处理后文档身份不合法");
        }
        ProjectFile sourceFile = projectFileRepository
                .findByIdAndProjectIdAndAssessmentId(document.getSourceDocumentId(),
                        scope.getProjectId(), scope.getAssessmentId())
                .orElseThrow(() -> new IllegalArgumentException("源文件不属于当前项目或评估"));
        String sourceFileName = sourceFileName(sourceFile);
        List<EvidenceChunk> chunks = new ArrayList<>();
        Path path = Paths.get(document.getInternalFilePath());
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                DocumentSegmentRecord record = objectMapper.readValue(line, DocumentSegmentRecord.class);
                if (!document.getSourceDocumentId().equals(record.getSourceDocumentId())) {
                    throw new IllegalArgumentException("分段中的源文件身份与处理后文档不一致");
                }
                chunks.add(EvidenceChunk.create(sourceFile.getId(), scope.getProjectId(),
                        scope.getAssessmentId(), sourceFileName, record.getPageNumber(),
                        record.getSegmentIndex(), null, null, record.getText(), LocalDateTime.now()));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("读取文档分段失败", exception);
        }
        return chunks;
    }

    private String sourceFileName(ProjectFile sourceFile) {
        String originalFileName = sourceFile.getOriginalFileName();
        if (originalFileName != null && !originalFileName.trim().isEmpty()) {
            return originalFileName;
        }
        log.warn("源文件{}缺少原始文件名，使用存储路径文件名作为证据展示名", sourceFile.getId());
        String filePath = sourceFile.getFilePath();
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("源文件路径不能为空");
        }
        Path path = Paths.get(filePath);
        Path name = path.getFileName();
        if (name == null || name.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("无法解析源文件名");
        }
        return name.toString();
    }
}
