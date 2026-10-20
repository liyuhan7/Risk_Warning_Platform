package com.riskwarning.processing.rule.v2;

import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.RequirementFinding.ConditionValue;
import com.riskwarning.common.dto.scoring.ScoringDecision.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.processing.rule.RuleContractException;
import java.math.BigDecimal;
import java.util.*;

/** 条件判断与分档的唯一实现，同时用于事实结果适配和最终评分。 */
public class RequirementJudgmentEvaluator {
    private final UniversalConditionEvaluator evaluator;
    public RequirementJudgmentEvaluator(UniversalConditionEvaluator evaluator) { this.evaluator = evaluator; }

    public Atomic evaluate(Requirement requirement, String behavior, RequirementFinding finding, Set<String> evidence) {
        boolean derived = finding != null && "FACTS_DERIVED".equals(finding.getJudgmentSource());
        Atomic atom = new Atomic(); atom.setBehaviorId(behavior); atom.setRequirementId(requirement.getRequirementId());
        atom.setJudgmentSource(derived ? "FACTS_DERIVED" : "MODEL_SEMANTIC");
        atom.setRegulationId(requirement.getSourceRegulationId());
        atom.setEvidenceIds(finding == null || finding.getEvidenceIds() == null ? Collections.emptyList() : new ArrayList<>(finding.getEvidenceIds()));
        if (!Boolean.TRUE.equals(requirement.getExpressible())) return pending(atom, Judgment.NEEDS_REVIEW, requirement.getReviewReason());
        if (finding == null) return pending(atom, Judgment.NEEDS_REVIEW, "模型缺少该行为要求的事实输出");
        if (!text(finding.getReasoning()) || finding.getApplicability() == null) return pending(atom, Judgment.NEEDS_REVIEW, "适用性或解释缺失");
        if (finding.getConfidence() != null && (finding.getConfidence().signum() < 0 || finding.getConfidence().compareTo(BigDecimal.ONE) > 0))
            return pending(atom, Judgment.INVALID_INPUT, "模型置信度超出范围");
        if (evidence == null || finding.getEvidenceIds() == null || finding.getEvidenceIds().isEmpty())
            return pending(atom, Judgment.INSUFFICIENT_EVIDENCE, "行为要求缺少证据引用");
        if (!evidence.containsAll(finding.getEvidenceIds()) || new HashSet<>(finding.getEvidenceIds()).size() != finding.getEvidenceIds().size())
            return pending(atom, Judgment.INVALID_INPUT, "证据引用不属于本行为或重复");
        if (finding.getApplicability() == Applicability.UNKNOWN) return pending(atom, Judgment.NEEDS_REVIEW, "适用性未知");
        if (finding.getApplicability() == Applicability.NOT_APPLICABLE) { atom.setJudgment(Judgment.NOT_APPLICABLE); atom.setReason(finding.getReasoning()); return atom; }
        if (!derived && finding.getComplianceStatus() == ComplianceStatus.INSUFFICIENT_EVIDENCE)
            return pending(atom, Judgment.INSUFFICIENT_EVIDENCE, finding.getReasoning());
        if (!derived && finding.getComplianceStatus() != ComplianceStatus.COMPLIANT && finding.getComplianceStatus() != ComplianceStatus.NON_COMPLIANT)
            return pending(atom, Judgment.NEEDS_REVIEW, "模型语义结果未决");
        if (finding.getConditionValues() == null) return pending(atom, Judgment.NEEDS_REVIEW, "条件事实缺失");
        Set<String> leafIds = new HashSet<>(); leaves(requirement.getCondition(), leafIds);
        if (requirement.getExceptWhen() != null) leaves(requirement.getExceptWhen(), leafIds);
        if (!leafIds.containsAll(finding.getConditionValues().keySet())) return pending(atom, Judgment.INVALID_INPUT, "包含未登记条件事实");
        for (ConditionValue value : finding.getConditionValues().values()) {
            // 未知值参与三值运算；不把未知分支提前当作违规，已知分支仍可决定组合结果。
            if (derived && value != null && ("NOT_STATED".equals(value.getFactStatus()) || "UNCERTAIN".equals(value.getFactStatus()))) {
                if (value.getConditionMet() != null || value.getObservedValue() != null)
                    return pending(atom, Judgment.INVALID_INPUT, "未知事实不能携带确定值");
                continue;
            }
            if (value == null || value.getEvidenceIds() == null || value.getEvidenceIds().isEmpty())
                return pending(atom, Judgment.INSUFFICIENT_EVIDENCE, "条件事实缺少证据");
            if (!new HashSet<>(finding.getEvidenceIds()).containsAll(value.getEvidenceIds())
                    || new HashSet<>(value.getEvidenceIds()).size() != value.getEvidenceIds().size())
                return pending(atom, Judgment.INVALID_INPUT, "条件事实引用不属于要求证据或重复");
        }
        try {
            if (requirement.getExceptWhen() != null) {
                UniversalConditionEvaluator.Truth except = evaluator.evaluate(requirement.getExceptWhen(), requirement.getValueType(), finding.getConditionValues());
                if (except == UniversalConditionEvaluator.Truth.UNKNOWN) return pending(atom, unknownJudgment(finding), "例外条件待核实");
                if (except == UniversalConditionEvaluator.Truth.TRUE) { atom.setJudgment(Judgment.NOT_APPLICABLE); atom.setReason("登记例外成立"); return atom; }
            }
            UniversalConditionEvaluator.Truth truth = evaluator.evaluate(requirement.getCondition(), requirement.getValueType(), finding.getConditionValues());
            if (truth == UniversalConditionEvaluator.Truth.UNKNOWN) return pending(atom, unknownJudgment(finding), "无法确定条件事实");
            BigDecimal observation = requirement.getCondition().getConditionId() == null ? null
                    : finding.getConditionValues().get(requirement.getCondition().getConditionId()).getObservedValue();
            setScore(atom, requirement, truth == UniversalConditionEvaluator.Truth.TRUE, observation);
            boolean modelViolation = finding.getComplianceStatus() == ComplianceStatus.NON_COMPLIANT;
            atom.setConflict(!derived && modelViolation != (atom.getJudgment() == Judgment.VIOLATED));
            atom.setReason(finding.getReasoning());
            return atom;
        } catch (RuleContractException invalid) { return pending(atom, Judgment.INVALID_INPUT, invalid.getMessage()); }
    }

    private static Judgment unknownJudgment(RequirementFinding finding) {
        return finding.getConditionValues().values().stream().filter(Objects::nonNull)
                .anyMatch(value -> "NOT_STATED".equals(value.getFactStatus()))
                ? Judgment.INSUFFICIENT_EVIDENCE : Judgment.NEEDS_REVIEW;
    }

    public void setScore(Atomic atom, Requirement requirement, boolean satisfied, BigDecimal observation) {
        atom.setConditionMet(satisfied);
        atom.setJudgment(satisfied ? Judgment.SATISFIED : requirement.getDirection() == Direction.OPTIONAL ? Judgment.UNSATISFIED_OPTIONAL : Judgment.VIOLATED);
        atom.setObservedValue(observation);
        if (observation != null) atom.setObservedUnit(requirement.getCondition().getUnit());
        if (!satisfied && requirement.getDirection() != Direction.OPTIONAL) {
            if (requirement.getValueType() == ValueType.BOOLEAN) {
                atom.setGapType(requirement.getDirection() == Direction.FORBIDDEN ? GapType.PROHIBITED_ACTION : GapType.MISSING_ACTION);
            } else {
                Condition condition = requirement.getCondition();
                BigDecimal target = condition.getOperand();
                boolean excess = condition.getOperator() == Operator.LE || condition.getOperator() == Operator.LT
                        || condition.getOperator() == Operator.BETWEEN && observation.compareTo(condition.getUpperOperand()) > 0
                        || condition.getOperator() == Operator.EQ && observation.compareTo(target) > 0;
                if (condition.getOperator() == Operator.BETWEEN && excess) target = condition.getUpperOperand();
                atom.setGapType(excess ? GapType.QUANTITATIVE_EXCESS : GapType.QUANTITATIVE_SHORTFALL);
                atom.setGapValue(observation.subtract(target).abs()); atom.setGapUnit(condition.getUnit());
            }
        }
        if (requirement.getGrading().getMode() == GradingMode.BINARY)
            atom.setSatisfaction(satisfied ? requirement.getGrading().getSatisfied() : requirement.getGrading().getViolated());
        else {
            check(observation != null, "分段计分缺少观测值");
            Segment match = null;
            for (Segment segment : requirement.getGrading().getSegments()) {
                if (contains(segment.getLower(), segment.getUpper(), segment.getLowerInclusive(), segment.getUpperInclusive(), observation)) {
                    check(match == null, "观测值命中重叠分段"); match = segment;
                }
            }
            check(match != null, "观测值超出登记定义域");
            atom.setSatisfaction(match.getSatisfaction()); atom.setViolationDegree(match.getViolationDegree());
        }
    }

    private static boolean contains(BigDecimal lower, BigDecimal upper, boolean li, boolean ui, BigDecimal x) {
        return (lower == null || x.compareTo(lower) > 0 || li && x.compareTo(lower) == 0)
                && (upper == null || x.compareTo(upper) < 0 || ui && x.compareTo(upper) == 0);
    }
    private static void leaves(Condition condition, Set<String> ids) {
        if (condition.getConditionId() != null) ids.add(condition.getConditionId());
        if (condition.getAllOf() != null) condition.getAllOf().forEach(child -> leaves(child, ids));
        if (condition.getAnyOf() != null) condition.getAnyOf().forEach(child -> leaves(child, ids));
        if (condition.getNotOf() != null) leaves(condition.getNotOf(), ids);
    }
    private static Atomic pending(Atomic atom, Judgment judgment, String reason) { atom.setJudgment(judgment); atom.setReason(reason); return atom; }
    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
    private static void check(boolean valid, String message) { if (!valid) throw new RuleContractException("INVALID_INPUT", message); }
}
