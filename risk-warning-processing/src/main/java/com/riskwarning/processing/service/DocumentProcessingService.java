package com.riskwarning.processing.service;

import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.processing.entity.dto.DocumentSegmentRecord;
import com.riskwarning.processing.entity.dto.ProcessedDocument;
import com.riskwarning.processing.util.ContentExtractor;
import com.riskwarning.processing.util.FileGetter;
import com.riskwarning.processing.util.FileScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;


@Slf4j
@Service
public class DocumentProcessingService {

    @Autowired
    private FileGetter fileGetter;

    @Autowired
    private FileScanner fileScanner;

    @Autowired
    private ContentExtractor contentExtractor;

    @Autowired
    private ObjectMapper objectMapper;

    
    public List<ProcessedDocument> processDocuments(AnalysisScope scope,
                                                     List<SourceDocumentRef> documents) {
        if (scope == null || documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("运行作用域和源文件不能为空");
        }
        List<ProcessedDocument> results = new ArrayList<>();
        for (SourceDocumentRef document : documents) {
            validateDocument(document);
            String internalPath = process(
                    fileGetter.getFromPath(document.getFilePath()), scope.getProjectId(),
                    document.getSourceDocumentId(), scope.getAnalysisRunId());
            results.add(new ProcessedDocument(document.getSourceDocumentId(), internalPath));
        }
        return results;
    }

    private void validateDocument(SourceDocumentRef document) {
        if (document == null || document.getSourceDocumentId() == null
                || document.getSourceDocumentId() <= 0 || document.getFilePath() == null
                || document.getFilePath().trim().isEmpty()) {
            throw new IllegalArgumentException("源文件必须包含有效的 sourceDocumentId 和路径");
        }
    }

    private String process(File documentFile, Long projectId, Long sourceDocumentId,
                           String analysisRunId) {
        String targetInternalPath = Constants.getInternalDirPath(projectId);
        UUID runPathId = UUID.nameUUIDFromBytes(
                analysisRunId.getBytes(StandardCharsets.UTF_8));
        File targetInternalFile = new File(targetInternalPath,
                projectId + "_" + sourceDocumentId + "_" + runPathId + ".jsonl");
        File parentDir = targetInternalFile.getParentFile();
        if(!parentDir.exists()){
            parentDir.mkdirs();
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                targetInternalFile.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )){
            FileGetter.FileMetadata metadata = fileGetter.getFileMetadata(documentFile);
            // 验证文件
            if (!fileGetter.validateFile(documentFile, metadata.getFileType())) {
                throw new RuntimeException("文件验证失败");
            }

            // 步骤2: 分页扫描
            List<FileScanner.PageContent> pages = fileScanner.scanByPage(documentFile);
            // 步骤3: 按页序写入，保证页码和段序号稳定
            writeSegments(writer, sourceDocumentId, metadata.getFileName(), pages);
            return targetInternalFile.getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void writeSegments(BufferedWriter writer, Long sourceDocumentId, String fileName,
                       List<FileScanner.PageContent> pages) throws Exception {
        int segmentIndex = 0;
        for (FileScanner.PageContent page : pages) {
            FileScanner.ScannedDocument pageDoc = new FileScanner.ScannedDocument();
            pageDoc.setFullText(page.getText());
            pageDoc.setPages(Collections.singletonList(page));
            pageDoc.setFileName(fileName);
            pageDoc.setTotalPages(pages.size());

            List<ContentExtractor.TextSegment> segments = contentExtractor.extract(pageDoc);
            for (ContentExtractor.TextSegment segment : segments) {
                if (segment != null && segment.getText() != null
                        && !segment.getText().isEmpty()) {
                    DocumentSegmentRecord record = new DocumentSegmentRecord(
                            sourceDocumentId, segment.getPageNumber(), segmentIndex++,
                            segment.getText());
                    writer.write(objectMapper.writeValueAsString(record));
                    writer.newLine();
                }
            }
        }
    }
}

