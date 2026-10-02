package com.riskwarning.common.dto.analysis;

import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import lombok.*;
import java.util.List;

/** 含未决原因的规则评估结果；非决定状态不提供占位分数。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleEvaluationResult {
    private String indicatorId;
    private RuleTypeEnum ruleType;
    private RuleEvaluationStatus status;
    private String ruleVersion;
    private String thresholdVersion;
    private AggregationOperator aggregationOperator;
    private String inputHash;
    private String decision;
    private Double calculatedScore;
    private Double maxPossibleScore;
    private Boolean riskTriggered;
    private RiskLevelEnum riskLevel;
    private Boolean conflict;
    private List<String> errors;
}
