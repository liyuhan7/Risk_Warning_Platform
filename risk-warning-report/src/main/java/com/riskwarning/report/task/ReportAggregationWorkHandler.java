package com.riskwarning.report.task;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.common.reliability.DurableWorkHandler;
import com.riskwarning.report.service.AnalysisRunCompletionService;
import com.riskwarning.report.service.AnalysisRunFailureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 报告汇总持久任务：文档聚合、旧结果清理、运行成功与完成通知入箱
 * 全部交由完成服务在同一事务内执行，本 Handler 只负责解码与分发。
 * 业务异常直接外抛，由 Durable Work 重试；重试耗尽后才把 AnalysisRun
 * 标记为 FAILED，临时故障期间运行状态保持 RUNNING。
 * 同一任务重跑依赖稳定 messageId 与持久化幂等，不依赖只执行一次的假设。
 */
@Component
@Slf4j
public class ReportAggregationWorkHandler implements DurableWorkHandler {

    public static final String KIND = "REPORT_AGGREGATION";

    private final ReportMessageCodec codec;
    private final AnalysisRunCompletionService analysisRunCompletionService;
    private final AnalysisRunFailureService analysisRunFailureService;

    public ReportAggregationWorkHandler(ReportMessageCodec codec,
                                        AnalysisRunCompletionService analysisRunCompletionService,
                                        AnalysisRunFailureService analysisRunFailureService) {
        this.codec = codec;
        this.analysisRunCompletionService = analysisRunCompletionService;
        this.analysisRunFailureService = analysisRunFailureService;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void execute(DurableWorkContext context, String payload) {
        AssessmentCompletedEventMessage message = codec.decode(payload);
        AnalysisScope scope = new AnalysisScope(
                message.getProjectId(), message.getAssessmentId(), message.getAnalysisRunId());
        log.info("[Durable 报告汇总] workId={}, messageId={}, analysisRunId={}",
                context.getWorkId(), message.getMessageId(), scope.getAnalysisRunId());
        analysisRunCompletionService.aggregateAndComplete(message, scope);
    }

    /**
     * 重试耗尽后按载荷中的原始作用域终止分析运行；analysisRunId 全程不变。
     * 载荷损坏或标记动作失败只记录日志，不改变 Durable Work 已 FAILED 的事实。
     */
    @Override
    public void onExhausted(DurableWorkContext context, String payload, Throwable failure) {
        AnalysisScope scope = null;
        try {
            AssessmentCompletedEventMessage message = codec.decode(payload);
            scope = new AnalysisScope(message.getProjectId(),
                    message.getAssessmentId(), message.getAnalysisRunId());
        } catch (Exception decodeException) {
            log.error("[Durable 报告汇总耗尽] 载荷解析失败，跳过分析运行标记: workId={}",
                    context.getWorkId(), decodeException);
            return;
        }
        try {
            analysisRunFailureService.markFailed(scope, LocalDateTime.now());
        } catch (Exception markException) {
            log.error("[Durable 报告汇总耗尽] 标记分析运行失败异常: workId={}, analysisRunId={}",
                    context.getWorkId(), scope.getAnalysisRunId(), markException);
        }
        log.error("[Durable 报告汇总重试耗尽] workId={}, analysisRunId={}, projectId={}",
                context.getWorkId(), scope.getAnalysisRunId(), scope.getProjectId(), failure);
    }

    /** 从载荷恢复链路身份；解析失败由 Worker 降级到任务固有身份。 */
    @Override
    public AssessmentFlowContext flowContext(DurableWorkContext context, String payload) {
        AssessmentCompletedEventMessage message = codec.decode(payload);
        return AssessmentFlowContext.fromWork(context)
                .toBuilder()
                .projectId(message.getProjectId()).assessmentId(message.getAssessmentId())
                .analysisRunId(message.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId())
                .build();
    }
}
