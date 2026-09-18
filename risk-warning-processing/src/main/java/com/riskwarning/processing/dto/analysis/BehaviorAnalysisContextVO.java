package com.riskwarning.processing.dto.analysis;

import lombok.*;

import java.util.List;

/**
 * 单个事实的后续分析输入：结构化事实、证据引用、检索审计与候选。
 * readyForAnalysis 为 false 时 blockedReason 必须非空，后续分析不得绕过该门禁。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorAnalysisContextVO {
    private String behaviorId;
    private String description;
    private String subject;
    private String action;
    private String object;
    private String type;
    private String dimension;
    private Double quantitativeData;
    private String quantitativeUnit;
    private Double confidence;
    private Long sourceDocumentId;
    private List<String> evidenceIds;
    private String queryText;
    private String queryTemplateVersion;
    private String filterVersion;
    private Boolean filterEnabled;
    private Boolean filterApplied;
    private String filterExpression;
    private String retrievalStatus;
    private String analysisStatus;
    private String embeddingModel;
    /** 与 embeddingModel 一起构成检索版本；同一事实的候选不得跨版本混用。 */
    private String retrievalVersion;
    private String errorCode;
    private String errorMessage;
    private boolean snapshotAvailable;
    /** 证据引用完整且两类候选均可回读时为 true。 */
    private boolean evidenceComplete;
    private boolean readyForAnalysis;
    /** READY 时为 null；否则为 NO_RETRIEVAL_AUDIT、RETRIEVAL_FAILED、NO_CANDIDATES 等明确原因。 */
    private String blockedReason;
    private List<RetrievalCandidateVO> indicatorCandidates;
    private List<RetrievalCandidateVO> regulationCandidates;
    /** 历史结论载体，仅在旧运行产生指标结果时携带；真实 P2 检索链为 null。 */
    private Object calculationDetails;
}
