package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.processing.repository.ProjectFileRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** 校验消息引用的文件确实属于本次项目和评估。 */
@Service
public class SourceDocumentScopeValidator {

    private final ProjectFileRepository projectFileRepository;

    public SourceDocumentScopeValidator(ProjectFileRepository projectFileRepository) {
        this.projectFileRepository = projectFileRepository;
    }

    public void validate(AnalysisScope scope, List<SourceDocumentRef> documents) {
        if (scope == null || documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("运行作用域和源文件不能为空");
        }
        Set<Long> seenDocumentIds = new HashSet<>();
        for (SourceDocumentRef document : documents) {
            if (document == null || document.getSourceDocumentId() == null
                    || document.getSourceDocumentId() <= 0 || document.getFilePath() == null
                    || document.getFilePath().trim().isEmpty()) {
                throw new IllegalArgumentException("源文件身份不完整");
            }
            if (!seenDocumentIds.add(document.getSourceDocumentId())) {
                throw new IllegalArgumentException("同一任务不能重复引用源文件");
            }
            ProjectFile stored = projectFileRepository.findByIdAndProjectIdAndAssessmentId(
                            document.getSourceDocumentId(), scope.getProjectId(), scope.getAssessmentId())
                    .orElseThrow(() -> new IllegalArgumentException("源文件不属于本次评估"));
            if (!document.getFilePath().equals(stored.getFilePath())) {
                throw new IllegalArgumentException("源文件路径与持久化记录不一致");
            }
        }
    }
}
