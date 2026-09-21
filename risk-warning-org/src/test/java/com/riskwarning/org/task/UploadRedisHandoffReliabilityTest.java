package com.riskwarning.org.task;

import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.common.reliability.redis.RedisDeadLetterItem;
import com.riskwarning.common.reliability.DurableWorkProperties;
import com.riskwarning.common.reliability.DurableWorkStore;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1-03 Step B：真实 Redis + PostgreSQL 验证上传移交链。
 * claim 后崩溃可恢复、poison 进入 DEAD 不阻塞队列、
 * Durable Work 入库失败不 ACK。
 */
@EnabledIfEnvironmentVariable(named = "R1_03_IT_ENABLED", matches = "true")
class UploadRedisHandoffReliabilityTest {

    private static ReliabilityPostgresEnv pgEnv;
    private static ReliabilityRedisEnv redisEnv;
    private RedisUtil redisUtil;
    private RedisTemplate<String, Object> template;
    private String readyKey;
    private String pendingKey;
    private String deadKey;
    private String namespace;

    @BeforeAll
    static void startEnvironment() throws Exception {
        pgEnv = ReliabilityPostgresEnv.start();
        redisEnv = ReliabilityRedisEnv.start();
    }

    @AfterAll
    static void stopEnvironment() {
        redisEnv.stop();
    }

    @BeforeEach
    void setup() {
        redisUtil = redisEnv.redisUtil();
        template = redisEnv.getRedisTemplate();
        String queueKey = "r103-org-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        readyKey = queueKey;
        pendingKey = queueKey + ":processing";
        deadKey = queueKey + ":dead";
        namespace = "r103-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @AfterEach
    void cleanup() {
        redisEnv.deleteByPrefix(readyKey);
        pgEnv.deleteDurableWork(namespace);
    }

    @Test
    void crashedClaimIsRecoveredByNextHandoff() {
        UploadTaskQueue queue = new UploadTaskQueue(redisUtil, readyKey);
        queue.enqueue(completeTask("upload-1"));
        DurableWorkStore store = store();

        // 模拟服务在 claim 后崩溃：任务留在 PROCESSING，未 ACK、未入库
        ClaimedRedisItem<UploadConfirmDto> crashed = queue.claim();
        assertNotNull(crashed);
        assertEquals("upload:7:upload-1", crashed.getValue().getTaskId());
        assertEquals(0L, listSize(readyKey));
        assertEquals(1L, listSize(pendingKey));

        UploadRedisHandoffWorker worker = new UploadRedisHandoffWorker(queue, store, new UploadConfirmTaskCodec(),
                redisUtil, new AssessmentFlowLogger());
        assertTrue(worker.handoffOnce());

        // 恢复后：Durable Work 已入库，PROCESSING 已 ACK，READY 无残留
        assertEquals(1L, durableWorkCount());
        assertEquals(0L, listSize(readyKey));
        assertEquals(0L, listSize(pendingKey));
    }

    @Test
    void poisonMovesToDeadAndValidTaskStillHandedOff() {
        UploadTaskQueue queue = new UploadTaskQueue(redisUtil, readyKey);
        queue.enqueue(completeTask("upload-1"));
        // 毒丸排在队尾，RPOPLPUSH 会先领取
        byte[] poison = "not-java-serialized".getBytes();
        pushRawBytes(readyKey, poison);
        DurableWorkStore store = store();
        UploadRedisHandoffWorker worker = new UploadRedisHandoffWorker(queue, store, new UploadConfirmTaskCodec(),
                redisUtil, new AssessmentFlowLogger());

        assertTrue(worker.handoffOnce());

        // 毒丸已移入 DEAD 并保留可观测记录，合法任务已入库并 ACK
        assertEquals(1L, listSize(deadKey));
        assertEquals(0L, listSize(pendingKey));
        assertEquals(1L, durableWorkCount());
        RedisDeadLetterItem deadLetter = (RedisDeadLetterItem) template.opsForList().index(deadKey, 0);
        assertNotNull(deadLetter);
        assertEquals(readyKey, deadLetter.getQueueKey());
        assertEquals(sha256(poison), deadLetter.getPayloadHash());
    }

    @Test
    void durableEnqueueFailureKeepsTaskPendingWithoutAck() {
        UploadTaskQueue queue = new UploadTaskQueue(redisUtil, readyKey);
        queue.enqueue(completeTask("upload-1"));
        AtomicInteger remainingFailures = new AtomicInteger(1);
        DurableWorkStore store = store(remainingFailures);
        UploadRedisHandoffWorker worker = new UploadRedisHandoffWorker(queue, store, new UploadConfirmTaskCodec(),
                redisUtil, new AssessmentFlowLogger());

        assertTrue(worker.handoffOnce());

        // 入库失败：不 ACK，任务保留在 PROCESSING，Durable Work 未创建
        assertEquals(1L, listSize(pendingKey));
        assertEquals(0L, durableWorkCount());

        // 故障恢复后重新移交成功并 ACK
        remainingFailures.set(0);
        assertTrue(worker.handoffOnce());
        assertEquals(1L, durableWorkCount());
        assertEquals(0L, listSize(pendingKey));
    }

    @Test
    void persistentEnqueueFailureDeadLettersAfterAttemptCap() {
        UploadTaskQueue queue = new UploadTaskQueue(redisUtil, readyKey);
        queue.enqueue(completeTask("upload-deadlock"));
        // 故障注入：每次入库都失败，验证尝试上限把任务收敛进死信
        DurableWorkStore store = store(new AtomicInteger(UploadRedisHandoffWorker.MAX_ENQUEUE_ATTEMPTS));
        UploadRedisHandoffWorker worker = new UploadRedisHandoffWorker(queue, store, new UploadConfirmTaskCodec(),
                redisUtil, new AssessmentFlowLogger());

        for (int attempt = 1; attempt < UploadRedisHandoffWorker.MAX_ENQUEUE_ATTEMPTS; attempt++) {
            assertTrue(worker.handoffOnce(), "第 " + attempt + " 次尝试应领取到任务");
            assertEquals(1L, listSize(pendingKey), "未达上限前任务应保留在 PROCESSING");
        }

        assertTrue(worker.handoffOnce());
        assertEquals(0L, listSize(pendingKey), "达到尝试上限后单槽位应恢复放行");
        assertEquals(0L, durableWorkCount());
        RedisDeadLetterItem deadLetter = (RedisDeadLetterItem) template.opsForList().index(deadKey, 0);
        assertNotNull(deadLetter);
        assertEquals(readyKey, deadLetter.getQueueKey());
        assertEquals(IllegalStateException.class.getSimpleName(), deadLetter.getExceptionType());
        assertFalse(redisUtil.hasKey("upload:handoff:failures:" + deadLetter.getPayloadHash()));
    }

    private DurableWorkStore store() {
        return store(new AtomicInteger(0));
    }

    private DurableWorkStore store(AtomicInteger remainingFailures) {
        DurableWorkProperties properties = new DurableWorkProperties();
        properties.setEnabled(true);
        properties.setNamespace(namespace);
        properties.setLeaseDuration(Duration.ofSeconds(15));
        properties.setRetryDelay(Duration.ofMillis(200));
        DurableWorkStore realStore = new DurableWorkStore(pgEnv.getJdbcTemplate(), pgEnv.transactionTemplate(),
                properties);
        return new DurableWorkStore(pgEnv.getJdbcTemplate(), pgEnv.transactionTemplate(), properties) {
            @Override
            public boolean enqueue(String kind, String taskKey, String payload) {
                if (remainingFailures.getAndDecrement() > 0) {
                    throw new IllegalStateException("durable work db down");
                }
                return realStore.enqueue(kind, taskKey, payload);
            }
        };
    }

    private long durableWorkCount() {
        Long count = pgEnv.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM t_durable_work WHERE namespace = ?", Long.class, namespace);
        return count == null ? 0L : count;
    }

    private String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder output = new StringBuilder();
            for (byte item : digest.digest(value)) {
                output.append(String.format("%02x", item));
            }
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("计算 SHA-256 失败", exception);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private byte[] keyBytes(String key) {
        org.springframework.data.redis.serializer.RedisSerializer serializer = (org.springframework.data.redis.serializer.RedisSerializer) template
                .getKeySerializer();
        byte[] bytes = serializer.serialize(key);
        if (bytes == null) {
            throw new IllegalStateException("Redis key 序列化失败: " + key);
        }
        return bytes;
    }

    private long listSize(String key) {
        Long size = template.execute((RedisCallback<Long>) connection -> connection.lLen(keyBytes(key)));
        return size == null ? 0L : size;
    }

    private void pushRawBytes(String key, byte[] raw) {
        template.execute((RedisCallback<Object>) connection -> {
            connection.rPush(keyBytes(key), raw);
            return null;
        });
    }

    private UploadConfirmDto completeTask(String uploadId) {
        UploadFileDto file = UploadFileDto.builder()
                .projectId(7L).uploadId(uploadId).userId(8L)
                .filePath("/tmp/" + uploadId).totalChunks(1).fileSuffix("pdf").build();
        return UploadConfirmDto.builder()
                .taskId("upload:7:" + uploadId).projectId(7L).userId(8L)
                .files(Collections.singletonList(file)).build();
    }
}
