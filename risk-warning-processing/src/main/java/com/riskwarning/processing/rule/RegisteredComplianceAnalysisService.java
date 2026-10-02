package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.processing.analysis.*;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.service.*;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.util.*;

/** 审核注册表到模型输入的适配；只使用当前运行已冻结的来源快照。 */
@Service
@RequiredArgsConstructor
public class RegisteredComplianceAnalysisService {
    private final VerifiedRuleRegistry registry;
    private final AnalysisContextService contexts;
    private final ComplianceAnalysisService analysis;
    private final RuleSourceSnapshotService sources;

    /** 生产编排只从当前 Run 的已提交候选审计读取来源。 */
    public ComplianceAnalysisOutcome analyze(AnalysisScope scope, String behaviorId, Map<String, String> background) {
        return analyze(scope, behaviorId, sources.sources(scope), background);
    }

    public ComplianceAnalysisOutcome analyze(AnalysisScope scope, String behaviorId, Map<String, JsonNode> currentSources,
                                             Map<String, String> background) {
        AnalysisContextVO context = contexts.context(scope.getAssessmentId(), scope.getProjectId(), scope.getAnalysisRunId());
        if (!scope.getAnalysisRunId().equals(context.getAnalysisRunId()) || !scope.getProjectId().equals(context.getProjectId())
                || !scope.getAssessmentId().equals(context.getAssessmentId()) || context.getItems() == null)
            throw new RuleContractException("INVALID_CONTEXT", "规则输入要求上下文作用域不匹配");
        List<BehaviorAnalysisContextVO> matched = new ArrayList<>();
        for (BehaviorAnalysisContextVO item : context.getItems()) if (behaviorId.equals(item.getBehaviorId())) matched.add(item);
        if (matched.size() != 1) throw new RuleContractException("INVALID_CONTEXT", "目标事实不存在或不唯一");
        Map<String, RuleInputRequirement> requirements = new TreeMap<>();
        if (matched.get(0).getIndicatorCandidates() != null) for (RetrievalCandidateVO candidate : matched.get(0).getIndicatorCandidates()) {
            if (!registry.isEnabled(candidate.getCandidateId())) continue;
            VerifiedRuleDefinition rule = registry.require(candidate.getCandidateId(), currentSources == null ? null : currentSources.get(candidate.getCandidateId()));
            requirements.put(candidate.getCandidateId(), RuleInputRequirement.builder().ruleType(rule.getRuleType().name())
                    .inputUnit(rule.getInputUnit()).condition(rule.getCondition()).observation(rule.getObservation()).build());
        }
        return analysis.analyze(scope, behaviorId, requirements, background);
    }
}
