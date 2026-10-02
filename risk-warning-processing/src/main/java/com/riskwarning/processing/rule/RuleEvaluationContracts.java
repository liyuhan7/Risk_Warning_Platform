package com.riskwarning.processing.rule;

import com.riskwarning.common.dto.analysis.RuleEvaluationResult;
import com.riskwarning.common.enums.analysis.RuleEvaluationStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;

/** 规则结果在进入审计或 IndicatorResult 前的统一契约门禁。 */
public final class RuleEvaluationContracts {
    public static void validate(RuleEvaluationResult r) {
        check(r != null && text(r.getIndicatorId()) && r.getStatus() != null && r.getInputHash() != null
                && r.getInputHash().matches("[a-f0-9]{64}"), "评估身份、状态或输入 hash 缺失");
        boolean decided = r.getStatus() == RuleEvaluationStatus.DECIDED || r.getStatus() == RuleEvaluationStatus.DECIDED_WITH_CONFLICT;
        if (decided) {
            check(r.getRuleType() != null && text(r.getRuleVersion()) && text(r.getThresholdVersion()) && r.getAggregationOperator() != null,
                    "有效决策必须有规则、阈值版本和聚合算子");
            check(finite(r.getCalculatedScore()) && finite(r.getMaxPossibleScore()) && r.getMaxPossibleScore() > 0
                    && r.getCalculatedScore() >= 0 && r.getCalculatedScore() <= r.getMaxPossibleScore(), "有效决策分数非法");
            check(r.getRiskTriggered() != null && r.getRiskLevel() != null
                    && (r.getRiskTriggered() ? r.getRiskLevel() != RiskLevelEnum.NO_RISK : r.getRiskLevel() == RiskLevelEnum.NO_RISK), "风险触发与等级不一致");
            check((r.getRiskTriggered() ? "RISK" : "NO_RISK").equals(r.getDecision()), "决定字段不一致");
            check(r.getConflict() != null && r.getConflict() == (r.getStatus() == RuleEvaluationStatus.DECIDED_WITH_CONFLICT), "冲突状态不一致");
        } else {
            check(r.getCalculatedScore() == null && r.getMaxPossibleScore() == null && r.getRiskTriggered() == null
                    && r.getRiskLevel() == null && r.getDecision() == null, "未决状态不能携带占位分数或风险");
            check(r.getErrors() != null && !r.getErrors().isEmpty() && r.getErrors().stream().allMatch(RuleEvaluationContracts::text), "未决状态必须有明确原因");
        }
    }
    private static boolean finite(Double value) { return value != null && Double.isFinite(value); }
    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
    private static void check(boolean test, String message) { if (!test) throw new RuleContractException("INVALID_EVALUATION_RESULT", message); }
    private RuleEvaluationContracts() { }
}
