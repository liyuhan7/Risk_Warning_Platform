package com.riskwarning.common.reliability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "assessment.reliability")
public class DurableWorkProperties {

    private boolean enabled = false;
    private String namespace;
    private Duration pollInterval = Duration.ofSeconds(2);
    private Duration leaseDuration = Duration.ofMinutes(15);
    private Duration heartbeatInterval = Duration.ofSeconds(60);
    private int defaultMaxAttempts = 3;
    private Duration retryDelay = Duration.ofSeconds(30);
    private int workerThreads = 2;

    public void validate() {
        if (!enabled) { return; }
        if (namespace == null || namespace.trim().isEmpty()) {
            throw new IllegalStateException("assessment.reliability.namespace 不能为空");
        }
        requirePositive(pollInterval, "poll-interval");
        requirePositive(leaseDuration, "lease-duration");
        requirePositive(heartbeatInterval, "heartbeat-interval");
        requirePositive(retryDelay, "retry-delay");
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalStateException("heartbeat-interval 必须小于 lease-duration");
        }
        if (defaultMaxAttempts <= 0 || workerThreads <= 0) {
            throw new IllegalStateException("default-max-attempts 和 worker-threads 必须为正数");
        }
    }

    private void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " 必须为正数");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    public int getDefaultMaxAttempts() { return defaultMaxAttempts; }
    public void setDefaultMaxAttempts(int defaultMaxAttempts) { this.defaultMaxAttempts = defaultMaxAttempts; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
}
