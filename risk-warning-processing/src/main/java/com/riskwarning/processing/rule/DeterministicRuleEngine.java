package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.*;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** 将受控计分与显式风险阈值组合，逐事实检查语义冲突，保留未决原因。 */
@Component
@RequiredArgsConstructor
public class DeterministicRuleEngine {
    private final RuleInputAssembler assembler;
    private final BinaryRuleCalculator binary;
    private final RangeRuleCalculator range;
    private final RiskThresholdEvaluator thresholds;

    /** 来源或指纹漂移直接拒绝执行；合法输入无法形成分数时返回无占位值的未决决定。 */
    public Evaluation evaluate(AnalysisScope scope, RuleInputAssembler.PreparedInputs prepared, JsonNode source) {
        assembler.verify(scope, prepared, source);
        VerifiedRuleDefinition rule = prepared.getRule();
        RuleEvaluationResult.RuleEvaluationResultBuilder result = RuleEvaluationResult.builder()
                .indicatorId(rule.getIndicatorId()).ruleType(rule.getRuleType()).ruleVersion(rule.getRuleVersion())
                .thresholdVersion(rule.getThresholdVersion()).aggregationOperator(rule.getAggregationOperator()).inputHash(prepared.getInputHash());
        ScoreCalculation score;
        try {
            score = rule.getRuleType() == RuleTypeEnum.BINARY ? binary.calculate(scope, prepared, source) : range.calculate(scope, prepared, source);
        } catch (RuleContractException failure) {
            RuleEvaluationStatus status;
            if ("NEEDS_REVIEW".equals(failure.getCode())) status = RuleEvaluationStatus.NEEDS_REVIEW;
            else if ("INVALID_INPUT".equals(failure.getCode())) status = RuleEvaluationStatus.INVALID_INPUT;
            else throw failure;
            RuleEvaluationResult undecided = result.status(status).errors(Collections.singletonList(failure.getCode() + ": " + failure.getMessage())).build();
            RuleEvaluationContracts.validate(undecided);
            return new Evaluation(null, undecided, Collections.emptyList());
        }
        RiskThresholdEvaluator.RiskDecision risk = thresholds.evaluate(rule, score.getCalculatedScore());
        Set<String> selected = new HashSet<>(score.getContributingResultIds());
        List<String> conflicts = new ArrayList<>();
        // 聚合合法的混合事实不是冲突；仅对照每条贡献事实的规则风险与该条模型合规语义。
        for (RuleInput input : prepared.getInputs()) {
            if (!selected.contains(input.getAnalysisResultId())) continue;
            double individual = rule.getRuleType() == RuleTypeEnum.BINARY
                    ? (input.getConditionMet() ? rule.getTrueScore() : rule.getFalseScore())
                    : RangeRuleCalculator.scoreObservation(rule, input.getObservedValue());
            boolean semanticRisk = input.getComplianceStatus() == ComplianceStatus.NON_COMPLIANT;
            if (thresholds.evaluate(rule, individual).isTriggered() != semanticRisk) conflicts.add(input.getAnalysisResultId());
        }
        boolean conflict = !conflicts.isEmpty();
        RuleEvaluationResult decision = result.status(conflict ? RuleEvaluationStatus.DECIDED_WITH_CONFLICT : RuleEvaluationStatus.DECIDED)
                .calculatedScore(score.getCalculatedScore()).maxPossibleScore(score.getMaxPossibleScore())
                .riskTriggered(risk.isTriggered()).riskLevel(risk.getRiskLevel()).decision(risk.isTriggered() ? "RISK" : "NO_RISK")
                .conflict(conflict).errors(Collections.emptyList()).build();
        RuleEvaluationContracts.validate(decision);
        return new Evaluation(score, decision, Collections.unmodifiableList(conflicts));
    }

    @Value
    public static class Evaluation {
        ScoreCalculation calculation;
        RuleEvaluationResult decision;
        List<String> conflictingResultIds;
    }
}
