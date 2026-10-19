package com.riskwarning.processing.service.scoring;

import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.ScoringDecision.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.indicator.*;
import com.riskwarning.processing.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

/** 在短事务内保存评分依据、模型事实和逐要求结果，模型调用不占用事务。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class ScoringPersistenceService {
    private final AnalysisRunRepository runs;
    private final ScoringRunSnapshotRepository snapshots;
    private final AnalysisResultRepository analysis;
    private final RequirementEvaluationRepository evaluations;
    private final RuleEvaluationAuditRepository audits;
    private final IndicatorResultRepository indicators;
    private final RetrievalAuditRepository retrievals;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScoringRunSnapshot freeze(AnalysisScope scope, ScoringRunSnapshot proposed) {
        writable(scope);
        Optional<ScoringRunSnapshot> existing = snapshots.findById(scope.getAnalysisRunId());
        if (existing.isPresent()) {
            if (!existing.get().getSnapshotHash().equals(proposed.getSnapshotHash())) throw new IllegalStateException("本次评分依据或规则已变化");
            return existing.get();
        }
        return snapshots.saveAndFlush(proposed);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analysis(AnalysisScope scope, AnalysisResult result) {
        writable(scope);
        Optional<AnalysisResult> old = analysis.findByAnalysisRunIdAndBehaviorIdAndIndicatorId(scope.getAnalysisRunId(), result.getBehaviorId(), result.getIndicatorId());
        if (old.isPresent()) return;
        analysis.saveAndFlush(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void behaviorComplete(AnalysisScope scope, String behaviorId, AnalysisAuditStatus status, String reason) {
        writable(scope);
        RetrievalAudit row = retrievals.findByAnalysisRunIdAndBehaviorId(scope.getAnalysisRunId(), behaviorId)
                .orElseThrow(() -> new IllegalStateException("行为检索记录缺失"));
        if (!scope.getAssessmentId().equals(row.getAssessmentId()) || !scope.getProjectId().equals(row.getProjectId()))
            throw new IllegalStateException("行为检索记录不属于本次评估");
        row.setAnalysisStatus(status); row.setErrorMessage(reason); row.setUpdatedAt(LocalDateTime.now());
        retrievals.saveAndFlush(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decision(AnalysisScope scope, ScoringSourceBundle source, IndicatorRule rule,
                         ScoringDecision decision, List<AnalysisTrace> traces) {
        writable(scope);
        Optional<RuleEvaluationAudit> old = audits.findByAnalysisRunIdAndIndicatorId(scope.getAnalysisRunId(), decision.getIndicatorId());
        if (old.isPresent()) {
            if (!decision.getInputHash().equals(old.get().getInputHash())) throw new IllegalStateException("重复评分输入变化");
            return;
        }
        Map<String, Requirement> requirements = new HashMap<>();
        rule.getRequirements().forEach(r -> requirements.put(r.getRequirementId(), r));
        LocalDateTime now = LocalDateTime.now();
        for (Atomic atom : decision.getEvaluations()) {
            RequirementEvaluation row = new RequirementEvaluation();
            row.setId(ScoringJson.hash(Arrays.asList(scope.getAnalysisRunId(), decision.getIndicatorId(), atom.getRequirementId(), atom.getBehaviorId())));
            row.setProjectId(scope.getProjectId()); row.setAssessmentId(scope.getAssessmentId()); row.setAnalysisRunId(scope.getAnalysisRunId());
            row.setIndicatorId(decision.getIndicatorId()); row.setRequirementId(atom.getRequirementId()); row.setBehaviorId(atom.getBehaviorId());
            row.setRegulationId(requirements.get(atom.getRequirementId()).getSourceRegulationId());
            row.setJudgment(atom.getJudgment()); row.setSatisfaction(atom.getSatisfaction()); row.setEarned(atom.getEarned()); row.setMaximum(atom.getMaximum());
            row.setObservedValue(atom.getObservedValue()); row.setViolationDegree(atom.getViolationDegree()); row.setInputHash(decision.getInputHash());
            row.setObservedUnit(atom.getObservedUnit()); row.setConditionMet(atom.getConditionMet());
            row.setGapType(atom.getGapType()); row.setGapValue(atom.getGapValue()); row.setGapUnit(atom.getGapUnit());
            row.setSourceBundleHash(decision.getSourceBundleHash()); row.setRegistryVersion(decision.getRegistryVersion()); row.setRuleVersion(decision.getRuleVersion());
            row.setEvidenceIds(atom.getEvidenceIds()); row.setConflict(atom.isConflict()); row.setReason(atom.getReason()); row.setCreatedAt(now);
            evaluations.save(row);
        }
        boolean decided = decision.getCalculatedScore() != null;
        RuleEvaluationStatus status = decided ? decision.isConflict() ? RuleEvaluationStatus.DECIDED_WITH_CONFLICT : RuleEvaluationStatus.DECIDED : RuleEvaluationStatus.NEEDS_REVIEW;
        IndicatorResultDetail details = IndicatorResultDetail.builder().schemaVersion("1.2").analysisMode("REASONING_RULES_V2")
                .scoringDecision(decision).scoringSource(source).traces(traces).build();
        audits.saveAndFlush(RuleEvaluationAudit.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId()).indicatorId(decision.getIndicatorId()).inputHash(decision.getInputHash())
                .ruleVersion(decision.getRuleVersion()).thresholdVersion(decision.getThresholdVersion()).aggregationOperator("REQUIREMENTS")
                .status(status).conflict(decision.isConflict()).calculationDetails(details).errorCode(decided ? null : decision.getStatus())
                .errorMessage(decided ? null : "要求未全部形成有效判断，指标不出最终分").createdAt(now).updatedAt(now).build());
        if (decided) indicators.saveAndFlush(IndicatorResult.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId()).indicatorEsId(decision.getIndicatorId()).indicatorName(source.getIndicator().path("name").asText())
                .indicatorLevel(source.getIndicator().path("indicatorLevel").asInt(3)).dimension(source.getIndicator().path("dimension").asText())
                .type(source.getIndicator().path("type").asText()).calculatedScore(decision.getCalculatedScore().doubleValue())
                .maxPossibleScore(100.0).usedCalculationRuleType("REQUIREMENTS").calculationDetails(details)
                .riskTriggered(decision.getRiskTriggered()).riskLevel(decision.getRiskLevel()).riskStatus(IndicatorRiskStatus.EVALUATED)
                .calculatedAt(now).createdAt(now).build());
    }

    private void writable(AnalysisScope scope) {
        AnalysisRun run = runs.lockScope(scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("评分运行不存在"));
        if (run.getExecutionMode() != AnalysisExecutionMode.REASONING || run.getStatus() != AnalysisRunStatus.RUNNING || run.getAnalysisSummary() != null)
            throw new IllegalStateException("评分只能写入尚未准备报告的本次合规运行");
    }
}
