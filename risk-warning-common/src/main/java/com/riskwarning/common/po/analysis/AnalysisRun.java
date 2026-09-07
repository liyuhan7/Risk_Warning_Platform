package com.riskwarning.common.po.analysis;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 独立分析运行记录。终态不可逆；版本号阻止并发更新覆盖已完成状态。
 * 同评估在途运行唯一性由 PostgreSQL 部分唯一索引保证。
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "t_analysis_run")
public class AnalysisRun {

    @Id
    @Column(name = "analysis_run_id", length = 64, nullable = false, updatable = false)
    private String analysisRunId;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private Long assessmentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private AnalysisRunStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 创建运行；调用方必须先校验项目、评估和文档归属再持久化。 */
    public static AnalysisRun start(AnalysisScope scope, LocalDateTime startedAt) {
        if (scope == null || startedAt == null) {
            throw new IllegalArgumentException("运行作用域和开始时间不能为空");
        }
        AnalysisRun run = new AnalysisRun();
        run.analysisRunId = scope.getAnalysisRunId();
        run.assessmentId = scope.getAssessmentId();
        run.projectId = scope.getProjectId();
        run.status = AnalysisRunStatus.RUNNING;
        run.startedAt = startedAt;
        return run;
    }

    public AnalysisScope toScope() {
        return new AnalysisScope(projectId, assessmentId, analysisRunId);
    }

    /** 仅在结果完整性校验通过后调用；此方法本身不发布或清理结果。 */
    public void succeed(LocalDateTime finishedAt) {
        finish(AnalysisRunStatus.SUCCEEDED, finishedAt);
    }

    /** 标记本次运行失败，不触碰同评估的既有成功结果。 */
    public void fail(LocalDateTime finishedAt) {
        finish(AnalysisRunStatus.FAILED, finishedAt);
    }

    private void finish(AnalysisRunStatus target, LocalDateTime finishedAt) {
        if (status != AnalysisRunStatus.RUNNING) {
            throw new IllegalStateException("已结束的运行不能再次变更状态");
        }
        if (finishedAt == null || finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("结束时间不能为空或早于开始时间");
        }
        this.status = target;
        this.finishedAt = finishedAt;
    }
}
