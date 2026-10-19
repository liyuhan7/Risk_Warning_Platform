package com.riskwarning.common.dto.scoring;

import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.enums.analysis.GapType;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/** 要求明细与指标汇总分别保存；未决指标不提供占位得分。 */
@Data
public class ScoringDecision {
    public enum Judgment { SATISFIED, VIOLATED, UNSATISFIED_OPTIONAL, NOT_APPLICABLE, INSUFFICIENT_EVIDENCE, NEEDS_REVIEW, INVALID_INPUT }
    private String schemaVersion;
    private String indicatorId;
    private String registryVersion;
    private String ruleVersion;
    private String thresholdVersion;
    private String sourceBundleHash;
    private String inputHash;
    private String status;
    private BigDecimal calculatedScore;
    private BigDecimal maxPossibleScore;
    private Boolean riskTriggered;
    private RiskLevelEnum riskLevel;
    private boolean conflict;
    private List<Atomic> evaluations;
    private List<RequirementSummary> requirements;

    @Data
    public static class Atomic {
        private String behaviorId;
        private String requirementId;
        private String regulationId;
        private Judgment judgment;
        private BigDecimal satisfaction;
        private BigDecimal observedValue;
        private String observedUnit;
        private Boolean conditionMet;
        private GapType gapType;
        private BigDecimal gapValue;
        private String gapUnit;
        private BigDecimal earned;
        private BigDecimal maximum;
        private String violationDegree;
        private List<String> evidenceIds;
        private String reason;
        private boolean conflict;
    }

    @Data
    public static class RequirementSummary {
        private String requirementId;
        private Judgment judgment;
        private BigDecimal satisfaction;
        private BigDecimal observedValue;
        private BigDecimal earned;
        private BigDecimal maximum;
        private List<String> contributingBehaviorIds;
    }
}
