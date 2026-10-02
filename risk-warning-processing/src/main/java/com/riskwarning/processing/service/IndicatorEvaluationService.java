package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.indicator.*;
import com.riskwarning.processing.rule.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

/** 分组当前 Run 的语义结果并写入规则决定，不接受上游传入的模型分数。 */
@Service
@RequiredArgsConstructor
public class IndicatorEvaluationService {
    private final RuleInputAssembler assembler;
    private final DeterministicRuleEngine engine;
    private final RuleEvaluationAuditWriter writer;

    public void evaluate(AnalysisScope scope, List<Behavior> behaviors, List<AnalysisResult> results,
                         List<RetrievalAudit> retrievals, Map<String, JsonNode> sources) {
        if (scope == null || behaviors == null || results == null || retrievals == null || sources == null)
            throw new IllegalArgumentException("规则评估的运行输入不能为空");
        Map<String, LocalDateTime> times = new HashMap<>();
        Set<String> behaviorIds = new HashSet<>();
        for (Behavior behavior : behaviors) {
            if (behavior == null || !scope.getProjectId().equals(behavior.getProjectId())
                    || !scope.getAssessmentId().equals(behavior.getAssessmentId())
                    || !scope.getAnalysisRunId().equals(behavior.getAnalysisRunId()) || !text(behavior.getId())
                    || !behaviorIds.add(behavior.getId())) throw new IllegalArgumentException("事实作用域或身份不一致");
            // behaviorDate 来自证据抽取的观察日期；缺失时不使用入库或分析时间代替。
            if (behavior.getBehaviorDate() != null) times.put(behavior.getId(), behavior.getBehaviorDate());
        }
        Map<String, RetrievalAudit> audits = new HashMap<>();
        for (RetrievalAudit audit : retrievals) {
            if (audit == null || !scope.getProjectId().equals(audit.getProjectId())
                    || !scope.getAssessmentId().equals(audit.getAssessmentId())
                    || !scope.getAnalysisRunId().equals(audit.getAnalysisRunId())
                    || !behaviorIds.contains(audit.getBehaviorId()) || audits.put(audit.getBehaviorId(), audit) != null)
                throw new IllegalArgumentException("检索审计作用域或身份不一致");
        }
        Map<String, List<AnalysisResult>> groups = new TreeMap<>();
        Set<String> resultIds = new HashSet<>();
        for (AnalysisResult result : results) {
            if (result == null || !scope.getAssessmentId().equals(result.getAssessmentId())
                    || !scope.getAnalysisRunId().equals(result.getAnalysisRunId()) || !"1.1".equals(result.getSchemaVersion())
                    || !behaviorIds.contains(result.getBehaviorId()) || !text(result.getId()) || !resultIds.add(result.getId()))
                throw new IllegalArgumentException("分析结果作用域、版本或身份不一致");
            if (text(result.getIndicatorId())) groups.computeIfAbsent(result.getIndicatorId(), key -> new ArrayList<>()).add(result);
        }
        for (Map.Entry<String, List<AnalysisResult>> entry : groups.entrySet()) {
            String id = entry.getKey(); List<AnalysisResult> group = entry.getValue();
            group.sort(Comparator.comparing(AnalysisResult::getBehaviorId).thenComparing(AnalysisResult::getId));
            RetrievalCandidateSnapshot candidate = candidate(id, group, audits);
            RuleInputAssembler.PreparedInputs prepared = null;
            DeterministicRuleEngine.Evaluation evaluation = null;
            RuleEvaluationResult decision;
            try {
                prepared = assembler.prepare(scope, id, group, sources.get(id), times);
                evaluation = engine.evaluate(scope, prepared, sources.get(id));
                decision = evaluation.getDecision();
            } catch (RuleContractException failure) {
                RuleEvaluationStatus status;
                switch (failure.getCode()) {
                    case "UNSUPPORTED_RULE": status = RuleEvaluationStatus.NEEDS_REVIEW; break;
                    case "INVALID_INPUT": status = RuleEvaluationStatus.INVALID_INPUT; break;
                    case "NEEDS_REVIEW": status = RuleEvaluationStatus.NEEDS_REVIEW; break;
                    default: throw failure;
                }
                Map<String, Object> rejected = new TreeMap<>(); rejected.put("scope", scope); rejected.put("indicatorId", id);
                rejected.put("results", group); rejected.put("source", sources.get(id)); rejected.put("factTimes", times);
                decision = RuleEvaluationResult.builder().indicatorId(id).status(status).inputHash(RuleCanonicalJson.hash(rejected))
                        .errors(Collections.singletonList(failure.getCode() + ": " + failure.getMessage())).build();
            }
            RuleEvaluationContracts.validate(decision);
            boolean decided = decision.getStatus() == RuleEvaluationStatus.DECIDED || decision.getStatus() == RuleEvaluationStatus.DECIDED_WITH_CONFLICT;
            Map<String, Object> aggregation = new TreeMap<>();
            if (evaluation != null && evaluation.getCalculation() != null) {
                aggregation.put("conditionMet", evaluation.getCalculation().getConditionMet());
                aggregation.put("observedValue", evaluation.getCalculation().getObservedValue());
                aggregation.put("contributingResultIds", evaluation.getCalculation().getContributingResultIds());
                aggregation.put("conflictingResultIds", evaluation.getConflictingResultIds());
            }
            String operator = decision.getAggregationOperator() == null ? null : decision.getAggregationOperator().name();
            RuleCalculationDetail ruleCalculation = RuleCalculationDetail.builder().inputHash(decision.getInputHash()).ruleVersion(decision.getRuleVersion())
                    .thresholdVersion(decision.getThresholdVersion()).aggregationOperator(operator).conflict(Boolean.TRUE.equals(decision.getConflict()))
                    .ruleInputs(prepared == null ? Collections.emptyList() : prepared.getInputs()).aggregationDetails(aggregation).finalDecision(decision).build();
            List<AnalysisTrace> traces = new ArrayList<>();
            for (AnalysisResult result : group) {
                RetrievalAudit audit = audits.get(result.getBehaviorId());
                traces.add(AnalysisTrace.builder().behaviorId(result.getBehaviorId()).evidenceIds(result.getEvidenceIds()).analysisResult(result)
                        .queryText(audit.getQueryText()).queryTemplateVersion(audit.getQueryTemplateVersion()).filterVersion(audit.getFilterVersion())
                        .filterEnabled(audit.getFilterEnabled()).candidates(audit.getCandidates()).build());
            }
            IndicatorResultDetail detail = IndicatorResultDetail.builder().schemaVersion("1.1").analysisMode("REASONING_RULES_V1").ruleCalculation(ruleCalculation).traces(traces).build();
            LocalDateTime now = LocalDateTime.now();
            RuleEvaluationAudit audit = RuleEvaluationAudit.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                    .analysisRunId(scope.getAnalysisRunId()).indicatorId(id).inputHash(decision.getInputHash()).ruleVersion(decision.getRuleVersion())
                    .thresholdVersion(decision.getThresholdVersion()).aggregationOperator(operator).status(decision.getStatus())
                    .conflict(Boolean.TRUE.equals(decision.getConflict())).calculationDetails(detail).createdAt(now).updatedAt(now)
                    .errorCode(decided ? null : decision.getStatus().name()).errorMessage(decided ? null : String.join("; ", decision.getErrors())).build();
            IndicatorResult result = !decided ? null : IndicatorResult.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                    .analysisRunId(scope.getAnalysisRunId()).indicatorEsId(id).indicatorName(candidate.getName()).indicatorLevel(candidate.getIndicatorLevel())
                    .dimension(candidate.getDimension()).type(candidate.getType()).calculatedScore(decision.getCalculatedScore()).maxPossibleScore(decision.getMaxPossibleScore())
                    .usedCalculationRuleType(decision.getRuleType().name()).calculationDetails(detail).riskTriggered(decision.getRiskTriggered())
                    .riskLevel(decision.getRiskLevel()).riskStatus(IndicatorRiskStatus.EVALUATED).calculatedAt(now).createdAt(now).build();
            writer.save(scope, audit, result);
        }
    }

    private RetrievalCandidateSnapshot candidate(String id, List<AnalysisResult> group, Map<String, RetrievalAudit> audits) {
        RetrievalCandidateSnapshot selected = null;
        for (AnalysisResult result : group) {
            RetrievalAudit audit = audits.get(result.getBehaviorId());
            if (audit == null || audit.getRetrievalStatus() != RetrievalAuditStatus.SUCCESS || audit.getCandidates() == null)
                throw new IllegalArgumentException("指标结论缺少成功检索审计");
            List<RetrievalCandidateSnapshot> matches = new ArrayList<>();
            for (RetrievalCandidateSnapshot candidate : audit.getCandidates()) {
                if (candidate != null && candidate.getResult() != null && candidate.getResult().getCandidateType() == RetrievalCandidateType.INDICATOR
                        && id.equals(candidate.getResult().getCandidateId())) matches.add(candidate);
            }
            if (matches.size() != 1) throw new IllegalArgumentException("选中指标不属于当前事实候选或候选重复");
            RetrievalCandidateSnapshot current = matches.get(0);
            if (!text(current.getName()) || current.getIndicatorLevel() == null)
                throw new IllegalArgumentException("指标快照缺少名称或层级");
            if (selected != null && (!Objects.equals(selected.getName(), current.getName())
                    || !Objects.equals(selected.getIndicatorLevel(), current.getIndicatorLevel())
                    || !Objects.equals(selected.getDimension(), current.getDimension()) || !Objects.equals(selected.getType(), current.getType())))
                throw new IllegalArgumentException("同运行指标元数据发生漂移");
            selected = current;
        }
        return selected;
    }

    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
}
