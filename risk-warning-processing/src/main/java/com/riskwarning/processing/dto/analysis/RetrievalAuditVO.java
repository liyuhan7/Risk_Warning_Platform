package com.riskwarning.processing.dto.analysis;

import lombok.*;
import java.util.List;

/** 分析概览只读展示契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalAuditVO {
    private String queryText;
    private String queryTemplateVersion;
    private String filterVersion;
    private Boolean filterEnabled;
    private Boolean filterApplied;
    private String filterExpression;
    private String embeddingModel;
    private String embeddingVersion;
    private String retrievalStatus;
    private String analysisStatus;
    private String errorCode;
    private String errorType;
    private String errorMessage;
    private int candidateTotal;
    private boolean candidateTruncated;
    private boolean snapshotAvailable;
    private List<RetrievalCandidateVO> candidates;
}
