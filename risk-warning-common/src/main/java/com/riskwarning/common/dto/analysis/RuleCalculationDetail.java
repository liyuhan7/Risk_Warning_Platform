package com.riskwarning.common.dto.analysis;

import lombok.*;
import java.util.List;
import java.util.Map;

/** 规则计算的回放契约；结构化输入与聚合过程由受控规则引擎提供。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleCalculationDetail {
    private String inputHash;
    private String ruleVersion;
    private String thresholdVersion;
    private String aggregationOperator;
    private List<RuleInput> ruleInputs;
    private Map<String, Object> aggregationDetails;
    private Boolean conflict;
    private RuleEvaluationResult finalDecision;
}
