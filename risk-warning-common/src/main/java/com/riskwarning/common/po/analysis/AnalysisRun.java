package com.riskwarning.common.po.analysis;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 独立分析运行记录。终态不可逆；版本号阻止并发更新覆盖已完成状态。
 * 同评估在途运行唯一性由 PostgreSQL 部分唯一索引保证。
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "t_analysis_run")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class AnalysisRun {

    @Id
    @Column(name = "analysis_run_id", length = 64, nullable = false, updatable = false)
    private String analysisRunId;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private Long assessmentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", length = 16, updatable = false)
    private AnalysisExecutionMode executionMode;

    @Type(type = "jsonb")
    @Column(name = "expected_behavior_ids", columnDefinition = "jsonb")
    @Getter(AccessLevel.NONE)
    private List<String> expectedBehaviorIds;

    @Column(name = "behavior_input_hash", length = 64)
    private String behaviorInputHash;

    /** 指纹覆盖参与分析的业务字段，输入变化不能沿用同一运行身份。 */
    public void freezeBehaviorInput(String inputHash) {
        if (status != AnalysisRunStatus.RUNNING || executionMode != AnalysisExecutionMode.REASONING || expectedBehaviorIds == null)
            throw new IllegalStateException("行为指纹要求进行中的已冻结合规推理运行");
        if (inputHash == null || !inputHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("行为指纹必须为 SHA-256");
        if (behaviorInputHash != null && !behaviorInputHash.equals(inputHash))
            throw new com.riskwarning.common.exception.AnalysisInputConflictException("同一运行的行为内容发生变化");
        behaviorInputHash = inputHash;
    }

    @Type(type = "jsonb")
    @Column(name = "analysis_summary", columnDefinition = "jsonb")
    @Getter(AccessLevel.NONE)
    private com.fasterxml.jackson.databind.JsonNode analysisSummary;

    /** 保存 Processing 完成准备的摘要；有效决定交给 Report 投影后结束运行。 */
    public void prepareReport(com.fasterxml.jackson.databind.JsonNode summary) {
        if (status != AnalysisRunStatus.RUNNING || executionMode != AnalysisExecutionMode.REASONING || expectedBehaviorIds == null)
            throw new IllegalStateException("仅已冻结输入的 Reasoning 运行可以准备报告");
        if (summary == null || !summary.isObject()) throw new IllegalArgumentException("报告摘要必须为对象");
        if (analysisSummary != null && !analysisSummary.equals(summary)) throw new IllegalStateException("报告准备摘要已冻结，不能覆盖");
        analysisSummary = summary.deepCopy();
    }

    public com.fasterxml.jackson.databind.JsonNode getAnalysisSummary() {
        return analysisSummary == null ? null : analysisSummary.deepCopy();
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
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
        return start(scope, startedAt, AnalysisExecutionMode.REASONING);
    }

    /** 创建具有明确分析身份的运行。 */
    public static AnalysisRun start(AnalysisScope scope, LocalDateTime startedAt,
                                    AnalysisExecutionMode executionMode) {
        if (scope == null || startedAt == null) {
            throw new IllegalArgumentException("运行作用域和开始时间不能为空");
        }
        AnalysisRun run = new AnalysisRun();
        run.analysisRunId = scope.getAnalysisRunId();
        run.assessmentId = scope.getAssessmentId();
        run.projectId = scope.getProjectId();
        run.status = AnalysisRunStatus.RUNNING;
        run.startedAt = startedAt;
        run.executionMode = executionMode;
        return run;
    }

    /** 在短事务持锁期间冻结清单；重放只能提交相同清单。 */
    public void freezeBehaviors(Collection<String> behaviorIds) {
        if (status != AnalysisRunStatus.RUNNING || executionMode != AnalysisExecutionMode.REASONING) {
            throw new IllegalStateException("仅进行中的 Reasoning 运行可以冻结行为清单");
        }
        if (behaviorIds == null || behaviorIds.isEmpty()) {
            throw new IllegalArgumentException("Reasoning 行为清单不能为空");
        }
        Set<String> unique = new TreeSet<>();
        for (String id : behaviorIds) {
            if (id == null || id.trim().isEmpty() || !unique.add(id)) {
                throw new IllegalArgumentException("行为身份不能为空或重复");
            }
        }
        List<String> frozen = new ArrayList<>(unique);
        if (expectedBehaviorIds != null && !expectedBehaviorIds.equals(frozen)) {
            throw new com.riskwarning.common.exception.AnalysisInputConflictException("运行行为清单已冻结，不能改变输入范围");
        }
        expectedBehaviorIds = frozen;
    }

    public List<String> getExpectedBehaviorIds() {
        return expectedBehaviorIds == null ? null
                : Collections.unmodifiableList(new ArrayList<>(expectedBehaviorIds));
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

    /** 业务输入不足时结束当前运行，但不发布或替换已有成功结果。 */
    public void completeWithoutDecision(LocalDateTime finishedAt) {
        finish(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, finishedAt);
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
