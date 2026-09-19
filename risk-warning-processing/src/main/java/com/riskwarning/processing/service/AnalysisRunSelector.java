package com.riskwarning.processing.service;

import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;

/** 为分析只读接口统一选择指定运行或最近可用运行。 */
@Service
@RequiredArgsConstructor
public class AnalysisRunSelector {
    private final AnalysisRunRepository runs;

    /**
     * 显式指定时严格读取该运行；缺省时优先最近的成功终态运行，无可用运行才展示最新运行。
     */
    public Selection select(Long assessmentId, Long projectId, String analysisRunId) {
        if (hasText(analysisRunId)) {
            AnalysisRun selected = runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                    analysisRunId, assessmentId, projectId)
                    .orElseThrow(() -> new NoSuchElementException("指定运行不存在或不属于本次评估"));
            return new Selection(selected, false);
        }
        Optional<AnalysisRun> latest = runs
                .findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(assessmentId, projectId);
        if (!latest.isPresent() || isUsable(latest.get())) {
            return new Selection(latest.orElse(null), false);
        }
        Optional<AnalysisRun> usable = runs
                .findFirstByAssessmentIdAndProjectIdAndStatusInOrderByStartedAtDescAnalysisRunIdDesc(
                        assessmentId, projectId, Arrays.asList(
                                AnalysisRunStatus.SUCCEEDED, AnalysisRunStatus.COMPLETED_WITHOUT_DECISION));
        return usable.map(run -> new Selection(run, true))
                .orElseGet(() -> new Selection(latest.get(), false));
    }

    private static boolean isUsable(AnalysisRun run) {
        return run.getStatus() == AnalysisRunStatus.SUCCEEDED
                || run.getStatus() == AnalysisRunStatus.COMPLETED_WITHOUT_DECISION;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Getter
    public static final class Selection {
        private final AnalysisRun run;
        private final boolean fallbackToUsableRun;

        Selection(AnalysisRun run, boolean fallbackToUsableRun) {
            this.run = run;
            this.fallbackToUsableRun = fallbackToUsableRun;
        }
    }
}
