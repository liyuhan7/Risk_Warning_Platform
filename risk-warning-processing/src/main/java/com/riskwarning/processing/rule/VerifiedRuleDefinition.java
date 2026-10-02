package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.AggregationOperator;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import lombok.*;
import java.util.List;

/** 结构化规则登记项；原始表达式仅作为来源数据，禁止执行。 */
@Data
@NoArgsConstructor
public class VerifiedRuleDefinition {
    private String indicatorId;
    private Boolean enabled;
    private JsonNode sourceRule;
    private String sourceRuleHash;
    private String ruleVersion;
    private RuleTypeEnum ruleType;
    private String inputUnit;
    private AggregationOperator aggregationOperator;
    private String condition;
    private String observation;
    private Double trueScore;
    private Double falseScore;
    private List<ScoreSegment> segments;
    private Double maxScore;
    private String thresholdVersion;
    private List<RiskInterval> riskIntervals;
    private String verifiedBy;
    private String verifiedAt;
    private String basis;

    @Data
    @NoArgsConstructor
    public static class ScoreSegment {
        private Double lower;
        private Double upper;
        private Boolean lowerInclusive;
        private Boolean upperInclusive;
        private Double score;
    }
    @Data
    @NoArgsConstructor
    public static class RiskInterval {
        private Double lower;
        private Double upper;
        private Boolean lowerInclusive;
        private Boolean upperInclusive;
        private RiskLevelEnum riskLevel;
    }
}
