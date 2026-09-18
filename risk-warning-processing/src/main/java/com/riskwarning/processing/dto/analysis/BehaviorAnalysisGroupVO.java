package com.riskwarning.processing.dto.analysis;

import lombok.*;
import java.util.List;

/** 分析概览只读展示契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorAnalysisGroupVO {
    private String behaviorId;
    private String behaviorDescription;
    private String behaviorType;
    private String behaviorDimension;
    private String subject;
    private String action;
    private String object;
    private Double quantitativeData;
    private String quantitativeUnit;
    private Double confidence;
    private Long sourceDocumentId;
    private List<String> evidenceIds;
    private RetrievalAuditVO retrievalAudit;
    private List<AnalysisConclusionVO> conclusions;
}
