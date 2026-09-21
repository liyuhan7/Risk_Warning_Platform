package com.riskwarning.common.observability;

/** AssessmentFlow 日志事件状态；只表达观测事实，不替代业务状态。 */
public enum AssessmentFlowStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    RETRY,
    DUPLICATE,
    SKIPPED
}
