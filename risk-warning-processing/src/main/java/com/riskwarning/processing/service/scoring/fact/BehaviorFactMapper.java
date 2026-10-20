package com.riskwarning.processing.service.scoring.fact;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.FactExtractionPlan.*;
import com.riskwarning.common.dto.scoring.ScoringDecision.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.processing.dto.analysis.BehaviorAnalysisContextVO;
import com.riskwarning.processing.rule.v2.RequirementScoringEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 把共享观测值分发回登记条件，再以同一条件判定器生成要求结论。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class BehaviorFactMapper {
    private final RequirementScoringEngine engine;

    public List<AnalysisResult> map(AnalysisScope scope, ScoringRunSnapshot snapshot, BehaviorAnalysisContextVO behavior,
                                    Map<String, Answer> answers) {
        FactExtractionPlan settings = snapshot.getFactExtractionPlan();
        BehaviorPlan plan = settings.getBehaviors().get(behavior.getBehaviorId());
        Map<String, Question> questions = plan.getQuestions().stream().collect(Collectors.toMap(Question::getFactKey, question -> question));
        List<AnalysisResult> results = new ArrayList<>();
        for (String indicator : new TreeSet<>(snapshot.getRuleDefinitions().keySet())) {
            if (!snapshot.getIndicatorBehaviors().get(indicator).contains(behavior.getBehaviorId())) continue;
            List<RequirementFinding> findings = new ArrayList<>();
            for (Requirement requirement : snapshot.getRuleDefinitions().get(indicator).getRequirements()) {
                RequirementFinding finding = new RequirementFinding(); finding.setIndicatorId(indicator); finding.setBehaviorId(behavior.getBehaviorId());
                finding.setRequirementId(requirement.getRequirementId()); finding.setJudgmentSource("FACTS_DERIVED");
                finding.setApplicability(Applicability.UNKNOWN); finding.setConditionValues(new TreeMap<>());
                Set<String> evidence = new TreeSet<>(); List<String> explanations = new ArrayList<>(); List<BigDecimal> confidences = new ArrayList<>();
                List<Condition> conditions = SharedFactCatalog.leaves(requirement.getCondition());
                if (requirement.getExceptWhen() != null) conditions.addAll(SharedFactCatalog.leaves(requirement.getExceptWhen()));
                for (Binding binding : plan.getBindings()) if (indicator.equals(binding.getIndicatorId()) && requirement.getRequirementId().equals(binding.getRequirementId())) {
                    Question question = questions.get(binding.getFactKey()); Answer answer = answers.get(question.getId());
                    if (answer == null) { answer = new Answer(); answer.setStatus("UNCERTAIN"); }
                    List<String> refs = answer.getEvidenceIds().stream().map(plan.getEvidenceReferences()::get).filter(Objects::nonNull).collect(Collectors.toList());
                    evidence.addAll(refs);
                    if (answer.getConfidence() != null) confidences.add(answer.getConfidence());
                    if (binding.getConditionId() == null) {
                        if ("KNOWN".equals(answer.getStatus())) finding.setApplicability(Applicability.valueOf(answer.getApplicability()));
                        explanations.add("适用性=" + finding.getApplicability());
                    } else {
                        RequirementFinding.ConditionValue value = new RequirementFinding.ConditionValue(); value.setFactStatus(answer.getStatus());
                        value.setConditionMet(answer.getBooleanValue()); value.setObservedValue(answer.getNumberValue()); value.setObservedUnit(answer.getUnit()); value.setEvidenceIds(refs);
                        finding.getConditionValues().put(binding.getConditionId(), value);
                        String observation = "KNOWN".equals(answer.getStatus()) ? Objects.toString(answer.getBooleanValue(), Objects.toString(answer.getNumberValue(), "")) + Objects.toString(answer.getUnit(), "") : answer.getStatus();
                        Condition condition = conditions.stream().filter(item -> binding.getConditionId().equals(item.getConditionId())).findFirst().get();
                        explanations.add(binding.getConditionId() + "：观测=" + observation + "，登记条件=" + condition.getOperator()
                                + (condition.getOperand() == null ? "" : " " + condition.getOperand())
                                + (condition.getUpperOperand() == null ? "" : " 至 " + condition.getUpperOperand()) + "，证据=" + refs);
                    }
                }
                // 对原文不足的说明仍关联本行为材料，不借用其他行为补证。
                finding.setEvidenceIds(evidence.isEmpty() ? new ArrayList<>(behavior.getEvidenceIds()) : new ArrayList<>(evidence));
                finding.setConfidence(confidences.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
                finding.setReasoning(String.join("；", explanations));
                Atomic atomic = engine.judgeRequirement(requirement, behavior.getBehaviorId(), finding, new HashSet<>(behavior.getEvidenceIds()));
                finding.setComplianceStatus(status(atomic.getJudgment()));
                String detail = atomic.getReason();
                finding.setReasoning(finding.getReasoning() + "；程序判定=" + atomic.getJudgment()
                        + (detail == null || detail.equals(finding.getReasoning()) ? "" : "；" + detail));
                findings.add(finding);
            }
            boolean unknown = findings.stream().anyMatch(item -> item.getApplicability() == Applicability.UNKNOWN);
            boolean allNotApplicable = findings.stream().allMatch(item -> item.getApplicability() == Applicability.NOT_APPLICABLE);
            boolean violation = findings.stream().anyMatch(item -> item.getComplianceStatus() == ComplianceStatus.NON_COMPLIANT);
            boolean pending = findings.stream().anyMatch(item -> item.getComplianceStatus() == ComplianceStatus.NEEDS_REVIEW || item.getComplianceStatus() == ComplianceStatus.INSUFFICIENT_EVIDENCE);
            results.add(AnalysisResult.builder().id(ScoringJson.hash(Arrays.asList(scope.getAnalysisRunId(), behavior.getBehaviorId(), indicator)))
                    .schemaVersion("1.2").analysisRunId(scope.getAnalysisRunId()).assessmentId(scope.getAssessmentId()).behaviorId(behavior.getBehaviorId()).indicatorId(indicator)
                    .evidenceIds(behavior.getEvidenceIds()).regulationIds(snapshot.getSourceBundles().get(indicator).getRelations().stream().map(ScoringSourceBundle.Source::getRegulationId).collect(Collectors.toList()))
                    .applicable(unknown ? Applicability.UNKNOWN : allNotApplicable ? Applicability.NOT_APPLICABLE : Applicability.APPLICABLE)
                    .complianceStatus(unknown ? ComplianceStatus.NEEDS_REVIEW : violation ? ComplianceStatus.NON_COMPLIANT : pending ? ComplianceStatus.NEEDS_REVIEW : ComplianceStatus.COMPLIANT)
                    .requirement("逐要求条件由共享事实映射").enterpriseFact(behavior.getDescription()).requirementFindings(findings)
                    .confidence(findings.stream().map(RequirementFinding::getConfidence).mapToDouble(BigDecimal::doubleValue).min().orElse(0))
                    .reasoning("FACTS_DERIVED：模型提供事实，程序执行登记条件；未进行独立模型合规结论比较")
                    .modelVersion(settings.getModelVersion()).promptVersion(settings.getPromptVersion()).analyzedAt(LocalDateTime.now()).build());
        }
        return results;
    }

    private ComplianceStatus status(Judgment judgment) {
        if (judgment == Judgment.VIOLATED) return ComplianceStatus.NON_COMPLIANT;
        if (judgment == Judgment.SATISFIED || judgment == Judgment.UNSATISFIED_OPTIONAL || judgment == Judgment.NOT_APPLICABLE) return ComplianceStatus.COMPLIANT;
        return judgment == Judgment.INSUFFICIENT_EVIDENCE ? ComplianceStatus.INSUFFICIENT_EVIDENCE : ComplianceStatus.NEEDS_REVIEW;
    }
}
