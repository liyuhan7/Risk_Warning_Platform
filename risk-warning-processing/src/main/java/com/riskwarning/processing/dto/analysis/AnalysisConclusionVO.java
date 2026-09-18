package com.riskwarning.processing.dto.analysis;

import lombok.*;
import java.util.List;

/** 分析概览只读展示契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisConclusionVO {
    private String resultId;
    private String behaviorId;
    private String indicatorId;
    private String indicatorName;
    private String applicable;
    private String requirement;
    private String enterpriseFact;
    private String complianceStatus;
    private String gapType;
    private Double gapValue;
    private String gapUnit;
    private Double confidence;
    private String reasoning;
    private String modelVersion;
    private String promptVersion;
    private List<String> regulationIds;
    private List<String> regulationNames;
    private List<String> evidenceIds;
    private String analyzedAt;
    private boolean mock;
}
