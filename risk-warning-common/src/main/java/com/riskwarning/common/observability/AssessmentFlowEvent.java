package com.riskwarning.common.observability;

/** 单条 AssessmentFlow 日志事件；build 时强制 stage/status 并校验 elapsedMs。 */
public final class AssessmentFlowEvent {

    private final AssessmentFlowStage stage;
    private final AssessmentFlowStatus status;
    private final Long projectId;
    private final Long assessmentId;
    private final String analysisRunId;
    private final String taskId;
    private final String kind;
    private final Integer attempt;
    private final String messageId;
    private final String traceId;
    private final String behaviorId;
    private final Long elapsedMs;
    private final Throwable failure;

    private AssessmentFlowEvent(Builder builder) {
        this.stage = builder.stage;
        this.status = builder.status;
        this.projectId = builder.projectId;
        this.assessmentId = builder.assessmentId;
        this.analysisRunId = builder.analysisRunId;
        this.taskId = builder.taskId;
        this.kind = builder.kind;
        this.attempt = builder.attempt;
        this.messageId = builder.messageId;
        this.traceId = builder.traceId;
        this.behaviorId = builder.behaviorId;
        this.elapsedMs = builder.elapsedMs;
        this.failure = builder.failure;
    }

    /** 在既有事件身份之上派生不同状态的事件，避免调用方重复拼装字段。 */
    public Builder toBuilder() {
        return new Builder(stage, status)
                .projectId(projectId).assessmentId(assessmentId)
                .analysisRunId(analysisRunId).taskId(taskId).kind(kind)
                .attempt(attempt).messageId(messageId).traceId(traceId)
                .behaviorId(behaviorId).elapsedMs(elapsedMs).failure(failure);
    }

    public AssessmentFlowStage getStage() { return stage; }
    public AssessmentFlowStatus getStatus() { return status; }
    public Long getProjectId() { return projectId; }
    public Long getAssessmentId() { return assessmentId; }
    public String getAnalysisRunId() { return analysisRunId; }
    public String getTaskId() { return taskId; }
    public String getKind() { return kind; }
    public Integer getAttempt() { return attempt; }
    public String getMessageId() { return messageId; }
    public String getTraceId() { return traceId; }
    public String getBehaviorId() { return behaviorId; }
    public Long getElapsedMs() { return elapsedMs; }
    public Throwable getFailure() { return failure; }

    public static Builder builder(AssessmentFlowStage stage, AssessmentFlowStatus status) {
        return new Builder(stage, status);
    }

    public static final class Builder {
        private AssessmentFlowStage stage;
        private AssessmentFlowStatus status;
        private Long projectId;
        private Long assessmentId;
        private String analysisRunId;
        private String taskId;
        private String kind;
        private Integer attempt;
        private String messageId;
        private String traceId;
        private String behaviorId;
        private Long elapsedMs;
        private Throwable failure;

        private Builder(AssessmentFlowStage stage, AssessmentFlowStatus status) {
            this.stage = stage;
            this.status = status;
        }

        public Builder projectId(Long value) { this.projectId = value; return this; }
        public Builder status(AssessmentFlowStatus value) { this.status = value; return this; }
        public Builder assessmentId(Long value) { this.assessmentId = value; return this; }
        public Builder analysisRunId(String value) { this.analysisRunId = value; return this; }
        public Builder taskId(String value) { this.taskId = value; return this; }
        public Builder kind(String value) { this.kind = value; return this; }
        public Builder attempt(Integer value) { this.attempt = value; return this; }
        public Builder messageId(String value) { this.messageId = value; return this; }
        public Builder traceId(String value) { this.traceId = value; return this; }
        public Builder behaviorId(String value) { this.behaviorId = value; return this; }
        public Builder elapsedMs(Long value) { this.elapsedMs = value; return this; }
        public Builder failure(Throwable value) { this.failure = value; return this; }

        public AssessmentFlowEvent build() {
            if (stage == null || status == null) {
                throw new IllegalArgumentException("AssessmentFlow 事件的 stage 和 status 不能为空");
            }
            if (elapsedMs != null && elapsedMs < 0) {
                throw new IllegalArgumentException("AssessmentFlow 事件的 elapsedMs 不能为负数");
            }
            return new AssessmentFlowEvent(this);
        }
    }
}
