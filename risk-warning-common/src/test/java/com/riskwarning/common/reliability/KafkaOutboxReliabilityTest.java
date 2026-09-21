package com.riskwarning.common.reliability;

import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.utils.KafkaUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1-03 Step E：真实 PostgreSQL + Kafka 验证 Transactional Outbox。
 * DB 提交后 Kafka 不可用时任务保留并重试；发送成功但状态更新失败时
 * 重复物理消息使用同一稳定 messageId；同一 messageId 重复入箱幂等。
 */
@EnabledIfEnvironmentVariable(named = "R1_03_IT_ENABLED", matches = "true")
class KafkaOutboxReliabilityTest {

    private static ReliabilityPostgresEnv pgEnv;
    private static ReliabilityKafkaEnv kafkaEnv;
    private JdbcTemplate jdbc;
    private String namespace;
    private String messageId;
    private String suffix;

    @BeforeAll
    static void startEnvironment() throws Exception {
        pgEnv = ReliabilityPostgresEnv.start();
        kafkaEnv = ReliabilityKafkaEnv.start();
    }

    @AfterAll
    static void stopEnvironment() {
        kafkaEnv.stop();
    }

    @BeforeEach
    void setup() {
        jdbc = pgEnv.getJdbcTemplate();
        suffix = kafkaEnv.randomSuffix();
        namespace = "r103-out-" + suffix;
        messageId = "r103-msg-" + suffix;
    }

    @AfterEach
    void cleanup() {
        pgEnv.deleteDurableWork(namespace);
    }

    @Test
    void kafkaUnavailableKeepsOutboxReadyAndRecoversAfterSend() throws Exception {
        DurableWorkStore store = store();
        TransactionalKafkaOutbox outbox = outboxWithStore(store);
        TransactionTemplate transactions = pgEnv.transactionTemplate();
        transactions.executeWithoutResult(status -> outbox.enqueue(notification()));

        assertEquals(1L, workCount());
        assertEquals("READY", workState());

        // Kafka 不可用：发送失败交回重试，Outbox 保留在数据库
        DurableWork work = store.claim(KafkaOutboxHandler.KIND, "worker-fail");
        assertNotNull(work);
        DurableWorker worker =
                new DurableWorker(store, java.util.Collections.<DurableWorkHandler>emptyList(),
                        properties(), new AssessmentFlowLogger());
        worker.execute(new KafkaOutboxHandler(kafkaEnv.unreachableKafkaUtils(5_000L),
                new KafkaOutboxCodec()), work);
        assertEquals("READY", workState());

        // Kafka 恢复后同一 Outbox 成功发送并 DONE
        DurableWork retry = claimWithin(store, 5);
        assertNotNull(retry);
        worker.execute(new KafkaOutboxHandler(kafkaEnv.kafkaUtils(), new KafkaOutboxCodec()), retry);
        assertEquals("DONE", workState());
    }

    @Test
    void resendAfterStateUpdateFailureUsesStableMessageId() throws Exception {
        String group = "r103-group-" + suffix;
        KafkaConsumer<String, String> consumer = kafkaEnv.stringConsumer(group);
        consumer.subscribe(Collections.singletonList("notification_tasks"));

        // complete 首次失败：模拟消息已发送但 worker 状态更新前崩溃
        AtomicInteger completeFailures = new AtomicInteger(1);
        DurableWorkStore store = storeWithFailingComplete(completeFailures);
        TransactionTemplate transactions = pgEnv.transactionTemplate();
        transactions.executeWithoutResult(status ->
                outboxWithStore(store).enqueue(notification()));

        DurableWork first = store.claim(KafkaOutboxHandler.KIND, "worker-1");
        assertNotNull(first);
        DurableWorker worker =
                new DurableWorker(store, java.util.Collections.<DurableWorkHandler>emptyList(),
                        properties(), new AssessmentFlowLogger());
        worker.execute(new KafkaOutboxHandler(kafkaEnv.kafkaUtils(), new KafkaOutboxCodec()), first);

        // 第一次执行已真实发送但 complete 失败；不得误走业务 retry，任务保持 RUNNING，
        // 崩溃恢复只允许在原 lease 到期后重新领取。
        assertEquals("RUNNING", workState());
        jdbc.update("UPDATE t_durable_work SET lease_until = LOCALTIMESTAMP - INTERVAL '1 second' "
                + "WHERE namespace = ? AND task_key = ?", namespace, messageId);

        DurableWork second = claimWithin(store, 5);
        assertNotNull(second);
        assertEquals(2, second.getAttempts());
        worker.execute(new KafkaOutboxHandler(kafkaEnv.kafkaUtils(), new KafkaOutboxCodec()), second);
        assertEquals("DONE", workState());

        // 两次物理投递使用同一稳定 messageId，下游可安全去重
        int matched = waitForMessages(consumer, messageId, 2, 30);
        assertEquals(2, matched);
        consumer.close();
    }

    @Test
    void duplicateEnqueueKeepsSingleRowWithOriginalPayload() {
        DurableWorkStore store = store();
        TransactionalKafkaOutbox outbox = outboxWithStore(store);
        TransactionTemplate transactions = pgEnv.transactionTemplate();
        transactions.executeWithoutResult(status -> outbox.enqueue(notification()));
        transactions.executeWithoutResult(status ->
                outbox.enqueue(notificationWithDifferentBody()));

        assertEquals(1L, workCount());
        String payload = jdbc.queryForObject(
                "SELECT payload FROM t_durable_work WHERE namespace = ?", String.class, namespace);
        assertNotNull(payload);
        assertTrue(payload.contains(messageId));
        assertFalse(payload.contains("different-content"), "重复入箱不得覆盖原载荷");
    }

    private NotificationMessage notification() {
        NotificationMessage message = new NotificationMessage(
                messageId, String.valueOf(System.currentTimeMillis()), "trace-" + suffix,
                5L, 10L, 20L);
        message.setAnalysisRunId("run-" + suffix);
        return message;
    }

    private NotificationMessage notificationWithDifferentBody() {
        NotificationMessage message = notification();
        message.setContent("different-content");
        return message;
    }

    private TransactionalKafkaOutbox outboxWithStore(DurableWorkStore store) {
        return new TransactionalKafkaOutbox(store, new KafkaOutboxCodec(),
                new AssessmentFlowLogger());
    }

    private DurableWorkStore store() {
        return new DurableWorkStore(pgEnv.getJdbcTemplate(), pgEnv.transactionTemplate(), properties());
    }

    /** 首次 complete 抛异常模拟发送成功后 worker 崩溃。 */
    private DurableWorkStore storeWithFailingComplete(AtomicInteger remainingFailures) {
        DurableWorkStore realStore = store();
        return new DurableWorkStore(pgEnv.getJdbcTemplate(), pgEnv.transactionTemplate(), properties()) {
            @Override
            public void complete(DurableWork work) {
                if (remainingFailures.getAndDecrement() > 0) {
                    throw new IllegalStateException("worker state update failed");
                }
                realStore.complete(work);
            }
        };
    }

    private DurableWorkProperties properties() {
        DurableWorkProperties properties = new DurableWorkProperties();
        properties.setEnabled(true);
        properties.setNamespace(namespace);
        properties.setLeaseDuration(Duration.ofSeconds(15));
        properties.setRetryDelay(Duration.ofMillis(200));
        return properties;
    }

    /** 重试延迟后任务回到 READY；轮询领取直到可见。 */
    private DurableWork claimWithin(DurableWorkStore store, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            DurableWork work = store.claim(KafkaOutboxHandler.KIND, "worker-retry");
            if (work != null) {
                return work;
            }
            Thread.sleep(100);
        }
        return null;
    }

    private long workCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_durable_work WHERE namespace = ?", Long.class, namespace);
        return count == null ? 0L : count;
    }

    private String workState() {
        return jdbc.queryForObject(
                "SELECT state FROM t_durable_work WHERE namespace = ?", String.class, namespace);
    }

    /** 轮询消费直到收齐匹配 messageId 的消息数。 */
    private int waitForMessages(KafkaConsumer<String, String> consumer, String messageId,
                                int expected, int maxSeconds) {
        int matched = 0;
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (matched < expected && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : records) {
                String value = record.value();
                if (value != null && value.contains("\"messageId\":\"" + messageId + "\"")) {
                    matched++;
                }
            }
        }
        return matched;
    }
}
