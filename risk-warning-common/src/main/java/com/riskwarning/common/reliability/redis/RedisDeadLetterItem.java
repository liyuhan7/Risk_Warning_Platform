package com.riskwarning.common.reliability.redis;

import java.io.Serializable;

/** Redis poison task 的可观测死信记录。 */
public class RedisDeadLetterItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String queueKey;
    private final String payloadHash;
    private final String exceptionType;
    private final long timestamp;
    private final byte[] rawValue;

    public RedisDeadLetterItem(String queueKey, String payloadHash, String exceptionType,
            long timestamp, byte[] rawValue) {
        this.queueKey = queueKey;
        this.payloadHash = payloadHash;
        this.exceptionType = exceptionType;
        this.timestamp = timestamp;
        this.rawValue = rawValue == null ? null : java.util.Arrays.copyOf(rawValue, rawValue.length);
    }

    public String getQueueKey() {
        return queueKey;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public byte[] getRawValue() {
        return rawValue == null ? null : java.util.Arrays.copyOf(rawValue, rawValue.length);
    }
}
