package com.riskwarning.report.service.impl;

import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.po.analysis.AnalysisResult;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.risk.*;
import java.util.*;
import java.util.stream.Collectors;

/** 从持久化追踪投影事实与法规，只使用本事实明确引用的法规快照。 */
final class FrozenReportEvidence {
    private FrozenReportEvidence() { }

    static List<RelatedIndicator> project(IndicatorResult indicator) {
        List<AnalysisTrace> traces = indicator.getCalculationDetails().getTraces();
        require(traces != null && !traces.isEmpty(), "报告缺少事实追踪");
        Map<String, RuleInput> inputs = new HashMap<>();
        for (RuleInput input : indicator.getCalculationDetails().getRuleCalculation().getRuleInputs())
            require(input != null && text(input.getAnalysisResultId()) && inputs.put(input.getAnalysisResultId(), input) == null,
                    "报告规则输入缺少分析结果身份或重复");
        Set<String> seen = new HashSet<>();
        List<RelatedBehavior> behaviors = new ArrayList<>();
        List<AnalysisTrace> ordered = new ArrayList<>(traces);
        require(ordered.stream().allMatch(Objects::nonNull), "报告事实追踪为空");
        ordered.sort(Comparator.comparing(AnalysisTrace::getBehaviorId, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (AnalysisTrace trace : ordered) {
            AnalysisResult result = trace.getAnalysisResult();
            RuleInput input = result == null ? null : inputs.get(result.getId());
            require(input != null && seen.add(result.getId()) && "1.1".equals(result.getSchemaVersion())
                    && Objects.equals(indicator.getAssessmentId(), result.getAssessmentId())
                    && Objects.equals(indicator.getAnalysisRunId(), result.getAnalysisRunId())
                    && Objects.equals(indicator.getIndicatorEsId(), result.getIndicatorId())
                    && Objects.equals(trace.getBehaviorId(), result.getBehaviorId())
                    && Objects.equals(input.getBehaviorId(), result.getBehaviorId())
                    && sameIds(trace.getEvidenceIds(), result.getEvidenceIds()) && sameIds(input.getEvidenceIds(), result.getEvidenceIds())
                    && Objects.equals(input.getConditionMet(), result.getConditionMet())
                    && Objects.equals(input.getObservedValue(), result.getObservedValue())
                    && Objects.equals(input.getObservedUnit(), result.getObservedUnit())
                    && input.getComplianceStatus() == result.getComplianceStatus()
                    && text(result.getEnterpriseFact()) && text(result.getReasoning()), "报告事实与规则输入不一致");
            Map<String, RetrievalCandidateSnapshot> regulations = new HashMap<>();
            require(trace.getCandidates() != null, "报告事实缺少冻结候选");
            for (RetrievalCandidateSnapshot candidate : trace.getCandidates()) {
                if (candidate == null || candidate.getResult() == null) continue;
                RetrievalResult identity = candidate.getResult();
                if (identity.getCandidateType() != RetrievalCandidateType.REGULATION) continue;
                require(Objects.equals(result.getAnalysisRunId(), identity.getAnalysisRunId())
                        && Objects.equals(result.getAssessmentId(), identity.getAssessmentId())
                        && Objects.equals(result.getBehaviorId(), identity.getBehaviorId())
                        && text(identity.getCandidateId()) && regulations.put(identity.getCandidateId(), candidate) == null,
                        "报告法规候选跨运行或重复");
            }
            List<RelatedRegulation> related = new ArrayList<>();
            Set<String> references = new TreeSet<>();
            if (result.getRegulationIds() != null) {
                require(result.getRegulationIds().stream().allMatch(FrozenReportEvidence::text), "报告法规引用为空");
                references.addAll(result.getRegulationIds());
                require(references.size() == result.getRegulationIds().size(), "报告法规引用重复");
            }
            for (String id : references) {
                RetrievalCandidateSnapshot candidate = regulations.get(id);
                require(candidate != null && text(candidate.getContent())
                        && EvidenceChunk.sha256(candidate.getContent()).equals(candidate.getContentHash()), "报告法规引用缺失或原文 hash 不一致");
                related.add(RelatedRegulation.builder().regulationId(id).regulationName(candidate.getName())
                        .violationType(result.getGapType() == null ? null : result.getGapType().name())
                        .complianceRequirement(result.getRequirement()).build());
            }
            behaviors.add(RelatedBehavior.builder().projectId(indicator.getProjectId()).description(result.getEnterpriseFact())
                    .relatedRegulations(related).build());
        }
        require(seen.equals(inputs.keySet()), "报告事实追踪与规则输入数量不一致");
        return Collections.singletonList(RelatedIndicator.builder().indicatorId(indicator.getIndicatorEsId())
                .indicatorName(indicator.getIndicatorName()).score(indicator.getCalculatedScore()).maxScore(indicator.getMaxPossibleScore())
                .relatedBehaviors(behaviors).isPrimaryTrigger(Boolean.TRUE.equals(indicator.getRiskTriggered())).build());
    }

    static String description(IndicatorResult indicator) {
        return indicator.getCalculationDetails().getTraces().stream().sorted(Comparator.comparing(AnalysisTrace::getBehaviorId))
                .map(trace -> trace.getAnalysisResult().getEnterpriseFact() + "：" + trace.getAnalysisResult().getReasoning())
                .collect(Collectors.joining("\n"));
    }
    private static boolean sameIds(List<String> left, List<String> right) {
        return left != null && right != null && !left.isEmpty() && left.stream().allMatch(FrozenReportEvidence::text)
                && new HashSet<>(left).size() == left.size() && new HashSet<>(right).size() == right.size()
                && new HashSet<>(left).equals(new HashSet<>(right));
    }
    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
}
