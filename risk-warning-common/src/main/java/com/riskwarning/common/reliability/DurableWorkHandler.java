package com.riskwarning.common.reliability;

import com.riskwarning.common.observability.AssessmentFlowContext;

/** 每一种持久任务只对应一个 Handler。 */
public interface DurableWorkHandler {

    String kind();

    void execute(DurableWorkContext context, String payload) throws Exception;

    default int maxAttempts() {
        return 0;
    }

    default void onExhausted(DurableWorkContext context, String payload, Throwable failure) {
    }

    /**
     * 为 AssessmentFlow 日志提供业务身份；默认只返回 Durable Work 固有字段。
     * Handler 可用自身 codec 解析 payload，提取 projectId/analysisRunId/traceId 等；
     * 解析失败交给 Worker 降级处理，不得影响正常执行路径。
     */
    default AssessmentFlowContext flowContext(DurableWorkContext context, String payload) {
        return AssessmentFlowContext.fromWork(context);
    }
}
