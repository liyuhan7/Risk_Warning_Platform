package com.riskwarning.common.observability;

/** 异步分析链路标准阶段；日志聚合依赖固定枚举，禁止自由字符串拼写。 */
public enum AssessmentFlowStage {
    UPLOAD_QUEUE,
    UPLOAD_CLAIM,
    UPLOAD_HANDOFF,

    DURABLE_CLAIM,
    DURABLE_RETRY,
    DURABLE_DONE,
    DURABLE_FAILED,

    ANALYSIS_RUN_START,

    KAFKA_OUTBOX,
    KAFKA_SEND,

    BEHAVIOR_INBOX,
    DOCUMENT_EXTRACT,
    EVIDENCE_PERSIST,
    FACT_EXTRACT,
    BEHAVIOR_PERSIST,

    INDICATOR_INBOX,

    RETRIEVAL_BATCH,
    RETRIEVAL_AUDIT,

    P2_COMPLETE,
    REPORT_AGGREGATE,
    REPORT_COMPLETE
}
