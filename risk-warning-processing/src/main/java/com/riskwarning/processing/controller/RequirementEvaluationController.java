package com.riskwarning.processing.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.result.Result;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.service.AnalysisRunSelector;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import com.riskwarning.common.dto.scoring.RequirementFinding;
import com.riskwarning.common.dto.scoring.ScoringDecision.Judgment;

/** 从同一次运行读取要求评分，支持按行为、指标和法规查看同一批明细。 */
@RestController
@RequestMapping("/analysis/scoring")
@RequiredArgsConstructor
public class RequirementEvaluationController {
    private final AssessmentRepository assessments;
    private final AnalysisRunSelector selector;
    private final RequirementEvaluationRepository evaluations;
    private final ScoringRunSnapshotRepository snapshots;
    private final RuleEvaluationAuditRepository audits;
    private final AnalysisResultRepository analysis;

    /** 复核阈值由调用方明确提供，只筛选人工复核清单，不参与任何分数计算。 */
    @AuthRequired
    @GetMapping("/{assessmentId}/review")
    public Result<Map<String, Object>> review(@PathVariable Long assessmentId, @RequestParam Long projectId,
            @RequestParam String analysisRunId, @RequestParam BigDecimal confidenceBelow) {
        if (confidenceBelow == null || confidenceBelow.signum() < 0 || confidenceBelow.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("复核置信度阈值应在0到1之间");
        Map<String, Object> data = list(assessmentId, projectId, analysisRunId, null, null, null).getData();
        Map<String, BigDecimal> confidences = new HashMap<>();
        for (AnalysisResult result : analysis.findByAssessmentIdAndAnalysisRunId(assessmentId, analysisRunId)) {
            if (result.getRequirementFindings() == null) continue;
            for (RequirementFinding finding : result.getRequirementFindings())
                confidences.put(reviewKey(result.getIndicatorId(), result.getBehaviorId(), finding.getRequirementId()), finding.getConfidence());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        @SuppressWarnings("unchecked") List<RequirementEvaluation> rows = (List<RequirementEvaluation>)data.get("evaluations");
        for (RequirementEvaluation row : rows) {
            BigDecimal confidence = confidences.get(reviewKey(row.getIndicatorId(), row.getBehaviorId(), row.getRequirementId()));
            List<String> reasons = new ArrayList<>();
            if (row.isConflict()) reasons.add("模型语义与规则判定不一致");
            if (row.getJudgment() == Judgment.NEEDS_REVIEW || row.getJudgment() == Judgment.INSUFFICIENT_EVIDENCE || row.getJudgment() == Judgment.INVALID_INPUT)
                reasons.add(row.getReason() == null ? "要求尚未完成判断" : row.getReason());
            if (row.getJudgment() == Judgment.VIOLATED && (confidence == null || confidence.compareTo(confidenceBelow) < 0))
                reasons.add(confidence == null ? "违规结论缺少置信度" : "违规结论低于本次复核置信度阈值");
            if (reasons.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>(); item.put("evaluation", row); item.put("confidence", confidence);
            item.put("reviewReasons", reasons); items.add(item);
        }
        data.put("reviewItems", items); data.put("reviewConfidenceBelow", confidenceBelow);
        return Result.success(data);
    }

    private static String reviewKey(String indicator, String behavior, String requirement) {
        return indicator + "|" + behavior + "|" + requirement;
    }

    @AuthRequired
    @GetMapping("/{assessmentId}")
    public Result<Map<String, Object>> list(@PathVariable Long assessmentId, @RequestParam Long projectId,
            @RequestParam(required = false) String analysisRunId, @RequestParam(required = false) String behaviorId,
            @RequestParam(required = false) String indicatorId, @RequestParam(required = false) String regulationId) {
        if (projectId == null || projectId <= 0 || assessmentId == null || assessmentId <= 0) throw new IllegalArgumentException("项目与评估身份无效");
        if (!projectId.equals(assessments.findById(assessmentId).orElseThrow(() -> new NoSuchElementException("评估不存在")).getProjectId()))
            throw new SecurityException("项目与评估不匹配");
        AnalysisRun run = selector.select(assessmentId, projectId, analysisRunId).getRun();
        Map<String, Object> out = new LinkedHashMap<>(); out.put("schemaVersion", "2.0");
        out.put("assessmentId", assessmentId); out.put("projectId", projectId);
        out.put("analysisRunId", run == null ? null : run.getAnalysisRunId());
        if (run == null) { out.put("evaluations", Collections.emptyList()); return Result.success(out); }
        List<RequirementEvaluation> rows = evaluations.findByProjectIdAndAssessmentIdAndAnalysisRunId(projectId, assessmentId, run.getAnalysisRunId());
        out.put("evaluations", rows.stream().filter(row -> behaviorId == null || behaviorId.equals(row.getBehaviorId()))
                .filter(row -> indicatorId == null || indicatorId.equals(row.getIndicatorId()))
                .filter(row -> regulationId == null || regulationId.equals(row.getRegulationId()))
                .sorted(Comparator.comparing(RequirementEvaluation::getIndicatorId).thenComparing(RequirementEvaluation::getBehaviorId).thenComparing(RequirementEvaluation::getRequirementId))
                .collect(Collectors.toList()));
        out.put("sourceBundles", snapshots.findById(run.getAnalysisRunId()).map(ScoringRunSnapshot::getSourceBundles).orElse(Collections.emptyMap()));
        Map<String, String> descriptions = new TreeMap<>();
        analysis.findByAssessmentIdAndAnalysisRunId(assessmentId, run.getAnalysisRunId()).forEach(row -> descriptions.putIfAbsent(row.getBehaviorId(), row.getEnterpriseFact()));
        out.put("behaviorDescriptions", descriptions);
        out.put("indicatorDecisions", audits.findByAssessmentIdAndAnalysisRunId(assessmentId, run.getAnalysisRunId()).stream()
                .filter(row -> row.getCalculationDetails() != null && row.getCalculationDetails().getScoringDecision() != null)
                .map(row -> row.getCalculationDetails().getScoringDecision()).collect(Collectors.toList()));
        return Result.success(out);
    }
}
