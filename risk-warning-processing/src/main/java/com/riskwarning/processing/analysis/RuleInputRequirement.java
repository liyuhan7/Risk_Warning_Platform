package com.riskwarning.processing.analysis;

import lombok.*;

/** 受控注册表向分析层提供的输入要求，不包含分数或风险阈值。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleInputRequirement {
    private String ruleType;
    private String inputUnit;
    private String condition;
    private String observation;
}
