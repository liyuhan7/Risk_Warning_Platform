package com.riskwarning.processing.rule;

import com.riskwarning.common.enums.risk.RiskLevelEnum;
import lombok.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;

/** 只执行登记的比例区间，未命中区间不触发，不使用全局阈值。 */
@Component
public class RiskThresholdEvaluator {
    /** 比较 score 与边界乘 max，避免除法舍入改变开闭边界归属。 */
    public RiskDecision evaluate(VerifiedRuleDefinition rule, double score) {
        Double max = rule.getMaxScore();
        if (max == null || !Double.isFinite(max) || max <= 0 || !Double.isFinite(score) || score < 0 || score > max)
            throw new RuleContractException("INVALID_INPUT", "风险比例要求有限得分及正最大分");
        if (rule.getRiskIntervals() == null || rule.getThresholdVersion() == null || rule.getThresholdVersion().trim().isEmpty())
            throw new RuleContractException("INVALID_THRESHOLD", "阈值与版本必须显式登记");
        BigDecimal numerator = BigDecimal.valueOf(score), denominator = BigDecimal.valueOf(max);
        RiskLevelEnum level = RiskLevelEnum.NO_RISK;
        for (VerifiedRuleDefinition.RiskInterval interval : rule.getRiskIntervals()) {
            int lower = numerator.compareTo(BigDecimal.valueOf(interval.getLower()).multiply(denominator));
            int upper = numerator.compareTo(BigDecimal.valueOf(interval.getUpper()).multiply(denominator));
            if ((lower > 0 || lower == 0 && interval.getLowerInclusive())
                    && (upper < 0 || upper == 0 && interval.getUpperInclusive())) {
                if (level != RiskLevelEnum.NO_RISK)
                    throw new RuleContractException("INVALID_THRESHOLD", "比例命中重叠风险区间");
                level = interval.getRiskLevel();
            }
        }
        return new RiskDecision(numerator.divide(denominator, MathContext.DECIMAL128).doubleValue(), level != RiskLevelEnum.NO_RISK, level);
    }

    @Value
    public static class RiskDecision {
        double scoreRatio;
        boolean triggered;
        RiskLevelEnum riskLevel;
    }
}
