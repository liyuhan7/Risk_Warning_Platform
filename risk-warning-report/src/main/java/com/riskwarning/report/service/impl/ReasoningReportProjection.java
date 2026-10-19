package com.riskwarning.report.service.impl;

import com.riskwarning.common.dto.analysis.RuleCalculationDetail;
import com.riskwarning.common.dto.analysis.RuleEvaluationResult;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.RuleEvaluationStatus;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.indicator.IndicatorResult;
import java.util.*;

/** 报告只投影已冻结的规则决定；缺失或不一致的结果不得回退为比例计算。 */
final class ReasoningReportProjection {
    private ReasoningReportProjection() { }

    static void validate(AnalysisRun run, List<IndicatorResult> results) {
        if (run.getAnalysisSummary() != null && "REASONING_RULES_V2".equals(run.getAnalysisSummary().path("analysisMode").asText())) {
            RequirementReportProjection.validate(run, results);
            return;
        }
        require(run.getStatus() == AnalysisRunStatus.RUNNING && run.getAnalysisSummary() != null
                && run.getExpectedBehaviorIds() != null, "合规报告要求已准备的运行");
        String kind = run.getAnalysisSummary().path("completionKind").asText();
        require(("FULL".equals(kind) || "PARTIAL".equals(kind)) && !results.isEmpty()
                && run.getAnalysisSummary().path("decisionCount").asInt(-1) == results.size(), "报告摘要与指标决定数量不一致");
        Set<String> ids = new HashSet<>();
        for (IndicatorResult result : results) {
            require(result != null && ids.add(result.getIndicatorEsId())
                    && Objects.equals(run.getProjectId(), result.getProjectId())
                    && Objects.equals(run.getAssessmentId(), result.getAssessmentId())
                    && Objects.equals(run.getAnalysisRunId(), result.getAnalysisRunId()), "报告指标重复或跨运行");
            require(result.getCalculationDetails() != null && "1.1".equals(result.getCalculationDetails().getSchemaVersion())
                    && "REASONING_RULES_V1".equals(result.getCalculationDetails().getAnalysisMode()), "合规报告缺失明确模式与详情版本");
            RuleCalculationDetail detail = result.getCalculationDetails().getRuleCalculation();
            RuleEvaluationResult decision = detail == null ? null : detail.getFinalDecision();
            require(decision != null && (decision.getStatus() == RuleEvaluationStatus.DECIDED
                    || decision.getStatus() == RuleEvaluationStatus.DECIDED_WITH_CONFLICT)
                    && Objects.equals(result.getIndicatorEsId(), decision.getIndicatorId())
                    && detail.getInputHash() != null && detail.getInputHash().matches("[a-f0-9]{64}")
                    && Objects.equals(detail.getInputHash(), decision.getInputHash())
                    && Objects.equals(detail.getRuleVersion(), decision.getRuleVersion())
                    && Objects.equals(detail.getThresholdVersion(), decision.getThresholdVersion())
                    && Objects.equals(detail.getConflict(), decision.getConflict()), "报告规则决定与追踪不一致");
            require(finite(result.getCalculatedScore()) && finite(result.getMaxPossibleScore())
                    && result.getMaxPossibleScore() > 0 && result.getCalculatedScore() >= 0
                    && result.getCalculatedScore() <= result.getMaxPossibleScore()
                    && Objects.equals(result.getCalculatedScore(), decision.getCalculatedScore())
                    && Objects.equals(result.getMaxPossibleScore(), decision.getMaxPossibleScore())
                    && result.getRiskTriggered() != null && Objects.equals(result.getRiskTriggered(), decision.getRiskTriggered())
                    && result.getRiskLevel() != null && result.getRiskLevel() == decision.getRiskLevel()
                    && (result.getRiskTriggered() != (result.getRiskLevel() == RiskLevelEnum.NO_RISK))
                    && result.getRiskStatus() == IndicatorRiskStatus.EVALUATED, "报告指标与最终规则分数或风险不一致");
            require(detail.getRuleInputs() != null && !detail.getRuleInputs().isEmpty(), "报告缺少规则输入追踪");
            detail.getRuleInputs().forEach(input -> require(input != null
                    && Objects.equals(run.getProjectId(), input.getProjectId())
                    && Objects.equals(run.getAssessmentId(), input.getAssessmentId())
                    && Objects.equals(run.getAnalysisRunId(), input.getAnalysisRunId())
                    && Objects.equals(result.getIndicatorEsId(), input.getIndicatorId())
                    && run.getExpectedBehaviorIds().contains(input.getBehaviorId()), "报告规则输入跨运行"));
            FrozenReportEvidence.project(result);
        }
    }

    private static boolean finite(Double value) { return value != null && Double.isFinite(value); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
}
