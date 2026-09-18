package com.riskwarning.processing.dto.analysis;

import lombok.*;
import java.util.List;
import java.util.Map;

/** 分析概览只读展示契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalCandidateVO {
    private String candidateType;
    private String candidateId;
    private String behaviorId;
    private Long assessmentId;
    private String analysisRunId;
    private String embeddingModel;
    private String embeddingVersion;
    /** 与审计的 filterEnabled/filterApplied 一起解释是否实际过滤。 */
    private Map<String, String> matchedFilters;
    private String name;
    private String content;
    private Double score;
    private String scoreType;
    private Integer rank;
    private String retrievalTextHash;
    private String contentHash;
    private Integer indicatorLevel;
    private String dimension;
    private String type;
    private Double maxScore;
    /** 候选类型、ID 和名称齐全；不要求指标必须有正文。 */
    private boolean snapshotAvailable;
    /** 正文独立于候选元数据判断，空白内容不可作为原文依据。 */
    private boolean contentAvailable;
}
