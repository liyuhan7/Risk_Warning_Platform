package com.riskwarning.processing.task;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.common.reliability.DurableWorkHandler;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.AnalysisRunModeResolver;
import com.riskwarning.processing.service.ComplianceAnalysisOrchestrator;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 指标计算持久任务：仅进入合规分析主链。
 * 检索超时、外部服务不可用等临时异常直接外抛，由 Durable Work 重试，
 * AnalysisRun 在临时故障期间保持 RUNNING；重试耗尽后才标记为 FAILED。
 * 消息身份保持稳定，运行作用域全程不变。
 */
@Component
@Slf4j
public class IndicatorCalculationWorkHandler implements DurableWorkHandler {

    public static final String KIND = "INDICATOR_CALCULATION";

    private final ProcessingMessageCodec codec;
    private final AnalysisRunFailureService analysisRunFailureService;
    private final AnalysisRunModeResolver runModes;
    private final ComplianceAnalysisOrchestrator analysis;

    public IndicatorCalculationWorkHandler(ProcessingMessageCodec codec,
                                           AnalysisRunFailureService analysisRunFailureService,
                                           AnalysisRunModeResolver runModes,
                                           ComplianceAnalysisOrchestrator analysis) {
        this.codec = codec;
        this.analysisRunFailureService = analysisRunFailureService;
        this.runModes = runModes;
        this.analysis = analysis;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void execute(DurableWorkContext context, String payload) {
        IndicatorCalculationTaskMessage message = codec.decodeIndicator(payload);
        AnalysisScope analysisScope = MessageTask.requireScope(message);
        AnalysisExecutionMode mode = runModes.resolve(analysisScope);
        log.info("[Durable 指标计算] workId={}, messageId={}, projectId={}, mode={}",
                context.getWorkId(), message.getMessageId(),
                message.getProjectId(), mode);
        analysis.process(message, analysisScope);
    }

    /**
     * 重试耗尽后按载荷中的原始作用域终止分析运行；仅终止当前主链的运行，
     * analysisRunId 全程不变。载荷损坏或标记动作失败只记录日志，
     * 不改变 Durable Work 已 FAILED 的事实。
     */
    @Override
    public void onExhausted(DurableWorkContext context, String payload, Throwable failure) {
        AnalysisScope scope = null;
        try {
            scope = MessageTask.requireScope(codec.decodeIndicator(payload));
        } catch (Exception decodeException) {
            log.error("[Durable 指标计算耗尽] 载荷解析失败，跳过分析运行标记: workId={}",
                    context.getWorkId(), decodeException);
            return;
        }
        try {
            analysisRunFailureService.markFailed(scope, java.time.LocalDateTime.now());
        } catch (Exception markException) {
            log.error("[Durable 指标计算耗尽] 标记分析运行失败异常: workId={}, analysisRunId={}",
                    context.getWorkId(), scope.getAnalysisRunId(), markException);
        }
        log.error("[Durable 指标计算重试耗尽] workId={}, analysisRunId={}, projectId={}, mode={}",
                context.getWorkId(), scope.getAnalysisRunId(), scope.getProjectId(),
                AnalysisExecutionMode.REASONING, failure);
    }

    /** 从载荷恢复链路身份；解析失败由 Worker 降级到任务固有身份。 */
    @Override
    public AssessmentFlowContext flowContext(DurableWorkContext context, String payload) {
        IndicatorCalculationTaskMessage message = codec.decodeIndicator(payload);
        return AssessmentFlowContext.fromWork(context)
                .toBuilder()
                .projectId(message.getProjectId()).assessmentId(message.getAssessmentId())
                .analysisRunId(message.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId())
                .build();
    }
}
