package com.riskwarning.processing.rule.v2;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.RequirementFinding.ConditionValue;
import com.riskwarning.common.dto.scoring.ScoringDecision.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.processing.rule.RuleContractException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 按登记条件计算逐行为要求结果，再跨行为和跨要求聚合，不采纳模型分数。 */
@Component
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class RequirementScoringEngine {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final ScoringRuleRegistry registry;
    private final UniversalConditionEvaluator evaluator;

    public RequirementScoringEngine(ScoringRuleRegistry registry, UniversalConditionEvaluator evaluator) {
        this.registry = registry; this.evaluator = evaluator;
    }

    /** 行为及可用证据清单必须来自本次持久化事实；缺少模型要求输出也会生成待审记录。 */
    public ScoringDecision evaluate(AnalysisScope scope, String indicatorId, ScoringSourceBundle bundle,
                                    Map<String, Set<String>> expectedEvidence, List<RequirementFinding> findings,
                                    Map<String, LocalDateTime> observedTimes) {
        IndicatorRule rule = registry.require(indicatorId, bundle);
        check(scope != null && scope.getProjectId() != null && scope.getProjectId() > 0 && scope.getAssessmentId() != null && scope.getAssessmentId() > 0 && text(scope.getAnalysisRunId())
                && expectedEvidence != null && !expectedEvidence.isEmpty() && findings != null && observedTimes != null, "运行范围或输入清单缺失");
        Set<String> requirementIds = rule.getRequirements().stream().map(Requirement::getRequirementId).collect(Collectors.toSet());
        Map<String, RequirementFinding> byKey = new TreeMap<>();
        for (RequirementFinding finding : findings) {
            check(finding != null && indicatorId.equals(finding.getIndicatorId()) && requirementIds.contains(finding.getRequirementId())
                    && expectedEvidence.containsKey(finding.getBehaviorId()), "要求事实跨指标、跨行为或未登记");
            check(byKey.put(key(finding.getBehaviorId(), finding.getRequirementId()), finding) == null, "行为要求事实重复");
        }
        ScoringDecision decision = new ScoringDecision();
        decision.setSchemaVersion("2.0"); decision.setIndicatorId(indicatorId);
        decision.setRegistryVersion(registry.version()); decision.setRuleVersion(rule.getRuleVersion());
        decision.setThresholdVersion(rule.getThresholdVersion()); decision.setSourceBundleHash(bundle.bundleHash());
        Map<String, Object> fingerprint = new TreeMap<>();
        fingerprint.put("scope", scope); fingerprint.put("registryVersion", registry.version()); fingerprint.put("rule", rule);
        fingerprint.put("bundle", bundle); fingerprint.put("expectedEvidence", canonicalEvidence(expectedEvidence));
        fingerprint.put("findings", byKey); fingerprint.put("observedTimes", new TreeMap<>(observedTimes));
        decision.setInputHash(ScoringJson.hash(fingerprint));
        List<Atomic> atoms = new ArrayList<>();
        List<RequirementSummary> summaries = new ArrayList<>();
        List<String> behaviors = new ArrayList<>(expectedEvidence.keySet()); Collections.sort(behaviors);
        for (Requirement requirement : rule.getRequirements()) {
            List<Atomic> rows = new ArrayList<>();
            for (String behavior : behaviors) {
                Atomic atom = atom(requirement, behavior, byKey.get(key(behavior, requirement.getRequirementId())), expectedEvidence.get(behavior));
                rows.add(atom); atoms.add(atom);
            }
            summaries.add(aggregate(requirement, rows, observedTimes));
        }
        // 逐行为分配份额，只用于该行为明细；指标最终分按要求的跨行为结果重新计算。
        for (String behavior : behaviors) allocate(rule, atoms.stream().filter(a -> behavior.equals(a.getBehaviorId())).collect(Collectors.toList()));
        decision.setEvaluations(atoms); decision.setRequirements(summaries);
        decision.setConflict(atoms.stream().anyMatch(Atomic::isConflict));
        if (summaries.stream().anyMatch(summary -> unresolved(summary.getJudgment()))) {
            decision.setStatus("NEEDS_REVIEW");
            return decision;
        }
        BigDecimal denominator = BigDecimal.ZERO;
        boolean pending = false;
        for (RequirementSummary summary : summaries) {
            if (summary.getJudgment() != Judgment.NOT_APPLICABLE) denominator = denominator.add(requirement(rule, summary.getRequirementId()).getWeight());
            pending |= unresolved(summary.getJudgment());
        }
        if (pending || denominator.signum() == 0) {
            decision.setStatus(pending ? "NEEDS_REVIEW" : "NOT_APPLICABLE");
            return decision;
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean violation = false;
        RiskLevelEnum floor = RiskLevelEnum.NO_RISK;
        for (RequirementSummary summary : summaries) {
            if (summary.getJudgment() == Judgment.NOT_APPLICABLE) continue;
            Requirement requirement = requirement(rule, summary.getRequirementId());
            BigDecimal maximum = HUNDRED.multiply(requirement.getWeight()).divide(denominator, MC);
            summary.setMaximum(maximum); summary.setEarned(maximum.multiply(summary.getSatisfaction(), MC));
            total = total.add(summary.getEarned());
            if (summary.getJudgment() == Judgment.VIOLATED && requirement.getDirection() != Direction.OPTIONAL) {
                violation = true;
                floor = higher(floor, requirement.getMinimumRiskLevel());
            }
        }
        // 在最终边界进行一次十进制舍入，避免循环小数份额累加偏离百分制上限。
        total = total.round(MC).min(HUNDRED).max(BigDecimal.ZERO);
        RiskLevelEnum level = violation ? risk(rule, total) : RiskLevelEnum.NO_RISK;
        level = higher(level, floor);
        decision.setStatus(decision.isConflict() ? "DECIDED_WITH_CONFLICT" : "DECIDED");
        decision.setCalculatedScore(total); decision.setMaxPossibleScore(HUNDRED);
        decision.setRiskLevel(level); decision.setRiskTriggered(level != RiskLevelEnum.NO_RISK);
        return decision;
    }

    private Atomic atom(Requirement requirement, String behavior, RequirementFinding finding, Set<String> evidence) {
        Atomic atom = new Atomic(); atom.setBehaviorId(behavior); atom.setRequirementId(requirement.getRequirementId());
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
        if (finding.getComplianceStatus() == ComplianceStatus.INSUFFICIENT_EVIDENCE)
            return pending(atom, Judgment.INSUFFICIENT_EVIDENCE, finding.getReasoning());
        if (finding.getComplianceStatus() != ComplianceStatus.COMPLIANT && finding.getComplianceStatus() != ComplianceStatus.NON_COMPLIANT)
            return pending(atom, Judgment.NEEDS_REVIEW, "模型语义结果未决");
        if (finding.getConditionValues() == null) return pending(atom, Judgment.NEEDS_REVIEW, "条件事实缺失");
        Set<String> leafIds = new HashSet<>(); leaves(requirement.getCondition(), leafIds);
        if (requirement.getExceptWhen() != null) leaves(requirement.getExceptWhen(), leafIds);
        if (!leafIds.containsAll(finding.getConditionValues().keySet())) return pending(atom, Judgment.INVALID_INPUT, "包含未登记条件事实");
        for (ConditionValue value : finding.getConditionValues().values()) {
            if (value == null || value.getEvidenceIds() == null || value.getEvidenceIds().isEmpty())
                return pending(atom, Judgment.INSUFFICIENT_EVIDENCE, "条件事实缺少证据");
            if (!new HashSet<>(finding.getEvidenceIds()).containsAll(value.getEvidenceIds())
                    || new HashSet<>(value.getEvidenceIds()).size() != value.getEvidenceIds().size())
                return pending(atom, Judgment.INVALID_INPUT, "条件事实引用不属于要求证据或重复");
        }
        try {
            if (requirement.getExceptWhen() != null) {
                UniversalConditionEvaluator.Truth except = evaluator.evaluate(requirement.getExceptWhen(), requirement.getValueType(), finding.getConditionValues());
                if (except == UniversalConditionEvaluator.Truth.UNKNOWN) return pending(atom, Judgment.NEEDS_REVIEW, "例外条件待核实");
                if (except == UniversalConditionEvaluator.Truth.TRUE) { atom.setJudgment(Judgment.NOT_APPLICABLE); atom.setReason("登记例外成立"); return atom; }
            }
            UniversalConditionEvaluator.Truth truth = evaluator.evaluate(requirement.getCondition(), requirement.getValueType(), finding.getConditionValues());
            if (truth == UniversalConditionEvaluator.Truth.UNKNOWN) return pending(atom, Judgment.NEEDS_REVIEW, "无法确定条件事实");
            BigDecimal observation = requirement.getCondition().getConditionId() == null ? null
                    : finding.getConditionValues().get(requirement.getCondition().getConditionId()).getObservedValue();
            setScore(atom, requirement, truth == UniversalConditionEvaluator.Truth.TRUE, observation);
            boolean modelViolation = finding.getComplianceStatus() == ComplianceStatus.NON_COMPLIANT;
            atom.setConflict(modelViolation != (atom.getJudgment() == Judgment.VIOLATED));
            atom.setReason(finding.getReasoning());
            return atom;
        } catch (RuleContractException invalid) { return pending(atom, Judgment.INVALID_INPUT, invalid.getMessage()); }
    }

    private void setScore(Atomic atom, Requirement requirement, boolean satisfied, BigDecimal observation) {
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

    private RequirementSummary aggregate(Requirement requirement, List<Atomic> all, Map<String, LocalDateTime> times) {
        RequirementSummary summary = new RequirementSummary(); summary.setRequirementId(requirement.getRequirementId());
        List<Atomic> rows = all.stream().filter(a -> a.getJudgment() != Judgment.NOT_APPLICABLE).collect(Collectors.toList());
        summary.setContributingBehaviorIds(rows.stream().map(Atomic::getBehaviorId).collect(Collectors.toList()));
        if (rows.isEmpty()) { summary.setJudgment(Judgment.NOT_APPLICABLE); return summary; }
        if (rows.stream().anyMatch(a -> unresolved(a.getJudgment()))) { summary.setJudgment(Judgment.NEEDS_REVIEW); return summary; }
        Aggregation op = requirement.getCrossBehaviorAggregation();
        if (op == Aggregation.SINGLE && rows.size() != 1) { summary.setJudgment(Judgment.NEEDS_REVIEW); return summary; }
        if (op == Aggregation.LATEST) {
            if (rows.stream().anyMatch(a -> times.get(a.getBehaviorId()) == null)) { summary.setJudgment(Judgment.NEEDS_REVIEW); return summary; }
            LocalDateTime latest = rows.stream().map(a -> times.get(a.getBehaviorId())).max(LocalDateTime::compareTo).get();
            rows = rows.stream().filter(a -> latest.equals(times.get(a.getBehaviorId()))).collect(Collectors.toList());
            Atomic first = rows.get(0);
            if (rows.stream().anyMatch(a -> a.getSatisfaction().compareTo(first.getSatisfaction()) != 0
                    || !equal(a.getObservedValue(), first.getObservedValue()) || a.getJudgment() != first.getJudgment())) {
                summary.setJudgment(Judgment.NEEDS_REVIEW); return summary;
            }
        }
        summary.setContributingBehaviorIds(rows.stream().map(Atomic::getBehaviorId).collect(Collectors.toList()));
        Atomic selected;
        if (requirement.getValueType() == ValueType.NUMBER && Arrays.asList(Aggregation.MIN, Aggregation.MAX, Aggregation.SUM, Aggregation.AVG).contains(op)) {
            BigDecimal observation;
            if (op == Aggregation.MIN) observation = rows.stream().map(Atomic::getObservedValue).min(BigDecimal::compareTo).get();
            else if (op == Aggregation.MAX) observation = rows.stream().map(Atomic::getObservedValue).max(BigDecimal::compareTo).get();
            else {
                observation = rows.stream().map(Atomic::getObservedValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (op == Aggregation.AVG) observation = observation.divide(BigDecimal.valueOf(rows.size()), MC);
            }
            selected = new Atomic();
            try { setScore(selected, requirement, UniversalConditionEvaluator.number(requirement.getCondition(), observation), observation); }
            catch (RuleContractException invalid) { summary.setJudgment(Judgment.INVALID_INPUT); return summary; }
        } else {
            boolean any = op == Aggregation.ANY || op == Aggregation.EXISTS;
            selected = any ? rows.stream().max(Comparator.comparing(Atomic::getSatisfaction)).get()
                    : rows.stream().min(Comparator.comparing(Atomic::getSatisfaction)).get();
        }
        summary.setJudgment(selected.getJudgment()); summary.setSatisfaction(selected.getSatisfaction()); summary.setObservedValue(selected.getObservedValue());
        return summary;
    }

    private void allocate(IndicatorRule rule, List<Atomic> rows) {
        if (rows.stream().anyMatch(atom -> atom.getJudgment() != Judgment.NOT_APPLICABLE
                && requirement(rule, atom.getRequirementId()).getWeight() == null)) return;
        BigDecimal denominator = rows.stream().filter(a -> a.getJudgment() != Judgment.NOT_APPLICABLE)
                .map(a -> requirement(rule, a.getRequirementId()).getWeight()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (denominator.signum() == 0) return;
        for (Atomic atom : rows) if (atom.getSatisfaction() != null) {
            atom.setMaximum(HUNDRED.multiply(requirement(rule, atom.getRequirementId()).getWeight()).divide(denominator, MC));
            atom.setEarned(atom.getMaximum().multiply(atom.getSatisfaction(), MC));
        }
    }

    private RiskLevelEnum risk(IndicatorRule rule, BigDecimal score) {
        RiskLevelEnum level = RiskLevelEnum.NO_RISK;
        for (RiskInterval interval : rule.getRiskIntervals())
            if (contains(interval.getLower().multiply(HUNDRED), interval.getUpper().multiply(HUNDRED),
                    interval.getLowerInclusive(), interval.getUpperInclusive(), score)) level = interval.getRiskLevel();
        return level;
    }

    private static boolean contains(BigDecimal lower, BigDecimal upper, boolean li, boolean ui, BigDecimal x) {
        return (lower == null || x.compareTo(lower) > 0 || li && x.compareTo(lower) == 0)
                && (upper == null || x.compareTo(upper) < 0 || ui && x.compareTo(upper) == 0);
    }
    private static Map<String, List<String>> canonicalEvidence(Map<String, Set<String>> evidence) {
        Map<String, List<String>> out = new TreeMap<>();
        evidence.forEach((id, ids) -> { check(text(id) && ids != null && ids.stream().allMatch(RequirementScoringEngine::text), "行为或证据编号无效"); List<String> sorted = new ArrayList<>(ids); Collections.sort(sorted); out.put(id, sorted); });
        return out;
    }
    private static void leaves(Condition condition, Set<String> ids) {
        if (condition.getConditionId() != null) ids.add(condition.getConditionId());
        if (condition.getAllOf() != null) condition.getAllOf().forEach(child -> leaves(child, ids));
        if (condition.getAnyOf() != null) condition.getAnyOf().forEach(child -> leaves(child, ids));
        if (condition.getNotOf() != null) leaves(condition.getNotOf(), ids);
    }
    private static boolean unresolved(Judgment judgment) { return judgment == Judgment.NEEDS_REVIEW || judgment == Judgment.INSUFFICIENT_EVIDENCE || judgment == Judgment.INVALID_INPUT; }
    private static Atomic pending(Atomic atom, Judgment judgment, String reason) { atom.setJudgment(judgment); atom.setReason(reason); return atom; }
    private static Requirement requirement(IndicatorRule rule, String id) { return rule.getRequirements().stream().filter(r -> id.equals(r.getRequirementId())).findFirst().get(); }
    private static String key(String behavior, String requirement) { return behavior + "\u0000" + requirement; }
    private static boolean equal(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private static boolean text(String text) { return text != null && !text.trim().isEmpty(); }
    private static void check(boolean valid, String message) { if (!valid) throw new RuleContractException("INVALID_INPUT", message); }
    private static RiskLevelEnum higher(RiskLevelEnum a, RiskLevelEnum b) { return b != null && b.ordinal() > a.ordinal() ? b : a; }
}
