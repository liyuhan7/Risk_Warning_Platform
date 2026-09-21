package com.riskwarning.common.reliability.redis;

import java.util.Arrays;

/** Redis 已完成领取，但 value 无法转换为业务对象。 */
public class RedisClaimDeserializationException extends RuntimeException {

    private final String readyKey;
    private final String pendingKey;
    private final byte[] rawValue;

    public RedisClaimDeserializationException(String readyKey, String pendingKey,
            byte[] rawValue, Throwable cause) {
        super("无法反序列化 Redis 可靠队列任务: " + readyKey, cause);
        this.readyKey = readyKey;
        this.pendingKey = pendingKey;
        this.rawValue = rawValue == null ? null : Arrays.copyOf(rawValue, rawValue.length);
    }

    public String getReadyKey() {
        return readyKey;
    }

    public String getPendingKey() {
        return pendingKey;
    }

    public byte[] getRawValue() {
        return rawValue == null ? null : Arrays.copyOf(rawValue, rawValue.length);
    }
}
