package com.riskwarning.common.dto.analysis;

import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.ComplianceStatus;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/** 单事实的规则输入，保留来源与时间，不从说明文字或模型得分反推。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleInput {
    private Long projectId;
    private Long assessmentId;
    private String analysisRunId;
    private String indicatorId;
    private String analysisResultId;
    private String behaviorId;
    private List<String> evidenceIds;
    private RuleTypeEnum ruleType;
    private Boolean conditionMet;
    private Double observedValue;
    private String observedUnit;
    private LocalDateTime factObservedAt;
    private LocalDateTime analyzedAt;
    private ComplianceStatus complianceStatus;
}
