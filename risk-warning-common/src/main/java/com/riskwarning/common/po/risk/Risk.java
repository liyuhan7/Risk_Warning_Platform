package com.riskwarning.common.po.risk;


import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.enums.risk.RiskStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Risk {

    private String id;

    private Long projectId;

    private Long assessmentId;

    /** 产生本风险的独立分析运行。 */
    private String analysisRunId;

    private String name;

    private String dimension;

    private String description;

    private RiskLevelEnum riskLevel;

    private double probability;

    private double impact;

    private double detectability;

    private RiskStatusEnum status;

    private String processingStatus;

    private String responsibleParty;

    private String[] affectedObjects;

    private String impactScope;

    private String countermeasures;

    private List<RelatedIndicator> relatedIndicators;

    private LocalDateTime createdAt;
}
