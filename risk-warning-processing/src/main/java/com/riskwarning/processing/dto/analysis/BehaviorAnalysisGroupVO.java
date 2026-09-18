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
    private RetrievalAuditVO retrievalAudit;
    private List<AnalysisConclusionVO> conclusions;
}
