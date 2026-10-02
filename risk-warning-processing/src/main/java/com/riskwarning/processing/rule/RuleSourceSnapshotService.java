package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.analysis.RetrievalAuditStatus;
import com.riskwarning.common.po.analysis.RetrievalAudit;
import com.riskwarning.processing.repository.RetrievalAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 规则来源只从当前 Run 已提交的候选审计读取，不回查实时 ES 或接受前端规则。 */
@Service
@RequiredArgsConstructor
public class RuleSourceSnapshotService {
    private final RetrievalAuditRepository audits;
    private final VerifiedRuleRegistry registry;

    @Transactional(readOnly = true)
    public Map<String, JsonNode> sources(AnalysisScope scope) {
        if (scope == null) throw new RuleContractException("INVALID_CONTEXT", "规则来源作用域缺失");
        Map<String, JsonNode> sources = new TreeMap<>(); Set<String> behaviors = new HashSet<>();
        for (RetrievalAudit audit : audits.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId())) {
            if (audit == null || !scope.getProjectId().equals(audit.getProjectId()) || !scope.getAssessmentId().equals(audit.getAssessmentId())
                    || !scope.getAnalysisRunId().equals(audit.getAnalysisRunId()) || !text(audit.getBehaviorId()) || !behaviors.add(audit.getBehaviorId()))
                throw new RuleContractException("INVALID_CONTEXT", "候选审计跨作用域或重复");
            // 未成功回读的候选不能作为规则来源，也不能让缺快照变成模型调用失败。
            if (audit.getRetrievalStatus() != RetrievalAuditStatus.SUCCESS || audit.getCandidates() == null) continue;
            Set<String> indicatorIds = new HashSet<>();
            for (RetrievalCandidateSnapshot snapshot : audit.getCandidates()) {
                if (snapshot == null || snapshot.getResult() == null) throw new RuleContractException("INVALID_CONTEXT", "候选身份缺失");
                RetrievalResult result = snapshot.getResult();
                if (result.getCandidateType() != RetrievalCandidateType.INDICATOR) continue;
                if (!text(result.getCandidateId()) || !scope.getAssessmentId().equals(result.getAssessmentId())
                        || !scope.getAnalysisRunId().equals(result.getAnalysisRunId()) || !audit.getBehaviorId().equals(result.getBehaviorId())
                        || !indicatorIds.add(result.getCandidateId()))
                    throw new RuleContractException("INVALID_CONTEXT", "指标候选跨事实/运行或重复");
                JsonNode source = snapshot.getSourceRule();
                if (registry.isEnabled(result.getCandidateId())) registry.require(result.getCandidateId(), source);
                if (source == null) continue;
                if (!source.isObject() || !result.getCandidateId().equals(source.path("indicatorId").asText()))
                    throw new RuleContractException("INVALID_CONTEXT", "冻结来源与候选身份不一致");
                JsonNode previous = sources.get(result.getCandidateId());
                if (previous != null && !RuleCanonicalJson.hash(previous).equals(RuleCanonicalJson.hash(source)))
                    throw new RuleContractException("SOURCE_RULE_HASH_MISMATCH", "同 Run 的指标来源快照不一致");
                sources.put(result.getCandidateId(), source.deepCopy());
            }
        }
        return Collections.unmodifiableMap(sources);
    }
    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
}
