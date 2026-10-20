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
    /** FACTS_DERIVED 由程序推导，空值保留历史模型结论的语义。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String judgmentSource;
    private List<String> evidenceIds;
    private Map<String, ConditionValue> conditionValues;
    private BigDecimal confidence;
    private String reasoning;

    @Data
    public static class ConditionValue {
        /** KNOWN、NOT_STATED 或 UNCERTAIN；历史数据可为空。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String factStatus;
        private Boolean conditionMet;
        private BigDecimal observedValue;
        private String observedUnit;
        private List<String> evidenceIds;
    }
}
