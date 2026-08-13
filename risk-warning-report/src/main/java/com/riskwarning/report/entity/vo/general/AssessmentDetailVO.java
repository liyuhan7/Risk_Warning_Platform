package com.riskwarning.report.entity.vo.general;


import com.riskwarning.common.enums.RiskDimensionEnum;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class

AssessmentDetailVO {

    private Long projectId;

    private Long assessmentId;

    private LocalDateTime assessmentDate;

    private OverallResult overallResult;

    private RiskSummary riskSummary;

    private IndicatorOverview indicatorOverview;

    private Map<RiskDimensionEnum, DimensionRiskDistribution> dimensionRiskDistribution;


}
