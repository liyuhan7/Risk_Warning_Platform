package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.AnalysisResult;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import java.time.LocalDateTime;
import java.util.*;

/** 分组输入在规则执行前统一校验，说明文字和旧 Range 表达式不参与装配。 */
@Component
@RequiredArgsConstructor
public class RuleInputAssembler {
    private final VerifiedRuleRegistry registry;

    public PreparedInputs prepare(AnalysisScope scope, String indicatorId, List<AnalysisResult> results,
                                  JsonNode currentSource, Map<String, LocalDateTime> factTimes) {
        VerifiedRuleDefinition rule = registry.require(indicatorId, currentSource);
        if (scope == null || results == null || results.isEmpty()) fail("INVALID_INPUT", "作用域与输入不能为空");
        // 未核验多事实聚合的规则只接受一条事实，不从输入中任选一条。
        if (rule.getAggregationOperator() == AggregationOperator.SINGLE && results.size() != 1)
            fail("NEEDS_REVIEW", "单事实规则不允许多事实计分");
        List<RuleInput> inputs = new ArrayList<>(); Set<String> behaviors = new HashSet<>(); Set<String> resultIds = new HashSet<>();
        for (AnalysisResult result : results) {
            if (result == null || !"1.1".equals(result.getSchemaVersion()) || !scope.getAnalysisRunId().equals(result.getAnalysisRunId())
                    || !scope.getAssessmentId().equals(result.getAssessmentId()) || !indicatorId.equals(result.getIndicatorId())
                    || !text(result.getId()) || !text(result.getBehaviorId()) || !behaviors.add(result.getBehaviorId()) || !resultIds.add(result.getId()))
                fail("INVALID_INPUT", "结果跨运行/指标、版本不支持或存在重复事实");
            if (result.getApplicable() != Applicability.APPLICABLE || !Arrays.asList(ComplianceStatus.COMPLIANT, ComplianceStatus.NON_COMPLIANT).contains(result.getComplianceStatus()))
                fail("NEEDS_REVIEW", "未决或不适用事实不能参与计分");
            if (result.getEvidenceIds() == null || result.getEvidenceIds().isEmpty() || result.getEvidenceIds().stream().anyMatch(id -> !text(id))
                    || new HashSet<>(result.getEvidenceIds()).size() != result.getEvidenceIds().size() || result.getAnalyzedAt() == null)
                fail("INVALID_INPUT", "证据引用或分析时间缺失");
            if (rule.getRuleType() == RuleTypeEnum.BINARY) {
                if (result.getConditionMet() == null || result.getObservedValue() != null || result.getObservedUnit() != null)
                    fail("INVALID_INPUT", "Binary 输入缺失或包含 Range 字段");
            } else if (result.getConditionMet() != null || result.getObservedValue() == null || !Double.isFinite(result.getObservedValue())
                    || !rule.getInputUnit().equals(result.getObservedUnit())) fail("INVALID_INPUT", "Range 值或单位无效");
            LocalDateTime factTime = factTimes == null ? null : factTimes.get(result.getBehaviorId());
            if (rule.getAggregationOperator() == AggregationOperator.LATEST && factTime == null)
                fail("NEEDS_REVIEW", "LATEST 必须提供事实观察时间，不使用模型调用时间代替");
            List<String> evidence = new ArrayList<>(result.getEvidenceIds()); Collections.sort(evidence);
            inputs.add(RuleInput.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId()).analysisRunId(scope.getAnalysisRunId())
                    .indicatorId(indicatorId).analysisResultId(result.getId()).behaviorId(result.getBehaviorId()).evidenceIds(evidence)
                    .ruleType(rule.getRuleType()).conditionMet(result.getConditionMet()).observedValue(result.getObservedValue()).observedUnit(result.getObservedUnit())
                    .factObservedAt(factTime).analyzedAt(result.getAnalyzedAt()).complianceStatus(result.getComplianceStatus()).build());
        }
        inputs.sort(Comparator.comparing(RuleInput::getBehaviorId).thenComparing(RuleInput::getAnalysisResultId));
        return new PreparedInputs(rule, Collections.unmodifiableList(inputs), fingerprint(scope, rule, inputs));
    }
    /** 执行方必须复核输入未在装配后被替换或篡改，不能只相信调用者传来的 hash。 */
    public void verify(AnalysisScope scope, PreparedInputs prepared, JsonNode currentSource) {
        if (scope == null || prepared == null || prepared.getRule() == null || prepared.getInputs() == null || prepared.getInputs().isEmpty())
            fail("INVALID_INPUT", "待执行输入为空");
        VerifiedRuleDefinition current = registry.require(prepared.getRule().getIndicatorId(), currentSource);
        if (!RuleCanonicalJson.hash(current).equals(RuleCanonicalJson.hash(prepared.getRule()))
                || !fingerprint(scope, current, prepared.getInputs()).equals(prepared.getInputHash()))
            fail("INPUT_HASH_MISMATCH", "已装配输入或规则配置发生变化");
    }
    private String fingerprint(AnalysisScope scope, VerifiedRuleDefinition rule, List<RuleInput> inputs) {
        Map<String, Object> values = new TreeMap<>(); values.put("registryVersion", registry.version());
        values.put("rule", rule); values.put("scope", scope); values.put("inputs", inputs);
        return RuleCanonicalJson.hash(values);
    }
    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
    private static void fail(String code, String message) { throw new RuleContractException(code, message); }
    @Value
    public static class PreparedInputs {
        VerifiedRuleDefinition rule;
        List<RuleInput> inputs;
        String inputHash;
    }
}
