package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 仅接受创建时声明为合规分析的运行，不提供旧链路回退。 */
@Service
@RequiredArgsConstructor
public class AnalysisRunModeResolver {
    private final AnalysisRunRepository runs;

    public AnalysisExecutionMode resolve(AnalysisScope scope) {
        AnalysisRun run = runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("模式分发的运行不存在"));
        if (run.getExecutionMode() != AnalysisExecutionMode.REASONING)
            throw new IllegalStateException("当前运行不属于合规分析主链，不能执行或升级旧运行");
        return AnalysisExecutionMode.REASONING;
    }
}
