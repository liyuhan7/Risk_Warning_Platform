package com.riskwarning.common.enums.analysis;

public enum AnalysisAuditStatus {
    NOT_ATTEMPTED,
    /** 仅用于读取历史演示运行；真实检索链不再写入。 */
    SUCCESS,
    RECALL_GAP,
    INSUFFICIENT_EVIDENCE,
    /** 仅用于读取历史非白名单演示输入；真实检索链不再写入。 */
    NOT_DEMO_INPUT,
    WAITING_P3
}
