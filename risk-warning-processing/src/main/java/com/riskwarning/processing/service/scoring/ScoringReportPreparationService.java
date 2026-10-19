package com.riskwarning.processing.service.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.message.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.processing.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** v2 报告准备使用要求汇总，不把部分要求的分数当作完整指标分。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class ScoringReportPreparationService {
    private final AnalysisRunRepository runs;
    private final ScoringRunSnapshotRepository snapshots;
    private final RuleEvaluationAuditRepository audits;
    private final IndicatorResultRepository indicators;
    private final RequirementEvaluationRepository evaluations;
    private final KafkaOutbox outbox;

    @Transactional
    public void prepare(IndicatorCalculationTaskMessage message, AnalysisScope scope, boolean failed) {
        AnalysisRun run = runs.lockScope(scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("评分运行不存在"));
        if (run.getStatus() != AnalysisRunStatus.RUNNING || run.getAnalysisSummary() != null) return;
        ScoringRunSnapshot snapshot = snapshots.findById(scope.getAnalysisRunId()).orElseThrow(() -> new IllegalStateException("评分依据未保存"));
        List<RuleEvaluationAudit> rules = audits.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId());
        List<IndicatorResult> results = indicators.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId());
        if (!failed && rules.size() != snapshot.getRuleDefinitions().size()) throw new IllegalStateException("要求评分尚未全部完成");
        long decided = rules.stream().filter(r -> r.getStatus() == RuleEvaluationStatus.DECIDED || r.getStatus() == RuleEvaluationStatus.DECIDED_WITH_CONFLICT).count();
        if (decided != results.size()) throw new IllegalStateException("有效评分与保存的指标结果数量不一致");
        Map<String, Object> summary = new TreeMap<>();
        summary.put("schemaVersion", "1.2"); summary.put("analysisMode", "REASONING_RULES_V2");
        summary.put("behaviorCount", run.getExpectedBehaviorIds().size()); summary.put("conclusionCount", evaluations.findByProjectIdAndAssessmentIdAndAnalysisRunId(scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId()).size());
        summary.put("decisionCount", decided); summary.put("needsReviewCount", rules.size() - decided);
        summary.put("conflictCount", rules.stream().filter(r -> Boolean.TRUE.equals(r.getConflict())).count());
        boolean full = decided > 0 && decided == rules.size() && rules.stream().noneMatch(r -> Boolean.TRUE.equals(r.getConflict()));
        Set<String> covered = new HashSet<>(); snapshot.getIndicatorBehaviors().values().forEach(covered::addAll);
        full &= covered.containsAll(run.getExpectedBehaviorIds());
        summary.put("completionKind", decided == 0 ? "NONE" : full ? "FULL" : "PARTIAL");
        summary.put("registryVersion", snapshot.getRegistryVersion()); summary.put("datasetVersion", snapshot.getDatasetVersion());
        run.prepareReport(new ObjectMapper().valueToTree(summary));
        if (!failed && decided > 0) {
            outbox.enqueue(new AssessmentCompletedEventMessage(StringUtils.deriveMessageId(scope.getAnalysisRunId(), "analysis-report-prepared"),
                    LocalDateTime.now().toString(), message.getTraceId(), message.getUserId(), scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId()));
        } else {
            if (failed) run.fail(LocalDateTime.now()); else run.completeWithoutDecision(LocalDateTime.now());
            NotificationMessage notification = new NotificationMessage(StringUtils.deriveMessageId(scope.getAnalysisRunId(), "analysis-terminal-notification"),
                    LocalDateTime.now().toString(), message.getTraceId(), message.getUserId(), scope.getProjectId(), scope.getAssessmentId(),
                    NotificationMessage.NotificationType.ASSESSMENT_COMPLETED, "分析状态通知", failed ? "本次要求评分失败" : "本次要求评分已结束，尚无有效指标最终分");
            notification.setAnalysisRunId(scope.getAnalysisRunId()); notification.setExtraData("{\"analysisStage\":\"REASONING\",\"displayStatus\":\"" + run.getStatus().name() + "\"}");
            outbox.enqueue(notification);
        }
        runs.saveAndFlush(run);
    }
}
