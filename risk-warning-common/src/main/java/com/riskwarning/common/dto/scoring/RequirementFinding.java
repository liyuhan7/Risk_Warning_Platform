package com.riskwarning.common.dto.scoring;

import com.riskwarning.common.enums.analysis.Applicability;
import com.riskwarning.common.enums.analysis.ComplianceStatus;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 模型提供逐要求、逐原子条件的事实；分数和风险等级不得由模型提供。 */
@Data
public class RequirementFinding {
    private String indicatorId;
    private String requirementId;
    private String behaviorId;
    private Applicability applicability;
    private ComplianceStatus complianceStatus;
    private List<String> evidenceIds;
    private Map<String, ConditionValue> conditionValues;
    private BigDecimal confidence;
    private String reasoning;

    @Data
    public static class ConditionValue {
        private Boolean conditionMet;
        private BigDecimal observedValue;
        private String observedUnit;
        private List<String> evidenceIds;
    }
}
