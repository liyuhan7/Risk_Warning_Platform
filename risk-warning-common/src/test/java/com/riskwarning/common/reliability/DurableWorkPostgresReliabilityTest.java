package com.riskwarning.common.reliability;

import com.riskwarning.common.observability.AssessmentFlowLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1-03 Step C：真实 PostgreSQL 验证 Durable Work 状态机、
 * lease 接管、并发竞争与重试耗尽。SQL 全部走生产 DurableWorkStore 实现。
 */
@EnabledIfEnvironmentVariable(named = "R1_03_IT_ENABLED", matches = "true")
class DurableWorkPostgresReliabilityTest {

    private static ReliabilityPostgresEnv env;
    private JdbcTemplate jdbc;
    private String namespace;

    @BeforeAll
    static void startEnvironment() throws Exception {
        env = ReliabilityPostgresEnv.start();
    }

    @BeforeEach
    void setup() {
        jdbc = env.getJdbcTemplate();
        namespace = "r103-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @AfterEach
    void cleanup() {
        env.deleteDurableWork(namespace);
    }

    private DurableWorkProperties properties() {
        DurableWorkProperties properties = new DurableWorkProperties();
        properties.setEnabled(true);
        properties.setNamespace(namespace);
        properties.setLeaseDuration(Duration.ofSeconds(15));
        properties.setRetryDelay(Duration.ofMillis(200));
        properties.setDefaultMaxAttempts(3);
        return properties;
    }

    private DurableWorkStore store() {
        return new DurableWorkStore(jdbc, transactionTemplate(), properties());
    }

    private TransactionTemplate transactionTemplate() {
        return env.transactionTemplate();
    }

    @Test
    void migratedSchemaPassesReliabilityStartupGuard() {
        new ReliabilitySchemaGuard(jdbc).verify();
    }

    @Test
    void duplicateEnqueueDoesNotOverwriteExistingTask() {
        DurableWorkStore store = store();
        assertTrue(store.enqueue("IT_KIND", "task-1", "first-payload"));
        assertFalse(store.enqueue("IT_KIND", "task-1", "second-payload"));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT payload FROM t_durable_work WHERE namespace = ? AND task_key = 'task-1'",
                namespace);
        assertEquals(1, rows.size());
        assertEquals("first-payload", rows.get(0).get("payload"));
    }

    @Test
    void unexpiredLeaseBlocksOtherWorkers() {
        DurableWorkStore store = store();
        store.enqueue("IT_KIND", "task-1", "payload");
        DurableWork first = store.claim("IT_KIND", "worker-1");
        assertNotNull(first);

        assertNull(store.claim("IT_KIND", "worker-2"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, worker_id, attempts FROM t_durable_work WHERE id = ?", first.getId());
        assertEquals("RUNNING", row.get("state"));
        assertEquals("worker-1", row.get("worker_id"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
    }

    @Test
    void leaseExpiryLetsOtherWorkerTakeOver() {
        DurableWorkStore store = store();
        store.enqueue("IT_KIND", "task-1", "payload");
        DurableWork crashed = store.claim("IT_KIND", "worker-1");
        assertNotNull(crashed);

        // 模拟 worker 崩溃：不 heartbeat、不 complete，直接把 lease 推到过去
        jdbc.update("UPDATE t_durable_work SET lease_until = LOCALTIMESTAMP - INTERVAL '1 second'"
                + " WHERE id = ?", crashed.getId());

        DurableWork takeover = store.claim("IT_KIND", "worker-2");
        assertNotNull(takeover);
        assertEquals(2, takeover.getAttempts());
        assertEquals("worker-2", takeover.getWorkerId());
    }

    @Test
    void sameWorkerIdCannotCompleteAfterNewClaimReplacesLeaseToken() {
        DurableWorkStore store = store();
        store.enqueue("IT_KIND", "task-1", "payload");
        DurableWork original = store.claim("IT_KIND", "worker-shared");
        assertNotNull(original);
        jdbc.update("UPDATE t_durable_work SET lease_until = LOCALTIMESTAMP - INTERVAL '1 second'"
                + " WHERE id = ?", original.getId());

        DurableWork takeover = store.claim("IT_KIND", "worker-shared");
        assertNotNull(takeover);
        assertNotEquals(original.getLeaseToken(), takeover.getLeaseToken());

        assertThrows(IllegalStateException.class, () -> store.complete(original));
        store.complete(takeover);
        assertEquals("DONE", jdbc.queryForObject(
                "SELECT state FROM t_durable_work WHERE id = ?", String.class, takeover.getId()));
    }

    @Test
    void expiredLeaseRejectsOriginalWorkerStateTransitions() {
        DurableWorkStore store = store();
        store.enqueue("IT_KIND", "task-1", "payload");
        DurableWork work = store.claim("IT_KIND", "worker-1");
        jdbc.update("UPDATE t_durable_work SET lease_until = LOCALTIMESTAMP - INTERVAL '1 second'"
                + " WHERE id = ?", work.getId());

        assertThrows(IllegalStateException.class, () -> store.complete(work));
        assertThrows(IllegalStateException.class,
                () -> store.retry(work, new IllegalStateException("late")));
    }

    @Test
    void retryReturnsTaskToReadyWithDelay() {
        DurableWorkStore store = store();
        store.enqueue("IT_KIND", "task-1", "payload");
        DurableWork work = store.claim("IT_KIND", "worker-1");

        store.retry(work, new IllegalStateException("temporary"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, worker_id, lease_until, last_error FROM t_durable_work WHERE id = ?",
                work.getId());
        assertEquals("READY", row.get("state"));
        assertNull(row.get("worker_id"));
        assertNull(row.get("lease_until"));
        assertNotNull(row.get("last_error"));
        // available_at 已延迟，立即再次领取不可见
        assertNull(store.claim("IT_KIND", "worker-2"));
    }

    @Test
    void concurrentClaimGivesSingleLease() throws Exception {
        DurableWorkStore store = store();
        store.enqueue("IT_KIND", "task-1", "payload");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DurableWork> first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return store().claim("IT_KIND", "worker-a");
            });
            Future<DurableWork> second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return store().claim("IT_KIND", "worker-b");
            });

            DurableWork a = first.get(10, TimeUnit.SECONDS);
            DurableWork b = second.get(10, TimeUnit.SECONDS);

            int winners = (a == null ? 0 : 1) + (b == null ? 0 : 1);
            assertEquals(1, winners, "同一 Durable Work 只能一个 worker 获得有效 lease");
            DurableWork winner = a != null ? a : b;
            assertEquals(1, winner.getAttempts());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exhaustedFailureMarksWorkFailedAndCallsCallbackOnce() {
        DurableWorkStore store = store();
        store.enqueue("IT_KIND", "task-1", "payload");
        // 两次接管把 attempts 推到上限 3，最后一次执行必须进入 FAILED
        DurableWork work = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            work = store.claim("IT_KIND", "worker-" + attempt);
            assertNotNull(work);
            if (attempt < 2) {
                jdbc.update("UPDATE t_durable_work SET lease_until = LOCALTIMESTAMP"
                        + " - INTERVAL '1 second' WHERE id = ?", work.getId());
            }
        }
        assertEquals(3, work.getAttempts());

        AtomicBoolean exhausted = new AtomicBoolean();
        DurableWorker worker = new DurableWorker(store, Collections.<DurableWorkHandler>emptyList(),
                properties(), new AssessmentFlowLogger());
        worker.execute(new DurableWorkHandler() {
            @Override public String kind() { return "IT_KIND"; }
            @Override public void execute(DurableWorkContext context, String payload) {
                throw new IllegalStateException("持续失败");
            }
            @Override public void onExhausted(DurableWorkContext context, String payload,
                                               Throwable failure) {
                assertTrue(exhausted.compareAndSet(false, true));
            }
        }, work);

        assertEquals("FAILED", stateOf(work.getId()));
        assertTrue(exhausted.get());
    }

    private String stateOf(String workId) {
        return jdbc.queryForObject("SELECT state FROM t_durable_work WHERE id = ?", String.class, workId);
    }
}
