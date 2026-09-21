package com.riskwarning.common.reliability;

import java.time.LocalDateTime;

/** 已获得 lease 的持久任务快照。 */
public final class DurableWork {

    private final String id;
    private final String namespace;
    private final String kind;
    private final String taskKey;
    private final String payload;
    private final int attempts;
    private final String workerId;
    private final String leaseToken;
    private final LocalDateTime leaseUntil;

    public DurableWork(String id, String namespace, String kind, String taskKey,
                       String payload, int attempts, String workerId, String leaseToken,
                       LocalDateTime leaseUntil) {
        this.id = id;
        this.namespace = namespace;
        this.kind = kind;
        this.taskKey = taskKey;
        this.payload = payload;
        this.attempts = attempts;
        this.workerId = workerId;
        this.leaseToken = leaseToken;
        this.leaseUntil = leaseUntil;
    }

    public String getId() { return id; }
    public String getNamespace() { return namespace; }
    public String getKind() { return kind; }
    public String getTaskKey() { return taskKey; }
    public String getPayload() { return payload; }
    public int getAttempts() { return attempts; }
    public String getWorkerId() { return workerId; }
    public String getLeaseToken() { return leaseToken; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
}
