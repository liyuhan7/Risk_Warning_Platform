package com.riskwarning.report.service.impl;

import com.riskwarning.common.dto.analysis.AnalysisTrace;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.ScoringDecision.*;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.risk.*;
import java.math.*;
import java.util.*;
import java.util.stream.Collectors;

/** 报告使用已保存的要求决定和补齐法规，不按最新法规或得分重新判断违规。 */
final class RequirementReportProjection {
    private RequirementReportProjection() { }

    static boolean isV2(IndicatorResult result) {
        return result.getCalculationDetails() != null && "REASONING_RULES_V2".equals(result.getCalculationDetails().getAnalysisMode());
    }

    static void validate(AnalysisRun run, List<IndicatorResult> results) {
        if (results.isEmpty() || run.getAnalysisSummary() == null || run.getAnalysisSummary().path("decisionCount").asInt(-1) != results.size())
            throw new IllegalStateException("要求评分报告的摘要与指标数量不一致");
        Set<String> ids = new HashSet<>();
        for (IndicatorResult result : results) {
            ScoringDecision decision = result.getCalculationDetails() == null ? null : result.getCalculationDetails().getScoringDecision();
            ScoringSourceBundle source = result.getCalculationDetails() == null ? null : result.getCalculationDetails().getScoringSource();
            if (!isV2(result) || !ids.add(result.getIndicatorEsId()) || decision == null || source == null
                    || !run.getAnalysisRunId().equals(result.getAnalysisRunId()) || !run.getAssessmentId().equals(result.getAssessmentId())
                    || !run.getProjectId().equals(result.getProjectId()) || !result.getIndicatorEsId().equals(decision.getIndicatorId())
                    || decision.getCalculatedScore() == null || !source.bundleHash().equals(decision.getSourceBundleHash())
                    || result.getCalculatedScore() == null || BigDecimal.valueOf(result.getCalculatedScore()).compareTo(BigDecimal.valueOf(decision.getCalculatedScore().doubleValue())) != 0
                    || !Objects.equals(result.getRiskTriggered(), decision.getRiskTriggered()) || result.getRiskLevel() != decision.getRiskLevel())
                throw new IllegalStateException("报告要求决定、指标结果或依据不一致");
            project(result);
        }
    }

    /** 首版六维等权：先取维度内指标均值，再对有已决指标的维度等权求均值。 */
    static double overallScore(List<IndicatorResult> results) {
        return overallScore(results, ReportAggregationProfile.firstVersion());
    }

    static Map<String, BigDecimal> dimensionScores(List<IndicatorResult> results) {
        Map<String, List<BigDecimal>> dimensions = new TreeMap<>();
        for (IndicatorResult result : results)
            dimensions.computeIfAbsent(result.getDimension(), ignored -> new ArrayList<>()).add(BigDecimal.valueOf(result.getCalculatedScore()));
        Map<String, BigDecimal> scores = new TreeMap<>();
        dimensions.forEach((dimension, values) -> scores.put(dimension, values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL128)));
        return scores;
    }

    static double overallScore(List<IndicatorResult> results, ReportAggregationProfile profile) {
        BigDecimal sum = BigDecimal.ZERO, denominator = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> dimension : dimensionScores(results).entrySet()) {
            BigDecimal weight = profile.getDimensionWeights().get(dimension.getKey());
            if (weight == null || weight.signum() <= 0) throw new IllegalStateException("报告缺少该维度的有效权重");
            sum = sum.add(dimension.getValue().multiply(weight)); denominator = denominator.add(weight);
        }
        return denominator.signum() == 0 ? 0 : sum.divide(denominator, MathContext.DECIMAL128).doubleValue();
    }

    static List<RelatedIndicator> project(IndicatorResult indicator) {
        ScoringDecision decision = indicator.getCalculationDetails().getScoringDecision();
        ScoringSourceBundle bundle = indicator.getCalculationDetails().getScoringSource();
        Map<String, ScoringSourceBundle.Source> sources = bundle.getRelations().stream().collect(Collectors.toMap(ScoringSourceBundle.Source::getRegulationId, source -> source));
        List<RelatedBehavior> behaviors = new ArrayList<>();
        for (AnalysisTrace trace : indicator.getCalculationDetails().getTraces()) {
            List<Atomic> violations = decision.getEvaluations().stream().filter(atom -> trace.getBehaviorId().equals(atom.getBehaviorId())
                    && atom.getJudgment() == Judgment.VIOLATED).collect(Collectors.toList());
            if (violations.isEmpty()) continue;
            Map<String, RelatedRegulation> regulations = new TreeMap<>();
            for (Atomic atom : violations) {
                ScoringSourceBundle.Source source = sources.get(atom.getRegulationId());
                if (source == null || atom.getEvidenceIds() == null || !trace.getEvidenceIds().containsAll(atom.getEvidenceIds()))
                    throw new IllegalStateException("违规要求缺少对应法规或原文证据");
                regulations.put(source.getRegulationId(), RelatedRegulation.builder().regulationId(source.getRegulationId())
                        .regulationName(source.getRegulation().path("name").asText()).violationType("REQUIREMENT_VIOLATION")
                        .complianceRequirement(source.getRelation().path("supportingText").asText()).build());
            }
            behaviors.add(RelatedBehavior.builder().projectId(indicator.getProjectId())
                    .description(trace.getAnalysisResult().getEnterpriseFact()).relatedRegulations(new ArrayList<>(regulations.values())).build());
        }
        return Collections.singletonList(RelatedIndicator.builder().indicatorId(indicator.getIndicatorEsId()).indicatorName(indicator.getIndicatorName())
                .score(indicator.getCalculatedScore()).maxScore(100.0).relatedBehaviors(behaviors).isPrimaryTrigger(Boolean.TRUE.equals(indicator.getRiskTriggered())).build());
    }
}
