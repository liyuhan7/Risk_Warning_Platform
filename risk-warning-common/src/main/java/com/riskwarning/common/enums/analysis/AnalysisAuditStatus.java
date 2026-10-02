package com.riskwarning.common.enums.analysis;

public enum AnalysisAuditStatus {
    NOT_ATTEMPTED,
    /** 仅用于读取历史演示运行；真实检索链不再写入。 */
    SUCCESS,
    RECALL_GAP,
    INSUFFICIENT_EVIDENCE,
    /** 仅用于读取历史非白名单演示输入；真实检索链不再写入。 */
    NOT_DEMO_INPUT,
    WAITING_ANALYSIS,
    /** 合法语义结果已保存，不代表规则已形成指标决策。 */
    ANALYZED,
    NO_APPLICABLE_CANDIDATE,
    NEEDS_REVIEW
}
