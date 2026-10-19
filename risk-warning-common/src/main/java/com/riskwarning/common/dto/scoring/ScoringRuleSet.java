package com.riskwarning.common.dto.scoring;

import com.riskwarning.common.enums.risk.RiskLevelEnum;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** v2 业务规则数据契约；条件为真统一表示满足要求。 */
@Data
public class ScoringRuleSet {
    private String schemaVersion;
    private String registryVersion;
    private String purpose;
    private Map<String, IndicatorRule> rules;

    public enum Direction { REQUIRED, FORBIDDEN, OPTIONAL }
    public enum ValueType { BOOLEAN, NUMBER }
    public enum Operator { IS_TRUE, IS_FALSE, EQ, NE, LT, LE, GT, GE, BETWEEN }
    public enum Aggregation { SINGLE, ALL, EXISTS, ANY, ANY_VIOLATION, MIN, MAX, SUM, AVG, LATEST }
    public enum GradingMode { BINARY, SEGMENTED }
    public enum Severity { HIGH, MEDIUM, LOW }

    @Data
    public static class IndicatorRule {
        private Boolean enabled;
        private String ruleVersion;
        private String sourceBundleHash;
        private BigDecimal maxScore;
        private String thresholdVersion;
        private List<RiskInterval> riskIntervals;
        private List<Requirement> requirements;
        private String verifiedBy;
        private String verifiedAt;
        private String basis;
    }

    @Data
    public static class Requirement {
        private String requirementId;
        private String sourceRegulationId;
        private String sourceRelationHash;
        private Direction direction;
        private ValueType valueType;
        private Condition condition;
        private Condition exceptWhen;
        private ApplicabilityGate applicabilityGate;
        private Aggregation crossBehaviorAggregation;
        private Grading grading;
        private Severity severity;
        private BigDecimal weight;
        private RiskLevelEnum minimumRiskLevel;
        private Boolean expressible;
        private String reviewReason;
    }

    @Data
    public static class ApplicabilityGate {
        private String mode;
        private String text;
    }

    /** 组合与叶子字段互斥；每个叶子有独立编号和模型事实输入。 */
    @Data
    public static class Condition {
        private String conditionId;
        private ValueType valueType;
        private Operator operator;
        private BigDecimal operand;
        private BigDecimal upperOperand;
        private Boolean lowerInclusive;
        private Boolean upperInclusive;
        private String unit;
        private String promptHint;
        private List<Condition> allOf;
        private List<Condition> anyOf;
        private Condition notOf;
    }

    @Data
    public static class Grading {
        private GradingMode mode;
        private BigDecimal satisfied;
        private BigDecimal violated;
        private List<Segment> segments;
    }

    @Data
    public static class Segment {
        private BigDecimal lower;
        private BigDecimal upper;
        private Boolean lowerInclusive;
        private Boolean upperInclusive;
        private BigDecimal satisfaction;
        private String violationDegree;
    }

    @Data
    public static class RiskInterval {
        private BigDecimal lower;
        private BigDecimal upper;
        private Boolean lowerInclusive;
        private Boolean upperInclusive;
        private RiskLevelEnum riskLevel;
    }
}
