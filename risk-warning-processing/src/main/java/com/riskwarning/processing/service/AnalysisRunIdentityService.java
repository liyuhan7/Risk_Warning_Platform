package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 固定 Reasoning 输入范围；ES 读取及模型请求均在此短事务之外执行。 */
@Service
@RequiredArgsConstructor
public class AnalysisRunIdentityService {
    private final AnalysisRunRepository runs;

    /** 已结束的运行重投返回 false，历史身份或跨作用域输入直接拒绝。 */
    @Transactional
    public boolean freeze(AnalysisScope scope, List<Behavior> behaviors) {
        Objects.requireNonNull(scope, "运行作用域不能为空");
        AnalysisRun run = runs.lockScope(scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("运行作用域不存在"));
        if (run.getStatus() != AnalysisRunStatus.RUNNING) return false;
        if (run.getExecutionMode() != AnalysisExecutionMode.REASONING)
            throw new IllegalStateException("运行创建时未声明 Reasoning，不能续算或升级历史运行");
        if (behaviors == null) throw new IllegalArgumentException("行为清单不能为空");
        List<String> ids = new ArrayList<>();
        for (Behavior behavior : behaviors) {
            if (behavior == null || !scope.getProjectId().equals(behavior.getProjectId())
                    || !scope.getAssessmentId().equals(behavior.getAssessmentId())
                    || !scope.getAnalysisRunId().equals(behavior.getAnalysisRunId()))
                throw new IllegalArgumentException("行为作用域与运行不一致");
            ids.add(behavior.getId());
        }
        run.freezeBehaviors(ids);
        run.freezeBehaviorInput(com.riskwarning.processing.analysis.BehaviorSnapshotFingerprint.hash(scope, behaviors));
        runs.saveAndFlush(run);
        return true;
    }
}
