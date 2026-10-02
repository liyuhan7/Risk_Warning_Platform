package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.processing.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

/** 分开保存调用审计和业务结果，外层运行失败时仍保留每次调用的证据。 */
@Service
@RequiredArgsConstructor
public class ComplianceAuditWriter {
    private final AnalysisAttemptRepository attempts;
    private final AnalysisResultRepository results;
    private final RetrievalAuditRepository audits;
    private final AnalysisRunRepository runs;

    /** 调用序号在短事务 Run 锁内分配；业务消息重投保护由编排层完成。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attempt(AnalysisScope scope, String behaviorId, String contextHash, String model, String prompt,
                        String raw, AnalysisAttemptStatus status, List<String> errors, String errorCode, String message) {
        requireWritable(scope);
        int no = attempts.findFirstByAnalysisRunIdAndBehaviorIdOrderByAttemptNoDesc(scope.getAnalysisRunId(), behaviorId)
                .map(a -> a.getAttemptNo() + 1).orElse(1);
        attempts.saveAndFlush(AnalysisAttempt.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId()).behaviorId(behaviorId).attemptNo(no).contextHash(contextHash)
                .modelVersion(model).promptVersion(prompt).rawResponse(raw).status(status).validationErrors(errors)
                .errorCode(errorCode).errorMessage(message).createdAt(LocalDateTime.now()).build());
    }

    /** 结果与检索审计分析状态在同一短事务提交，不持有模型调用期间的数据库事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(AnalysisScope scope, String behaviorId, AnalysisAuditStatus status,
                         AnalysisResult result, String errorCode, String message) {
        requireWritable(scope);
        RetrievalAudit audit = audits.findByAnalysisRunIdAndBehaviorId(scope.getAnalysisRunId(), behaviorId)
                .orElseThrow(() -> new IllegalStateException("当前事实的检索审计不存在"));
        if (!scope.getProjectId().equals(audit.getProjectId()) || !scope.getAssessmentId().equals(audit.getAssessmentId())
                || !scope.getAnalysisRunId().equals(audit.getAnalysisRunId()) || !behaviorId.equals(audit.getBehaviorId()))
            throw new IllegalStateException("检索审计作用域不匹配");
        if (audit.getAnalysisStatus() != AnalysisAuditStatus.WAITING_ANALYSIS) {
            if (audit.getAnalysisStatus() == status && result == null && status != AnalysisAuditStatus.ANALYZED) return;
            if (audit.getAnalysisStatus() == status && result != null) {
                java.util.Optional<AnalysisResult> existing = results.findByAnalysisRunIdAndBehaviorIdAndIndicatorId(
                        scope.getAnalysisRunId(), behaviorId, result.getIndicatorId());
                if (existing.isPresent() && semanticHash(existing.get()).equals(semanticHash(result))) return;
            }
            throw new com.riskwarning.common.exception.AnalysisInputConflictException("事实分析已完成，不能用不同响应覆盖");
        }
        if (result != null) {
            if (!scope.getAnalysisRunId().equals(result.getAnalysisRunId()) || !scope.getAssessmentId().equals(result.getAssessmentId())
                    || !behaviorId.equals(result.getBehaviorId())) throw new IllegalStateException("结果作用域不匹配");
            results.saveAndFlush(result);
        }
        audit.setAnalysisStatus(status); audit.setErrorCode(errorCode);
        audit.setErrorType(errorCode == null ? null : "COMPLIANCE_VALIDATION");
        audit.setErrorMessage(message == null ? null : message.substring(0, Math.min(1000, message.length())));
        audit.setUpdatedAt(LocalDateTime.now());
        audits.saveAndFlush(audit);
    }

    private void requireWritable(AnalysisScope scope) {
        AnalysisRun run = runs.lockScope(scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("分析写入的运行作用域不存在"));
        if (run.getStatus() != com.riskwarning.common.enums.AnalysisRunStatus.RUNNING || run.getAnalysisSummary() != null)
            throw new IllegalStateException("已结束或已准备报告的运行不能继续写入分析");
    }

    private String semanticHash(AnalysisResult result) {
        com.fasterxml.jackson.databind.node.ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules().valueToTree(result);
        node.remove("analyzedAt");
        return com.riskwarning.processing.rule.RuleCanonicalJson.hash(node);
    }
}
