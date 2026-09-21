package com.riskwarning.common.observability;

import com.riskwarning.common.reliability.DurableWorkContext;

/** 一次链路事件可携带的稳定身份字段；缺失字段保持 null 并按占位输出。 */
public final class AssessmentFlowContext {

    private final Long projectId;
    private final Long assessmentId;
    private final String analysisRunId;
    private final String taskId;
    private final String kind;
    private final Integer attempt;
    private final String messageId;
    private final String traceId;

    private AssessmentFlowContext(Builder builder) {
        this.projectId = builder.projectId;
        this.assessmentId = builder.assessmentId;
        this.analysisRunId = builder.analysisRunId;
        this.taskId = builder.taskId;
        this.kind = builder.kind;
        this.attempt = builder.attempt;
        this.messageId = builder.messageId;
        this.traceId = builder.traceId;
    }

    /** Durable Work 固有身份：不解析业务 payload，任何任务都能提供。 */
    public static AssessmentFlowContext fromWork(DurableWorkContext work) {
        return new Builder()
                .taskId(work.getWorkId())
                .kind(work.getKind())
                .attempt(work.getAttempt())
                .messageId(work.getTaskKey())
                .build();
    }

    /** Durable Work 固有字段之上追加业务身份；缺失字段保持 null。 */
    public Builder toBuilder() {
        return new Builder()
                .projectId(projectId).assessmentId(assessmentId)
                .analysisRunId(analysisRunId).taskId(taskId).kind(kind)
                .attempt(attempt).messageId(messageId).traceId(traceId);
    }

    public Long getProjectId() { return projectId; }
    public Long getAssessmentId() { return assessmentId; }
    public String getAnalysisRunId() { return analysisRunId; }
    public String getTaskId() { return taskId; }
    public String getKind() { return kind; }
    public Integer getAttempt() { return attempt; }
    public String getMessageId() { return messageId; }
    public String getTraceId() { return traceId; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long projectId;
        private Long assessmentId;
        private String analysisRunId;
        private String taskId;
        private String kind;
        private Integer attempt;
        private String messageId;
        private String traceId;

        public Builder projectId(Long value) { this.projectId = value; return this; }
        public Builder assessmentId(Long value) { this.assessmentId = value; return this; }
        public Builder analysisRunId(String value) { this.analysisRunId = value; return this; }
        public Builder taskId(String value) { this.taskId = value; return this; }
        public Builder kind(String value) { this.kind = value; return this; }
        public Builder attempt(Integer value) { this.attempt = value; return this; }
        public Builder messageId(String value) { this.messageId = value; return this; }
        public Builder traceId(String value) { this.traceId = value; return this; }

        public AssessmentFlowContext build() { return new AssessmentFlowContext(this); }
    }
}
