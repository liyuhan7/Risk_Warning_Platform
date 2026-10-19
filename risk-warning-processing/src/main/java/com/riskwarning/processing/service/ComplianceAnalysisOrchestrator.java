package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.provider.LlmProviderException;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.rule.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 新 Reasoning 运行的完整 Processing 链，不跨外部调用持有数据库事务。 */
@Service
@RequiredArgsConstructor
public class ComplianceAnalysisOrchestrator {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.riskwarning.processing.service.scoring.ScoringOrchestrator scoring;
    private final AnalysisRunRepository runs;
    private final BehaviorDocumentRepository behaviors;
    private final AnalysisRunIdentityService identity;
    private final RetrievalProcessingService retrieval;
    private final RetrievalAuditRepository audits;
    private final AnalysisResultRepository results;
    private final AnalysisAttemptRepository attempts;
    private final AnalysisContextService contexts;
    private final RegisteredComplianceAnalysisService analysis;
    private final RuleSourceSnapshotService sources;
    private final ComplianceAuditWriter auditWriter;
    private final IndicatorEvaluationService evaluation;
    private final ReportPreparationService preparation;

    /** 终态与已准备报告的运行重投跳过；只处理创建时声明 Reasoning 的三重作用域。 */
    public void process(IndicatorCalculationTaskMessage message, AnalysisScope scope) {
        if (message == null || scope == null || !scope.getProjectId().equals(message.getProjectId())
                || !scope.getAssessmentId().equals(message.getAssessmentId()) || !scope.getAnalysisRunId().equals(message.getAnalysisRunId()))
            throw new IllegalArgumentException("Reasoning 消息与运行作用域不一致");
        AnalysisRun run = runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("Reasoning 运行不存在"));
        if (run.getStatus() != AnalysisRunStatus.RUNNING || run.getAnalysisSummary() != null) return;
        if (run.getExecutionMode() != AnalysisExecutionMode.REASONING) throw new IllegalStateException("不能将历史或其他模式运行升级为 Reasoning");
        List<Behavior> scoped = behaviors.findByScope(scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId());
        try {
            if (!identity.freeze(scope, scoped)) return;
        } catch (com.riskwarning.common.exception.AnalysisInputConflictException conflict) {
            preparation.prepare(message, scope, true); return;
        }
        if (attempts.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()).stream()
                .anyMatch(attempt -> attempt.getStatus() == AnalysisAttemptStatus.PROVIDER_FAILED)) {
            preparation.prepare(message, scope, true); return;
        }
        try {
            retrieval.retrieveForAnalysis(message, scope);
        } catch (RuntimeException failure) {
            if (audits.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()).stream()
                    .anyMatch(audit -> audit.getRetrievalStatus() == RetrievalAuditStatus.FAILED)) {
                preparation.prepare(message, scope, true); return;
            }
            throw failure;
        }
        if (scoring != null) {
            scoring.process(message, scope, scoped);
            return;
        }
        Map<String, JsonNode> ruleSources;
        try {
            ruleSources = sources.sources(scope);
            AnalysisContextVO context = contexts.context(scope.getAssessmentId(), scope.getProjectId(), scope.getAnalysisRunId());
            if (!scope.getProjectId().equals(context.getProjectId()) || !scope.getAssessmentId().equals(context.getAssessmentId())
                    || !scope.getAnalysisRunId().equals(context.getAnalysisRunId()) || context.getItems() == null)
                throw new IllegalStateException("Reasoning 上下文作用域不一致");
            Map<String, BehaviorAnalysisContextVO> byBehavior = new HashMap<>();
            for (BehaviorAnalysisContextVO item : context.getItems())
                if (item == null || byBehavior.put(item.getBehaviorId(), item) != null) throw new IllegalStateException("Reasoning 上下文事实重复");
            Set<String> expected = new HashSet<>();
            for (Behavior behavior : scoped) expected.add(behavior.getId());
            if (!byBehavior.keySet().equals(expected)) throw new IllegalStateException("Reasoning 上下文与冻结行为清单不一致");
            List<String> ordered = new ArrayList<>(expected); Collections.sort(ordered);
            for (String id : ordered) {
                BehaviorAnalysisContextVO item = byBehavior.get(id);
                if (!AnalysisAuditStatus.WAITING_ANALYSIS.name().equals(item.getAnalysisStatus())) continue;
                if (item.isReadyForAnalysis()) analysis.analyze(scope, id, ruleSources, Collections.emptyMap());
                else auditWriter.complete(scope, id, AnalysisAuditStatus.INSUFFICIENT_EVIDENCE, null,
                        "ANALYSIS_CONTEXT_NOT_READY", item.getBlockedReason());
            }
            evaluation.evaluate(scope, scoped, results.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()),
                    audits.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()), ruleSources);
        } catch (LlmProviderException | RuleContractException | com.riskwarning.common.exception.AnalysisInputConflictException failure) {
            preparation.prepare(message, scope, true); return;
        }
        preparation.prepare(message, scope, false);
    }
}
