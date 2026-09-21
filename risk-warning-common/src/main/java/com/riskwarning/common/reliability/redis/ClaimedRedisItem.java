package com.riskwarning.common.reliability.redis;

import java.util.Arrays;

/**
 * Redis 可靠队列的领取凭证。
 *
 * ACK 必须携带 Redis 中的原始 value 字节，不能重新序列化业务对象，否则序列化差异可能导致待处理项无法删除。
 */
public final class ClaimedRedisItem<T> {

    private final T value;
    private final byte[] rawValue;
    private final String readyKey;
    private final String pendingKey;

    public ClaimedRedisItem(T value, byte[] rawValue, String readyKey, String pendingKey) {
        if (value == null || rawValue == null || rawValue.length == 0
                || readyKey == null || pendingKey == null) {
            throw new IllegalArgumentException("Redis 领取凭证字段不能为空");
        }
        this.value = value;
        this.rawValue = Arrays.copyOf(rawValue, rawValue.length);
        this.readyKey = readyKey;
        this.pendingKey = pendingKey;
    }

    public T getValue() {
        return value;
    }

    public byte[] getRawValue() {
        return Arrays.copyOf(rawValue, rawValue.length);
    }

    public String getReadyKey() {
        return readyKey;
    }

    public String getPendingKey() {
        return pendingKey;
    }
}
