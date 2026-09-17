package com.riskwarning.common.dto.analysis;

import com.riskwarning.common.enums.risk.RiskLevelEnum;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleEvaluation {
    private String ruleVersion;
    private String decision;
    private Double calculatedScore;
    private Double maxPossibleScore;
    private Boolean riskTriggered;
    private RiskLevelEnum riskLevel;
}
