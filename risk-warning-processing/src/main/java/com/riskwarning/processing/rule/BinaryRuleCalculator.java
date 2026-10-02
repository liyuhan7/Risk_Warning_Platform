package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.RuleInput;
import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.AggregationOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 按已核验算子聚合条件值，再选择登记分数，不解析合规说明文字。 */
@Component
@RequiredArgsConstructor
public class BinaryRuleCalculator {
    private final RuleInputAssembler assembler;

    /** 来源与输入指纹在每次执行前复核；无法消解的并列事实不产生分数。 */
    public ScoreCalculation calculate(AnalysisScope scope, RuleInputAssembler.PreparedInputs prepared, JsonNode currentSource) {
        assembler.verify(scope, prepared, currentSource);
        VerifiedRuleDefinition rule = prepared.getRule();
        if (rule.getRuleType() != RuleTypeEnum.BINARY)
            throw new RuleContractException("UNSUPPORTED_RULE", "Binary 计算器仅处理 Binary 规则");
        List<RuleInput> selected = new ArrayList<>(prepared.getInputs());
        boolean condition;
        if (rule.getAggregationOperator() == AggregationOperator.SINGLE) {
            if (selected.size() != 1)
                throw new RuleContractException("NEEDS_REVIEW", "单事实规则不允许多事实计分");
            condition = selected.get(0).getConditionMet();
        } else if (rule.getAggregationOperator() == AggregationOperator.ALL) {
            condition = selected.stream().allMatch(RuleInput::getConditionMet);
        } else if (rule.getAggregationOperator() == AggregationOperator.ANY) {
            condition = selected.stream().anyMatch(RuleInput::getConditionMet);
        } else if (rule.getAggregationOperator() == AggregationOperator.LATEST) {
            if (selected.stream().anyMatch(input -> input.getFactObservedAt() == null))
                throw new RuleContractException("NEEDS_REVIEW", "LATEST 缺失事实观察时间");
            LocalDateTime latest = selected.stream().map(RuleInput::getFactObservedAt).max(LocalDateTime::compareTo).get();
            selected = selected.stream().filter(input -> latest.equals(input.getFactObservedAt())).collect(Collectors.toList());
            condition = selected.get(0).getConditionMet();
            final boolean expected = condition;
            if (selected.stream().anyMatch(input -> input.getConditionMet() != expected))
                throw new RuleContractException("NEEDS_REVIEW", "最新事实时间并列且条件值冲突");
        } else {
            throw new RuleContractException("UNSUPPORTED_RULE", "Binary 聚合算子未受支持");
        }
        return new ScoreCalculation(condition, null, condition ? rule.getTrueScore() : rule.getFalseScore(),
                rule.getMaxScore(), selected.stream().map(RuleInput::getAnalysisResultId).collect(Collectors.toList()),
                prepared.getInputHash());
    }
}
