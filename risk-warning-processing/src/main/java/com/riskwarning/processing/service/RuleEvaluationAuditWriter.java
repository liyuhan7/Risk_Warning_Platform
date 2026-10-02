package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.RuleEvaluationStatus;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.indicator.*;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.rule.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** 规则决定与指标结果同事务提交；模型调用和规则计算不占用此事务。 */
@Service
@RequiredArgsConstructor
public class RuleEvaluationAuditWriter {
    private final AnalysisRunRepository runs;
    private final RuleEvaluationAuditRepository audits;
    private final IndicatorResultRepository results;

    /** 同输入和决定的重放不写入；输入漂移、孤立结果或跨运行数据直接拒绝。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean save(AnalysisScope scope, RuleEvaluationAudit audit, IndicatorResult result) {
        Objects.requireNonNull(scope, "运行作用域不能为空");
        AnalysisRun run = runs.lockScope(scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("规则审计的运行不存在"));
        if (run.getStatus() != AnalysisRunStatus.RUNNING) return false;
        if (run.getExecutionMode() != AnalysisExecutionMode.REASONING || run.getExpectedBehaviorIds() == null)
            throw new IllegalStateException("规则写入要求已冻结输入的 Reasoning 运行");
        validate(scope, run, audit, result);
        Optional<RuleEvaluationAudit> existing = audits.findByAnalysisRunIdAndIndicatorId(
                scope.getAnalysisRunId(), audit.getIndicatorId());
        Optional<IndicatorResult> stored = results.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(
                scope.getAssessmentId(), scope.getAnalysisRunId(), audit.getIndicatorId());
        if (existing.isPresent()) {
            RuleEvaluationAudit old = existing.get();
            if (!scope.getProjectId().equals(old.getProjectId()) || !scope.getAssessmentId().equals(old.getAssessmentId())
                    || !Objects.equals(old.getInputHash(), audit.getInputHash())
                    || old.getStatus() != audit.getStatus()
                    || !RuleCanonicalJson.hash(old.getCalculationDetails()).equals(RuleCanonicalJson.hash(audit.getCalculationDetails())))
                throw new com.riskwarning.common.exception.AnalysisInputConflictException("规则审计重放的输入或决定不一致");
            validate(scope, run, old, stored.orElse(null));
            return false;
        }
        if (stored.isPresent()) throw new IllegalStateException("存在没有规则审计的指标结果，不能覆盖");
        if (run.getAnalysisSummary() != null) throw new IllegalStateException("已准备报告的运行不能增加指标决定");
        audits.saveAndFlush(audit);
        if (result != null) results.saveAndFlush(result);
        return true;
    }

    private void validate(AnalysisScope scope, AnalysisRun run, RuleEvaluationAudit audit, IndicatorResult result) {
        require(audit != null && scope.getProjectId().equals(audit.getProjectId())
                && scope.getAssessmentId().equals(audit.getAssessmentId())
                && scope.getAnalysisRunId().equals(audit.getAnalysisRunId()), "审计作用域不一致");
        IndicatorResultDetail detail = audit.getCalculationDetails();
        require(detail != null && "1.1".equals(detail.getSchemaVersion())
                && "REASONING_RULES_V1".equals(detail.getAnalysisMode()) && detail.getRuleCalculation() != null, "缺少 Reasoning 计算详情");
        RuleCalculationDetail ruleCalculation = detail.getRuleCalculation();
        RuleEvaluationResult decision = ruleCalculation.getFinalDecision();
        RuleEvaluationContracts.validate(decision);
        require(Objects.equals(audit.getIndicatorId(), decision.getIndicatorId()) && audit.getStatus() == decision.getStatus()
                && Objects.equals(audit.getInputHash(), decision.getInputHash())
                && Objects.equals(audit.getInputHash(), ruleCalculation.getInputHash())
                && Objects.equals(audit.getRuleVersion(), decision.getRuleVersion())
                && Objects.equals(audit.getThresholdVersion(), decision.getThresholdVersion())
                && Objects.equals(audit.getAggregationOperator(), decision.getAggregationOperator() == null ? null : decision.getAggregationOperator().name())
                && Objects.equals(ruleCalculation.getRuleVersion(), decision.getRuleVersion())
                && Objects.equals(ruleCalculation.getThresholdVersion(), decision.getThresholdVersion())
                && Objects.equals(ruleCalculation.getAggregationOperator(), audit.getAggregationOperator())
                && Objects.equals(audit.getConflict(), Boolean.TRUE.equals(decision.getConflict()))
                && Objects.equals(ruleCalculation.getConflict(), audit.getConflict()), "审计与规则决定不一致");
        boolean decided = decision.getStatus() == RuleEvaluationStatus.DECIDED
                || decision.getStatus() == RuleEvaluationStatus.DECIDED_WITH_CONFLICT;
        require(decided == (result != null), "未决不能保存指标结果，有效决定必须有指标结果");
        if (!decided) return;
        require(ruleCalculation.getRuleInputs() != null && !ruleCalculation.getRuleInputs().isEmpty(), "有效决定缺少事实输入");
        for (RuleInput input : ruleCalculation.getRuleInputs()) {
            require(input != null && scope.getProjectId().equals(input.getProjectId())
                    && scope.getAssessmentId().equals(input.getAssessmentId())
                    && scope.getAnalysisRunId().equals(input.getAnalysisRunId())
                    && audit.getIndicatorId().equals(input.getIndicatorId())
                    && run.getExpectedBehaviorIds().contains(input.getBehaviorId()), "规则输入不属于冻结运行");
        }
        require(scope.getProjectId().equals(result.getProjectId()) && scope.getAssessmentId().equals(result.getAssessmentId())
                && scope.getAnalysisRunId().equals(result.getAnalysisRunId())
                && audit.getIndicatorId().equals(result.getIndicatorEsId())
                && Objects.equals(decision.getCalculatedScore(), result.getCalculatedScore())
                && Objects.equals(decision.getMaxPossibleScore(), result.getMaxPossibleScore())
                && Objects.equals(decision.getRiskTriggered(), result.getRiskTriggered())
                && decision.getRiskLevel() == result.getRiskLevel()
                && decision.getRuleType().name().equals(result.getUsedCalculationRuleType())
                && result.getRiskStatus() == IndicatorRiskStatus.EVALUATED
                && RuleCanonicalJson.hash(detail).equals(RuleCanonicalJson.hash(result.getCalculationDetails())),
                "指标结果与规则决定不一致");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
