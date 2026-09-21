package com.riskwarning.org.task;

import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.common.reliability.redis.RedisClaimDeserializationException;
import com.riskwarning.common.reliability.redis.RedisDeadLetterItem;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;

/** 上传确认任务的 Redis READY / PROCESSING / DEAD 队列边界。 */
@Component
@Slf4j
public class UploadTaskQueue {

    private final RedisUtil redis;
    private final String readyKey;
    private final String pendingKey;
    private final String deadKey;

    public UploadTaskQueue(RedisUtil redis,
            @Value("${org.upload.queue-key:file:confirmed:queue}") String readyKey) {
        if (readyKey == null || readyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("上传确认队列 key 不能为空");
        }
        this.redis = redis;
        this.readyKey = readyKey;
        this.pendingKey = readyKey + ":processing";
        this.deadKey = readyKey + ":dead";
    }

    public void enqueue(UploadConfirmDto task) {
        if (task == null) {
            throw new IllegalArgumentException("上传确认任务不能为空");
        }
        if (!redis.lSet(readyKey, task)) {
            throw new IllegalStateException("上传确认任务写入 Redis 失败");
        }
    }

    /** poison task 会先移入 DEAD，再继续领取后续任务。 */
    public ClaimedRedisItem<UploadConfirmDto> claim() {
        while (true) {
            try {
                return redis.claimListItem(readyKey, pendingKey, UploadConfirmDto.class);
            } catch (RedisClaimDeserializationException exception) {
                byte[] rawValue = exception.getRawValue();
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                RedisDeadLetterItem deadLetter = new RedisDeadLetterItem(
                        readyKey, sha256(rawValue), cause.getClass().getName(),
                        System.currentTimeMillis(), rawValue);
                redis.deadLetterListItem(pendingKey, deadKey, rawValue, deadLetter);
                log.error("上传确认 poison task 已移入 DEAD: queueKey={}, payloadHash={}, exceptionType={}",
                        readyKey, deadLetter.getPayloadHash(), deadLetter.getExceptionType(), exception);
            }
        }
    }

    public void acknowledge(ClaimedRedisItem<UploadConfirmDto> claimed) {
        redis.acknowledgeListItem(claimed);
    }

    /** 无法通过重试恢复的任务移入死信并保留原始载荷。 */
    public void deadLetter(ClaimedRedisItem<UploadConfirmDto> claimed, String reason) {
        RedisDeadLetterItem deadLetter = new RedisDeadLetterItem(
                readyKey, sha256(claimed.getRawValue()),
                reason == null || reason.trim().isEmpty() ? "UNKNOWN" : reason,
                System.currentTimeMillis(), claimed.getRawValue());
        redis.deadLetterListItem(pendingKey, deadKey, claimed.getRawValue(), deadLetter);
    }

    /** 领取载荷的稳定指纹，供失败计数与死信记录复用。 */
    public String payloadHash(ClaimedRedisItem<UploadConfirmDto> claimed) {
        return sha256(claimed.getRawValue());
    }

    /** 统计同一载荷的移交失败次数；键按载荷指纹隔离并带 TTL。 */
    public long recordFailure(String payloadHash, long expireSeconds) {
        return redis.incrementWithExpire("upload:handoff:failures:" + payloadHash, expireSeconds);
    }

    /** 清除载荷对应的移交失败计数，成功移交或已入死信后调用。 */
    public void clearFailure(String payloadHash) {
        redis.del("upload:handoff:failures:" + payloadHash);
    }

    public String getReadyKey() {
        return readyKey;
    }

    public String getPendingKey() {
        return pendingKey;
    }

    public String getDeadKey() {
        return deadKey;
    }

    private String sha256(byte[] value) {
        if (value == null) {
            return "unavailable";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value);
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("计算 Redis poison payload hash 失败", exception);
        }
    }
}
