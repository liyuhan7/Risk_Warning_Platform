package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.RuleInput;
import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.AggregationOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 观测值按登记算子聚合后匹配结构化分段，不执行原始表达式。 */
@Component
@RequiredArgsConstructor
public class RangeRuleCalculator {
    private final RuleInputAssembler assembler;

    /** 逐事实和聚合值均须处于登记定义域，避免平均值掩盖域外事实。 */
    public ScoreCalculation calculate(AnalysisScope scope, RuleInputAssembler.PreparedInputs prepared, JsonNode currentSource) {
        assembler.verify(scope, prepared, currentSource);
        VerifiedRuleDefinition rule = prepared.getRule();
        if (rule.getRuleType() != RuleTypeEnum.RANGE)
            throw new RuleContractException("UNSUPPORTED_RULE", "Range 计算器仅处理结构化 Range");
        List<RuleInput> selected = new ArrayList<>(prepared.getInputs());
        for (RuleInput input : selected) match(rule, value(input));
        BigDecimal observation;
        AggregationOperator op = rule.getAggregationOperator();
        if (op == AggregationOperator.SINGLE) {
            if (selected.size() != 1)
                throw new RuleContractException("NEEDS_REVIEW", "单事实规则不允许多事实计分");
            observation = value(selected.get(0));
        } else if (op == AggregationOperator.LATEST) {
            if (selected.stream().anyMatch(input -> input.getFactObservedAt() == null))
                throw new RuleContractException("NEEDS_REVIEW", "LATEST 缺失事实观察时间");
            LocalDateTime latest = selected.stream().map(RuleInput::getFactObservedAt).max(LocalDateTime::compareTo).get();
            selected = selected.stream().filter(input -> latest.equals(input.getFactObservedAt())).collect(Collectors.toList());
            observation = value(selected.get(0));
            final BigDecimal expected = observation;
            if (selected.stream().anyMatch(input -> value(input).compareTo(expected) != 0))
                throw new RuleContractException("NEEDS_REVIEW", "最新事实时间并列且观测值冲突");
        } else if (op == AggregationOperator.MIN) {
            observation = selected.stream().map(RangeRuleCalculator::value).min(BigDecimal::compareTo).get();
        } else if (op == AggregationOperator.MAX) {
            observation = selected.stream().map(RangeRuleCalculator::value).max(BigDecimal::compareTo).get();
        } else if (op == AggregationOperator.SUM || op == AggregationOperator.AVG) {
            // 避免有限观测值求和时 double 中间溢出，AVG 不经 SUM 的 double 转换。
            observation = selected.stream().map(RangeRuleCalculator::value).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (op == AggregationOperator.AVG)
                observation = observation.divide(BigDecimal.valueOf(selected.size()), MathContext.DECIMAL128);
        } else {
            throw new RuleContractException("UNSUPPORTED_RULE", "Range 聚合算子未受支持");
        }
        double actual = observation.doubleValue();
        if (!Double.isFinite(actual))
            throw new RuleContractException("INVALID_INPUT", "聚合观测值超出有限数值范围");
        VerifiedRuleDefinition.ScoreSegment matched = match(rule, observation);
        return new ScoreCalculation(null, actual, matched.getScore(), rule.getMaxScore(),
                selected.stream().map(RuleInput::getAnalysisResultId).collect(Collectors.toList()), prepared.getInputHash());
    }

    private static VerifiedRuleDefinition.ScoreSegment match(VerifiedRuleDefinition rule, BigDecimal observation) {
        VerifiedRuleDefinition.ScoreSegment matched = null;
        for (VerifiedRuleDefinition.ScoreSegment segment : rule.getSegments()) {
            int lower = observation.compareTo(BigDecimal.valueOf(segment.getLower()));
            int upper = observation.compareTo(BigDecimal.valueOf(segment.getUpper()));
            if ((lower > 0 || lower == 0 && segment.getLowerInclusive())
                    && (upper < 0 || upper == 0 && segment.getUpperInclusive())) {
                if (matched != null)
                    throw new RuleContractException("INVALID_INPUT", "观测值命中重叠分段");
                matched = segment;
            }
        }
        if (matched == null)
            throw new RuleContractException("INVALID_INPUT", "观测值不在登记区间内");
        return matched;
    }

    private static BigDecimal value(RuleInput input) { return BigDecimal.valueOf(input.getObservedValue()); }

    /** 已核验的单事实观测用于与该事实的模型语义对照，不参与改变聚合结果。 */
    static double scoreObservation(VerifiedRuleDefinition rule, double observation) {
        return match(rule, BigDecimal.valueOf(observation)).getScore();
    }
}
