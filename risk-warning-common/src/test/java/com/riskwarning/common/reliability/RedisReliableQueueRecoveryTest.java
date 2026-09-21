package com.riskwarning.common.reliability;

import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.common.reliability.redis.RedisClaimDeserializationException;
import com.riskwarning.common.reliability.redis.RedisDeadLetterItem;
import com.riskwarning.common.utils.RedisUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R1-03 Step B：真实 Redis 验证可靠队列原语。
 * claim 后不 ACK 模拟崩溃恢复、poison payload 进入 DEAD、
 * DEAD 化后合法任务继续消费。
 */
@EnabledIfEnvironmentVariable(named = "R1_03_IT_ENABLED", matches = "true")
class RedisReliableQueueRecoveryTest {

    private static ReliabilityRedisEnv redisEnv;
    private RedisUtil redis;
    private String readyKey;
    private String pendingKey;
    private String deadKey;

    @BeforeAll
    static void startEnvironment() {
        redisEnv = ReliabilityRedisEnv.start();
    }

    @AfterAll
    static void stopEnvironment() {
        redisEnv.stop();
    }

    @BeforeEach
    void setup() {
        redis = redisEnv.redisUtil();
        readyKey = "r103-it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        pendingKey = readyKey + ":processing";
        deadKey = readyKey + ":dead";
    }

    @AfterEach
    void cleanup() {
        redisEnv.deleteByPrefix(readyKey);
    }

    @Test
    void crashedClaimIsRecoveredByNextWorkerAndAcknowledged() {
        redis.lSet(readyKey, "task-1");

        // 第一次领取后服务崩溃：任务停留在 PROCESSING，未 ACK
        ClaimedRedisItem<String> crashed = redis.claimListItem(readyKey, pendingKey, String.class);
        assertNotNull(crashed);
        assertEquals("task-1", crashed.getValue());
        assertEquals(0L, listSize(readyKey));
        assertEquals(1L, listSize(pendingKey));

        // 新 worker 下一次 claim 必须先恢复 PROCESSING 头部任务
        ClaimedRedisItem<String> recovered = redis.claimListItem(readyKey, pendingKey, String.class);
        assertNotNull(recovered);
        assertEquals("task-1", recovered.getValue());

        redis.acknowledgeListItem(recovered);
        assertEquals(0L, listSize(readyKey));
        assertEquals(0L, listSize(pendingKey));
    }

    @Test
    void poisonPayloadMovesToDeadAndNextTaskIsClaimable() {
        redis.lSet(readyKey, "task-1");
        // 直接向 READY 写入无法反序列化的原始字节，模拟损坏 payload；
        // RPOPLPUSH 从右侧领取，毒丸必须排在队尾才会先被消费
        pushRawBytes(readyKey, "not-java-serialized".getBytes());

        RedisClaimDeserializationException failure = assertThrows(
                RedisClaimDeserializationException.class,
                () -> redis.claimListItem(readyKey, pendingKey, String.class));

        assertNotNull(failure.getRawValue());
        redis.deadLetterListItem(pendingKey, deadKey, failure.getRawValue(),
                new RedisDeadLetterItem(readyKey, "hash", "PoisonPayload",
                        System.currentTimeMillis(), failure.getRawValue()));

        assertEquals(1L, listSize(deadKey));
        assertEquals(0L, listSize(pendingKey));
        // 毒丸移入 DEAD 后，合法任务不受阻塞
        assertEquals("task-1",
                redis.claimListItem(readyKey, pendingKey, String.class).getValue());
    }

    /** key 序列化必须与模板一致（生产为 JDK 序列化），否则长度统计指向别的 key。 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private byte[] keyBytes(String key) {
        org.springframework.data.redis.serializer.RedisSerializer serializer = (org.springframework.data.redis.serializer.RedisSerializer) template()
                .getKeySerializer();
        byte[] bytes = serializer.serialize(key);
        if (bytes == null) {
            throw new IllegalStateException("Redis key 序列化失败: " + key);
        }
        return bytes;
    }

    private long listSize(String key) {
        Long size = template().execute((RedisCallback<Long>) connection -> connection.lLen(keyBytes(key)));
        return size == null ? 0L : size;
    }

    private void pushRawBytes(String key, byte[] raw) {
        template().execute((RedisCallback<Object>) connection -> {
            connection.rPush(keyBytes(key), raw);
            return null;
        });
    }

    private RedisTemplate<String, Object> template() {
        return redisEnv.getRedisTemplate();
    }
}
