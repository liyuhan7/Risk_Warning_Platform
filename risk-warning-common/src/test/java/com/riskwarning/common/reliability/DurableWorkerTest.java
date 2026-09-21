package com.riskwarning.common.reliability;

import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowMdc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableWorkerTest {

    private final DurableWorkStore store = mock(DurableWorkStore.class);
    private final DurableWorkProperties properties = new DurableWorkProperties();
    private final AssessmentFlowLogger flowLogger = mock(AssessmentFlowLogger.class);
    private DurableWorker worker;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        properties.setNamespace("processing");
        properties.setWorkerThreads(1);
        properties.setHeartbeatInterval(Duration.ofMinutes(1));
        properties.setLeaseDuration(Duration.ofMinutes(15));
        properties.setDefaultMaxAttempts(3);
        worker = new DurableWorker(store, Collections.emptyList(), properties, flowLogger);
    }

    @AfterEach
    void shutdown() {
        worker.stop();
        org.slf4j.MDC.clear();
    }

    @Test
    void completesSuccessfulHandler() {
        DurableWorkHandler handler = handler(0, false, null);
        DurableWork work = work(1);

        worker.execute(handler, work);

        verify(store).complete(work);
        verify(store, never()).retry(any(), any());
    }

    @Test
    void retriesTemporaryFailureWithoutExhaustedCallback() {
        AtomicBoolean exhausted = new AtomicBoolean();
        DurableWorkHandler handler = handler(0, true, exhausted);
        DurableWork work = work(2);

        worker.execute(handler, work);

        verify(store).retry(any(), any());
        verify(store, never()).fail(any(), any());
        assertFalse(exhausted.get());
        verify(flowLogger).warn(any(AssessmentFlowEvent.class));
    }

    @Test
    void failsAndCallsExhaustedAtAttemptLimit() {
        AtomicBoolean exhausted = new AtomicBoolean();
        DurableWorkHandler handler = handler(0, true, exhausted);
        DurableWork work = work(3);

        worker.execute(handler, work);

        verify(store).fail(any(), any());
        verify(store, never()).retry(any(), any());
        assertTrue(exhausted.get());
        verify(flowLogger).error(any(AssessmentFlowEvent.class));
    }

    @Test
    void rejectsDuplicateHandlerKinds() {
        DurableWorkHandler first = handler(0, false, null);
        DurableWorkHandler second = handler(0, false, null);

        assertThrows(IllegalStateException.class,
                () -> new DurableWorker(store, java.util.Arrays.asList(first, second), properties, flowLogger));
    }

    @Test
    void handlerReadsFlowIdentityFromMdcDuringExecution() {
        DurableWork work = work(1);
        AtomicReference<String> taskId = new AtomicReference<>();
        AtomicReference<String> kind = new AtomicReference<>();
        AtomicReference<String> messageId = new AtomicReference<>();
        DurableWorkHandler handler = new DurableWorkHandler() {
            @Override public String kind() { return "TEST"; }
            @Override public void execute(DurableWorkContext context, String payload) {
                taskId.set(MDC.get(AssessmentFlowMdc.TASK_ID));
                kind.set(MDC.get(AssessmentFlowMdc.KIND));
                messageId.set(MDC.get(AssessmentFlowMdc.MESSAGE_ID));
            }
        };

        worker.execute(handler, work);

        assertEquals("work-1", taskId.get());
        assertEquals("TEST", kind.get());
        assertEquals("task-1", messageId.get());
    }

    @Test
    void mdcIsRestoredAfterHandlerCompletes() {
        MDC.put(AssessmentFlowMdc.TASK_ID, "outer-task");
        try {
            worker.execute(handler(0, false, null), work(1));

            assertEquals("outer-task", MDC.get(AssessmentFlowMdc.TASK_ID));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void mdcIsRestoredEvenWhenHandlerFails() {
        DurableWorkHandler handler = new DurableWorkHandler() {
            @Override public String kind() { return "TEST"; }
            @Override public void execute(DurableWorkContext context, String payload) {
                throw new IllegalStateException("临时失败");
            }
        };

        worker.execute(handler, work(1));

        org.junit.jupiter.api.Assertions.assertNull(MDC.get(AssessmentFlowMdc.TASK_ID));
    }

    @Test
    void exhaustedCallbackStillRunsInsideFlowScope() {
        DurableWorkHandler handler = new DurableWorkHandler() {
            @Override public String kind() { return "TEST"; }
            @Override public void execute(DurableWorkContext context, String payload) {
                throw new IllegalStateException("临时失败");
            }
            @Override public void onExhausted(DurableWorkContext context, String payload,
                                               Throwable failure) {
                // 回调期间业务身份仍可从 MDC 读取
                if (!"TEST".equals(MDC.get(AssessmentFlowMdc.KIND))) {
                    throw new IllegalStateException("MDC 已被提前清理");
                }
            }
        };

        worker.execute(handler, work(3));
    }

    @Test
    void brokenFlowContextFallsBackToWorkIdentityWithoutChangingOutcome() {
        DurableWork work = work(1);
        DurableWorkHandler handler = new DurableWorkHandler() {
            @Override public String kind() { return "TEST"; }
            @Override public void execute(DurableWorkContext context, String payload) { }
            @Override public AssessmentFlowContext flowContext(DurableWorkContext context,
                                                               String payload) {
                throw new IllegalStateException("payload 解析失败");
            }
        };

        worker.execute(handler, work);

        verify(store).complete(work);
        verify(flowLogger).info(any(AssessmentFlowEvent.class));
    }

    @Test
    void completeFailureDoesNotBecomeBusinessRetry() {
        DurableWorkHandler handler = handler(0, false, null);
        doThrow(new IllegalStateException("lease 已失效")).when(store).complete(any());

        worker.execute(handler, work(1));

        verify(flowLogger, never()).info(any(AssessmentFlowEvent.class));
        verify(flowLogger, never()).warn(any(AssessmentFlowEvent.class));
        verify(store, never()).retry(any(), any());
        verify(store, never()).fail(any(), any());
    }

    @Test
    void retryTransitionFailureDoesNotEscapeExecution() {
        DurableWorkHandler handler = handler(0, true, null);
        doThrow(new IllegalStateException("lease 已失效")).when(store).retry(any(), any());

        worker.execute(handler, work(1));

        verify(store).retry(any(), any());
        verify(flowLogger, never()).warn(any(AssessmentFlowEvent.class));
    }

    @Test
    void failedTransitionFailureSkipsExhaustedCallback() {
        AtomicBoolean exhausted = new AtomicBoolean();
        DurableWorkHandler handler = handler(0, true, exhausted);
        doThrow(new IllegalStateException("lease 已失效")).when(store).fail(any(), any());

        worker.execute(handler, work(3));

        verify(store).fail(any(), any());
        assertFalse(exhausted.get());
        verify(flowLogger, never()).error(any(AssessmentFlowEvent.class));
    }

    @Test
    void pollLoopSurvivesClaimFailureAndProcessesNextTask() {
        properties.setPollInterval(Duration.ofMillis(10));
        DurableWorkHandler handler = handler(0, false, null);
        DurableWork claimed = work(1);
        worker.stop();
        worker = new DurableWorker(store, Collections.singletonList(handler), properties, flowLogger);
        when(store.claim(org.mockito.ArgumentMatchers.eq("TEST"),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("数据库短暂不可用"))
                .thenReturn(claimed)
                .thenReturn(null);

        worker.start();

        verify(store, timeout(1000).times(1)).complete(claimed);
        verify(store, timeout(1000).atLeast(2))
                .claim(org.mockito.ArgumentMatchers.eq("TEST"),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void pollLoopSurvivesStateTransitionFailureAndProcessesNextTask() {
        properties.setPollInterval(Duration.ofMillis(10));
        DurableWorkHandler handler = handler(0, false, null);
        DurableWork first = work(1);
        DurableWork second = new DurableWork("work-2", "processing", "TEST", "task-2", "payload",
                1, "worker-1", "lease-1", LocalDateTime.now().plusMinutes(15));
        worker.stop();
        worker = new DurableWorker(store, Collections.singletonList(handler), properties, flowLogger);
        when(store.claim(org.mockito.ArgumentMatchers.eq("TEST"),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(first)
                .thenReturn(second)
                .thenReturn(null);
        doThrow(new IllegalStateException("lease 已失效")).doNothing()
                .when(store).complete(any());

        worker.start();

        verify(store, timeout(1000).times(2)).complete(any());
        verify(store, never()).retry(any(), any());
    }

    @Test
    void flowContextIsExtractedFromHandlerForLogging() {
        AtomicReference<String> runId = new AtomicReference<>();
        DurableWorkHandler handler = new DurableWorkHandler() {
            @Override public String kind() { return "TEST"; }
            @Override public void execute(DurableWorkContext context, String payload) {
                runId.set(MDC.get(AssessmentFlowMdc.ANALYSIS_RUN_ID));
            }
            @Override public AssessmentFlowContext flowContext(DurableWorkContext context,
                                                               String payload) {
                return AssessmentFlowContext.fromWork(context)
                        .toBuilder().analysisRunId("run-1").build();
            }
        };

        worker.execute(handler, work(1));

        assertEquals("run-1", runId.get());
        verify(store).complete(any());
    }

    private DurableWorkHandler handler(int maxAttempts, boolean fail, AtomicBoolean exhausted) {
        return new DurableWorkHandler() {
            @Override public String kind() { return "TEST"; }
            @Override public int maxAttempts() { return maxAttempts; }
            @Override public void execute(DurableWorkContext context, String payload) {
                if (fail) { throw new IllegalStateException("temporary"); }
            }
            @Override public void onExhausted(DurableWorkContext context, String payload,
                                               Throwable failure) {
                if (exhausted != null) { exhausted.set(true); }
            }
        };
    }

    private DurableWork work(int attempts) {
        return new DurableWork("work-1", "processing", "TEST", "task-1", "payload",
                attempts, "worker-1", "lease-1", LocalDateTime.now().plusMinutes(15));
    }
}

