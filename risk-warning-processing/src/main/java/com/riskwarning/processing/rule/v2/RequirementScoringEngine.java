package com.riskwarning.processing.rule.v2;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
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
    private final RequirementJudgmentEvaluator judgments;

    public RequirementScoringEngine(ScoringRuleRegistry registry, UniversalConditionEvaluator evaluator) {
        this.registry = registry; this.judgments = new RequirementJudgmentEvaluator(evaluator);
    }

    /** 事实映射和最终计分共用条件语义。 */
    public Atomic judgeRequirement(Requirement rule, String behavior, RequirementFinding finding, Set<String> evidence) {
        return judgments.evaluate(rule, behavior, finding, evidence);
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
                Atomic atom = judgeRequirement(requirement, behavior, byKey.get(key(behavior, requirement.getRequirementId())), expectedEvidence.get(behavior));
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
            try { judgments.setScore(selected, requirement, UniversalConditionEvaluator.number(requirement.getCondition(), observation), observation); }
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
    private static boolean unresolved(Judgment judgment) { return judgment == Judgment.NEEDS_REVIEW || judgment == Judgment.INSUFFICIENT_EVIDENCE || judgment == Judgment.INVALID_INPUT; }
    private static Requirement requirement(IndicatorRule rule, String id) { return rule.getRequirements().stream().filter(r -> id.equals(r.getRequirementId())).findFirst().get(); }
    private static String key(String behavior, String requirement) { return behavior + "\u0000" + requirement; }
    private static boolean equal(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private static boolean text(String text) { return text != null && !text.trim().isEmpty(); }
    private static void check(boolean valid, String message) { if (!valid) throw new RuleContractException("INVALID_INPUT", message); }
    private static RiskLevelEnum higher(RiskLevelEnum a, RiskLevelEnum b) { return b != null && b.ordinal() > a.ordinal() ? b : a; }
}
