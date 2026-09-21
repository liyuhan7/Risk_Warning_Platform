package com.riskwarning.common.reliability;

/** Handler 本次执行的稳定身份与重试信息。 */
public final class DurableWorkContext {

    private final String workId;
    private final String taskKey;
    private final String namespace;
    private final String kind;
    private final int attempt;
    private final String workerId;

    public DurableWorkContext(DurableWork work) {
        this.workId = work.getId();
        this.taskKey = work.getTaskKey();
        this.namespace = work.getNamespace();
        this.kind = work.getKind();
        this.attempt = work.getAttempts();
        this.workerId = work.getWorkerId();
    }

    public String getWorkId() { return workId; }
    public String getTaskKey() { return taskKey; }
    public String getNamespace() { return namespace; }
    public String getKind() { return kind; }
    public int getAttempt() { return attempt; }
    public String getWorkerId() { return workerId; }
}
