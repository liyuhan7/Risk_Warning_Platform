package com.riskwarning.common.exception;

/** 同运行重放输入变化时拒绝覆盖，调用方应结束当前运行并保留既有结果。 */
public class AnalysisInputConflictException extends IllegalStateException {
    public AnalysisInputConflictException(String message) { super(message); }
}
