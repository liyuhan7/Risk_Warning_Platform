package com.riskwarning.processing.task;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.common.reliability.DurableWorkHandler;
import com.riskwarning.processing.config.P2ProcessingProperties;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.BehaviorProcessingService;
import com.riskwarning.processing.service.P2RetrievalProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 指标计算持久任务：按运行模式分发到 P2 检索链或 LEGACY 行为评估链。
 * 检索超时、外部服务不可用等临时异常直接外抛，由 Durable Work 重试，
 * AnalysisRun 在临时故障期间保持 RUNNING；重试耗尽后才标记为 FAILED。
 * LEGACY 链传入原始 indicator messageId，保证完成事件 ID 稳定可去重。
 */
@Component
@Slf4j
public class IndicatorCalculationWorkHandler implements DurableWorkHandler {

    public static final String KIND = "INDICATOR_CALCULATION";

    private final ProcessingMessageCodec codec;
    private final P2RetrievalProcessingService p2RetrievalProcessingService;
    private final BehaviorProcessingService behaviorProcessingService;
    private final P2ProcessingProperties p2Properties;
    private final AnalysisRunFailureService analysisRunFailureService;

    public IndicatorCalculationWorkHandler(ProcessingMessageCodec codec,
                                           P2RetrievalProcessingService p2RetrievalProcessingService,
                                           BehaviorProcessingService behaviorProcessingService,
                                           P2ProcessingProperties p2Properties,
                                           AnalysisRunFailureService analysisRunFailureService) {
        this.codec = codec;
        this.p2RetrievalProcessingService = p2RetrievalProcessingService;
        this.behaviorProcessingService = behaviorProcessingService;
        this.p2Properties = p2Properties;
        this.analysisRunFailureService = analysisRunFailureService;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void execute(DurableWorkContext context, String payload) {
        IndicatorCalculationTaskMessage message = codec.decodeIndicator(payload);
        AnalysisScope analysisScope = MessageTask.requireScope(message);
        log.info("[Durable 指标计算] workId={}, messageId={}, projectId={}, mode={}",
                context.getWorkId(), message.getMessageId(),
                message.getProjectId(), p2Properties.getMode());
        if (p2Properties.getMode() == P2ProcessingProperties.Mode.P2) {
            p2RetrievalProcessingService.process(message, analysisScope);
        } else {
            behaviorProcessingService.processProjectBehaviors(
                    message.getUserId(), message.getProjectId(), message.getAssessmentId(),
                    analysisScope.getAnalysisRunId(), message.getMessageId());
        }
    }

    /**
     * 重试耗尽后按载荷中的原始作用域终止分析运行；P2 与 LEGACY 共用同一规则，
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
                p2Properties.getMode(), failure);
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
