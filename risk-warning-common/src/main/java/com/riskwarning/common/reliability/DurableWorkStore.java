package com.riskwarning.common.reliability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** PostgreSQL 持久任务状态机；每个公开状态转换只持有一个短事务。 */
public class DurableWorkStore {

    private static final String CLAIM_SQL =
            "WITH candidate AS (" +
            " SELECT id FROM t_durable_work" +
            " WHERE namespace = ? AND kind = ?" +
            " AND ((state = 'READY' AND available_at <= LOCALTIMESTAMP)" +
            "      OR (state = 'RUNNING' AND lease_until < LOCALTIMESTAMP))" +
            " ORDER BY available_at, created_at" +
            " LIMIT 1 FOR UPDATE SKIP LOCKED" +
            ") UPDATE t_durable_work work SET state = 'RUNNING', attempts = attempts + 1," +
            " worker_id = ?, lease_token = ?," +
            " lease_until = LOCALTIMESTAMP + (? * INTERVAL '1 millisecond')," +
            " updated_at = LOCALTIMESTAMP" +
            " FROM candidate WHERE work.id = candidate.id" +
            " RETURNING work.id, work.namespace, work.kind, work.task_key, work.payload," +
            " work.attempts, work.worker_id, work.lease_token, work.lease_until";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DurableWorkProperties properties;

    public DurableWorkStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                            DurableWorkProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** 重复 taskKey 不覆盖既有任务或 payload。 */
    public boolean enqueue(String kind, String taskKey, String payload) {
        requireText(kind, "kind");
        requireText(taskKey, "taskKey");
        requireText(payload, "payload");
        int inserted = jdbc.update("INSERT INTO t_durable_work " +
                        "(id, namespace, kind, task_key, payload, state, attempts, available_at, " +
                        "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'READY', 0, " +
                        "LOCALTIMESTAMP, LOCALTIMESTAMP, LOCALTIMESTAMP) " +
                        "ON CONFLICT (namespace, kind, task_key) DO NOTHING",
                UUID.randomUUID().toString(), properties.getNamespace(), kind, taskKey, payload);
        return inserted == 1;
    }

    public DurableWork claim(String kind, String workerId) {
        requireText(kind, "kind");
        requireText(workerId, "workerId");
        return transactions.execute(status -> {
            String leaseToken = UUID.randomUUID().toString();
            List<DurableWork> claimed = jdbc.query(CLAIM_SQL,
                    (result, row) -> new DurableWork(
                            result.getString("id"), result.getString("namespace"),
                            result.getString("kind"), result.getString("task_key"),
                            result.getString("payload"), result.getInt("attempts"),
                            result.getString("worker_id"), result.getString("lease_token"),
                            result.getTimestamp("lease_until").toLocalDateTime()),
                    properties.getNamespace(), kind, workerId, leaseToken,
                    properties.getLeaseDuration().toMillis());
            return claimed.isEmpty() ? null : claimed.get(0);
        });
    }

    public boolean heartbeat(DurableWork work) {
        return jdbc.update("UPDATE t_durable_work SET " +
                        "lease_until = LOCALTIMESTAMP + (? * INTERVAL '1 millisecond'), " +
                        "updated_at = LOCALTIMESTAMP WHERE id = ? AND state = 'RUNNING' " +
                        "AND worker_id = ? AND lease_token = ? AND lease_until >= LOCALTIMESTAMP",
                properties.getLeaseDuration().toMillis(), work.getId(), work.getWorkerId(),
                work.getLeaseToken()) == 1;
    }

    public void complete(DurableWork work) {
        requireOwnedUpdate(jdbc.update("UPDATE t_durable_work SET state = 'DONE', " +
                        "worker_id = NULL, lease_token = NULL, lease_until = NULL, last_error = NULL, " +
                        "updated_at = LOCALTIMESTAMP WHERE id = ? AND state = 'RUNNING' " +
                        "AND worker_id = ? AND lease_token = ? AND lease_until >= LOCALTIMESTAMP",
                work.getId(), work.getWorkerId(), work.getLeaseToken()), work, "完成");
    }

    public void retry(DurableWork work, Throwable failure) {
        requireOwnedUpdate(jdbc.update("UPDATE t_durable_work SET state = 'READY', " +
                        "available_at = LOCALTIMESTAMP + (? * INTERVAL '1 millisecond'), " +
                        "worker_id = NULL, lease_token = NULL, lease_until = NULL, last_error = ?, " +
                        "updated_at = LOCALTIMESTAMP WHERE id = ? AND state = 'RUNNING' " +
                        "AND worker_id = ? AND lease_token = ? AND lease_until >= LOCALTIMESTAMP",
                properties.getRetryDelay().toMillis(), errorMessage(failure),
                work.getId(), work.getWorkerId(), work.getLeaseToken()), work, "重试");
    }

    public void fail(DurableWork work, Throwable failure) {
        requireOwnedUpdate(jdbc.update("UPDATE t_durable_work SET state = 'FAILED', " +
                        "worker_id = NULL, lease_token = NULL, lease_until = NULL, last_error = ?, " +
                        "updated_at = LOCALTIMESTAMP WHERE id = ? AND state = 'RUNNING' " +
                        "AND worker_id = ? AND lease_token = ? AND lease_until >= LOCALTIMESTAMP",
                errorMessage(failure), work.getId(), work.getWorkerId(), work.getLeaseToken()),
                work, "失败");
    }

    private void requireOwnedUpdate(int updated, DurableWork work, String operation) {
        if (updated != 1) {
            throw new IllegalStateException("Durable Work " + operation
                    + "失败，lease 已失效或不属于当前 worker: " + work.getId());
        }
    }

    private String errorMessage(Throwable failure) {
        String message = failure == null ? "unknown failure" : failure.toString();
        return message.length() <= 4000 ? message : message.substring(0, 4000);
    }

    private void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
