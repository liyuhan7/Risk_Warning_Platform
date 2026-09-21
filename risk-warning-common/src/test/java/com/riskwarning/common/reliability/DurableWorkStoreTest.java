package com.riskwarning.common.reliability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableWorkStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final DurableWorkProperties properties = new DurableWorkProperties();
    private DurableWorkStore store;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        properties.setNamespace("processing");
        properties.setLeaseDuration(Duration.ofMinutes(15));
        properties.setRetryDelay(Duration.ofSeconds(30));
        store = new DurableWorkStore(jdbc, transactions, properties);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }

    @Test
    void enqueueUsesStableUniqueTaskWithoutOverwriting() {
        when(jdbc.update(contains("ON CONFLICT"), any(), any(), any(), any(), any()))
                .thenReturn(1, 0);

        assertTrue(store.enqueue("UPLOAD_CONFIRM", "task-1", "payload"));
        assertFalse(store.enqueue("UPLOAD_CONFIRM", "task-1", "payload"));
    }

    @Test
    void claimReturnsNullWhenNoTaskIsAvailable() {
        when(jdbc.query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class),
                any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        assertNull(store.claim("UPLOAD_CONFIRM", "worker-1"));
        verify(transactions).execute(any());
    }

    @Test
    void claimMapsAttemptAndLeaseFromReturningRow() throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getString("id")).thenReturn("work-1");
        when(result.getString("namespace")).thenReturn("processing");
        when(result.getString("kind")).thenReturn("UPLOAD_CONFIRM");
        when(result.getString("task_key")).thenReturn("task-1");
        when(result.getString("payload")).thenReturn("payload");
        when(result.getInt("attempts")).thenReturn(2);
        when(result.getString("worker_id")).thenReturn("worker-1");
        when(result.getString("lease_token")).thenReturn("lease-1");
        when(result.getTimestamp("lease_until"))
                .thenReturn(Timestamp.valueOf("2026-09-19 10:15:00"));
        when(jdbc.query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class),
                any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Collections.singletonList(
                        ((RowMapper<?>) invocation.getArgument(1)).mapRow(result, 0)));

        DurableWork work = store.claim("UPLOAD_CONFIRM", "worker-1");

        assertEquals("work-1", work.getId());
        assertEquals(2, work.getAttempts());
        assertEquals("worker-1", work.getWorkerId());
        assertEquals("lease-1", work.getLeaseToken());
    }

    @Test
    void retryReleasesLeaseAndDelaysAvailability() {
        DurableWork work = work(1);
        when(jdbc.update(contains("state = 'READY'"), any(), any(), any(), any(), any()))
                .thenReturn(1);

        store.retry(work, new IllegalStateException("temporary"));

        verify(jdbc).update(contains("state = 'READY'"),
                org.mockito.ArgumentMatchers.eq(30_000L),
                org.mockito.ArgumentMatchers.eq("java.lang.IllegalStateException: temporary"),
                org.mockito.ArgumentMatchers.eq("work-1"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                org.mockito.ArgumentMatchers.eq("lease-1"));
    }

    private DurableWork work(int attempts) {
        return new DurableWork("work-1", "processing", "UPLOAD_CONFIRM", "task-1",
                "payload", attempts, "worker-1", "lease-1",
                java.time.LocalDateTime.of(2026, 9, 19, 10, 15));
    }
}
