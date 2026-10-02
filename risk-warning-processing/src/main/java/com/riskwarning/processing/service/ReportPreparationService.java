package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.RuleEvaluationStatus;
import com.riskwarning.common.message.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.processing.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Processing 的持久化发布门禁；有效决定保持 RUNNING 等待 Report 完成投影。 */
@Service
@RequiredArgsConstructor
public class ReportPreparationService {
    private final AnalysisRunRepository runs;
    private final RetrievalAuditRepository retrievals;
    private final AnalysisResultRepository analysis;
    private final AnalysisAttemptRepository attempts;
    private final RuleEvaluationAuditRepository rules;
    private final IndicatorResultRepository indicators;
    private final AnalysisCompletionPolicy policy;
    private final KafkaOutbox outbox;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 摘要与 Outbox 同事务；持久化故障整体回滚，不发布中间结果。 */
    @Transactional
    public void prepare(IndicatorCalculationTaskMessage message, AnalysisScope scope, boolean failed) {
        if (message == null || scope == null || !scope.getProjectId().equals(message.getProjectId())
                || !scope.getAssessmentId().equals(message.getAssessmentId()) || !scope.getAnalysisRunId().equals(message.getAnalysisRunId()))
            throw new IllegalArgumentException("报告消息作用域不一致");
        AnalysisRun run = runs.lockScope(scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("报告准备的运行不存在"));
        if (run.getStatus() != AnalysisRunStatus.RUNNING) return;
        if (run.getExecutionMode() != AnalysisExecutionMode.REASONING || run.getExpectedBehaviorIds() == null)
            throw new IllegalStateException("报告准备要求已冻结输入的 Reasoning 运行");
        List<RuleEvaluationAudit> ruleRows = rules.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId());
        AnalysisCompletionPolicy.Completion completion = policy.resolve(scope, new HashSet<>(run.getExpectedBehaviorIds()),
                retrievals.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()),
                analysis.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()), ruleRows,
                attempts.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()), failed);
        validatePersisted(scope, ruleRows, indicators.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()));
        if (run.getAnalysisSummary() != null) {
            run.prepareReport(mapper.valueToTree(completion.getSummary()));
            return;
        }
        run.prepareReport(mapper.valueToTree(completion.getSummary()));
        if (completion.getStatus() == AnalysisRunStatus.SUCCEEDED) {
            outbox.enqueue(new AssessmentCompletedEventMessage(StringUtils.deriveMessageId(scope.getAnalysisRunId(), "analysis-report-prepared"),
                    LocalDateTime.now().toString(), message.getTraceId(), message.getUserId(), scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId()));
        } else {
            if (completion.getStatus() == AnalysisRunStatus.FAILED) run.fail(LocalDateTime.now());
            else run.completeWithoutDecision(LocalDateTime.now());
            NotificationMessage notification = new NotificationMessage(StringUtils.deriveMessageId(scope.getAnalysisRunId(), "analysis-terminal-notification"),
                    LocalDateTime.now().toString(), message.getTraceId(), message.getUserId(), scope.getProjectId(), scope.getAssessmentId(),
                    NotificationMessage.NotificationType.ASSESSMENT_COMPLETED, "分析状态通知",
                    completion.getStatus() == AnalysisRunStatus.FAILED ? "本次分析失败，请查看调用与检索审计。" : "本次分析已结束，未形成有效指标决定，请查看未决原因。");
            notification.setAnalysisRunId(scope.getAnalysisRunId());
            notification.setExtraData("{\"analysisStage\":\"REASONING\",\"displayStatus\":\"" + completion.getStatus().name() + "\"}");
            outbox.enqueue(notification);
        }
        runs.saveAndFlush(run);
    }

    private void validatePersisted(AnalysisScope scope, List<RuleEvaluationAudit> audits, List<IndicatorResult> results) {
        Map<String, RuleEvaluationAudit> decided = new HashMap<>();
        for (RuleEvaluationAudit audit : audits) if (audit.getStatus() == RuleEvaluationStatus.DECIDED
                || audit.getStatus() == RuleEvaluationStatus.DECIDED_WITH_CONFLICT) decided.put(audit.getIndicatorId(), audit);
        Set<String> seen = new HashSet<>();
        for (IndicatorResult result : results) {
            RuleEvaluationAudit audit = result == null ? null : decided.get(result.getIndicatorEsId());
            if (audit == null || !seen.add(result.getIndicatorEsId()) || !scope.getProjectId().equals(result.getProjectId())
                    || !scope.getAssessmentId().equals(result.getAssessmentId()) || !scope.getAnalysisRunId().equals(result.getAnalysisRunId())
                    || !Objects.equals(result.getCalculatedScore(), audit.getCalculationDetails().getRuleCalculation().getFinalDecision().getCalculatedScore())
                    || !Objects.equals(result.getMaxPossibleScore(), audit.getCalculationDetails().getRuleCalculation().getFinalDecision().getMaxPossibleScore())
                    || !Objects.equals(result.getRiskTriggered(), audit.getCalculationDetails().getRuleCalculation().getFinalDecision().getRiskTriggered())
                    || result.getRiskLevel() != audit.getCalculationDetails().getRuleCalculation().getFinalDecision().getRiskLevel()
                    || !com.riskwarning.processing.rule.RuleCanonicalJson.hash(result.getCalculationDetails())
                    .equals(com.riskwarning.processing.rule.RuleCanonicalJson.hash(audit.getCalculationDetails())))
                throw new IllegalStateException("报告准备的指标结果与权威规则审计不一致");
        }
        if (!seen.equals(decided.keySet())) throw new IllegalStateException("报告准备缺少已决定指标的持久化结果");
    }
}
