package com.riskwarning.common.reliability;

import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowMdc;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 事务外执行 Handler，并用独立短事务维持 lease。 */
@Slf4j
public class DurableWorker implements SmartLifecycle {

    private final DurableWorkStore store;
    private final Map<String, DurableWorkHandler> handlers;
    private final DurableWorkProperties properties;
    private final AssessmentFlowLogger flowLogger;
    private final String workerId;
    private final ExecutorService workers;
    private final ScheduledExecutorService heartbeats;
    private volatile boolean running;

    public DurableWorker(DurableWorkStore store, List<DurableWorkHandler> handlers,
                         DurableWorkProperties properties, AssessmentFlowLogger flowLogger) {
        this.store = store;
        this.properties = properties;
        this.flowLogger = flowLogger;
        this.workerId = UUID.randomUUID().toString();
        this.handlers = uniqueHandlers(handlers);
        this.workers = Executors.newFixedThreadPool(properties.getWorkerThreads(), runnable -> {
            Thread thread = new Thread(runnable, "durable-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeats = Executors.newScheduledThreadPool(properties.getWorkerThreads(), runnable -> {
            Thread thread = new Thread(runnable, "durable-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public synchronized void start() {
        if (running) { return; }
        properties.validate();
        running = true;
        for (int index = 0; index < properties.getWorkerThreads(); index++) {
            workers.submit(this::pollLoop);
        }
    }

    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (!pollOnce()) {
                    sleep(properties.getPollInterval().toMillis());
                }
            } catch (RuntimeException failure) {
                // 领取或调度异常不得终止常驻线程；短暂退避后继续轮询。
                log.error("Durable Worker 轮询异常，将继续重试: workerId={}", workerId, failure);
                sleep(properties.getPollInterval().toMillis());
            }
        }
    }

    private boolean pollOnce() {
        boolean claimedAny = false;
        for (DurableWorkHandler handler : handlers.values()) {
            DurableWork work = store.claim(handler.kind(), workerId);
            if (work != null) {
                claimedAny = true;
                logClaimed(handler, work);
                execute(handler, work);
            }
        }
        return claimedAny;
    }

    void execute(DurableWorkHandler handler, DurableWork work) {
        Future<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (!store.heartbeat(work)) {
                    log.error("Durable Work 续租失败: workId={}, kind={}", work.getId(), work.getKind());
                }
            } catch (RuntimeException exception) {
                log.error("Durable Work 续租异常: workId={}, kind={}",
                        work.getId(), work.getKind(), exception);
            }
        }, properties.getHeartbeatInterval().toMillis(),
                properties.getHeartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        DurableWorkContext context = new DurableWorkContext(work);
        AssessmentFlowContext flow = extractFlow(handler, context, work.getPayload());
        long startedAt = System.currentTimeMillis();
        try (AssessmentFlowMdc.Scope ignored = AssessmentFlowMdc.open(flow)) {
            Exception businessFailure = null;
            try {
                handler.execute(context, work.getPayload());
            } catch (Exception failure) {
                businessFailure = failure;
            }
            if (businessFailure == null) {
                complete(work, flow, startedAt);
            } else {
                handleBusinessFailure(handler, context, work, flow, startedAt, businessFailure);
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void complete(DurableWork work, AssessmentFlowContext flow, long startedAt) {
        try {
            store.complete(work);
            flowLogger.info(doneEvent(flow, work, startedAt, null));
        } catch (RuntimeException transitionFailure) {
            // Handler 已成功，状态转换失败通常表示租约已失效或数据库暂时不可用。
            // 此时不得再将其当成业务失败执行 retry/fail，否则会二次覆盖任务所有权。
            log.error("Durable Work 完成状态转换失败，等待当前所有者或租约恢复: workId={}, kind={}",
                    work.getId(), work.getKind(), transitionFailure);
        }
    }

    private void handleBusinessFailure(DurableWorkHandler handler, DurableWorkContext context,
                                       DurableWork work, AssessmentFlowContext flow,
                                       long startedAt, Exception failure) {
        int maxAttempts = handler.maxAttempts() > 0
                ? handler.maxAttempts() : properties.getDefaultMaxAttempts();
        if (work.getAttempts() >= maxAttempts) {
            if (!transitionToFailed(work, flow, startedAt, failure)) {
                return;
            }
            try {
                handler.onExhausted(context, work.getPayload(), failure);
            } catch (RuntimeException callbackFailure) {
                log.error("Durable Work 耗尽回调失败: workId={}, kind={}",
                        work.getId(), work.getKind(), callbackFailure);
            }
            return;
        }
        try {
            store.retry(work, failure);
            flowLogger.warn(flowEvent(AssessmentFlowStage.DURABLE_RETRY,
                    AssessmentFlowStatus.RETRY, flow, work, startedAt, failure));
        } catch (RuntimeException transitionFailure) {
            log.error("Durable Work 重试状态转换失败，等待当前所有者或租约恢复: workId={}, kind={}",
                    work.getId(), work.getKind(), transitionFailure);
        }
    }

    private boolean transitionToFailed(DurableWork work, AssessmentFlowContext flow,
                                       long startedAt, Exception failure) {
        try {
            store.fail(work, failure);
            flowLogger.error(flowEvent(AssessmentFlowStage.DURABLE_FAILED,
                    AssessmentFlowStatus.FAILED, flow, work, startedAt, failure));
            return true;
        } catch (RuntimeException transitionFailure) {
            log.error("Durable Work 失败状态转换失败，跳过耗尽回调: workId={}, kind={}",
                    work.getId(), work.getKind(), transitionFailure);
            return false;
        }
    }

    /** 上下文提取失败只降级到任务固有身份，不得改变任务执行结果。 */
    private AssessmentFlowContext extractFlow(DurableWorkHandler handler,
                                              DurableWorkContext context, String payload) {
        try {
            AssessmentFlowContext flow = handler.flowContext(context, payload);
            return flow != null ? flow : AssessmentFlowContext.fromWork(context);
        } catch (Exception exception) {
            log.warn("AssessmentFlow 上下文提取失败，使用任务固有身份: workId={}, kind={}",
                    context.getWorkId(), context.getKind(), exception);
            return AssessmentFlowContext.fromWork(context);
        }
    }

    private void logClaimed(DurableWorkHandler handler, DurableWork work) {
        AssessmentFlowContext flow = AssessmentFlowContext.fromWork(
                new DurableWorkContext(work));
        flowLogger.info(AssessmentFlowEvent.builder(
                        AssessmentFlowStage.DURABLE_CLAIM, AssessmentFlowStatus.SUCCEEDED)
                .taskId(flow.getTaskId()).kind(flow.getKind())
                .attempt(flow.getAttempt()).messageId(flow.getMessageId())
                .build());
    }

    private AssessmentFlowEvent doneEvent(AssessmentFlowContext flow, DurableWork work,
                                          long startedAt, Throwable failure) {
        return flowEvent(AssessmentFlowStage.DURABLE_DONE, AssessmentFlowStatus.SUCCEEDED,
                flow, work, startedAt, failure);
    }

    private AssessmentFlowEvent flowEvent(AssessmentFlowStage stage, AssessmentFlowStatus status,
                                          AssessmentFlowContext flow, DurableWork work,
                                          long startedAt, Throwable failure) {
        return AssessmentFlowEvent.builder(stage, status)
                .projectId(flow.getProjectId()).assessmentId(flow.getAssessmentId())
                .analysisRunId(flow.getAnalysisRunId()).taskId(flow.getTaskId())
                .kind(flow.getKind()).attempt(flow.getAttempt())
                .messageId(flow.getMessageId()).traceId(flow.getTraceId())
                .elapsedMs(System.currentTimeMillis() - startedAt)
                .failure(failure)
                .build();
    }

    private Map<String, DurableWorkHandler> uniqueHandlers(List<DurableWorkHandler> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, DurableWorkHandler> result = new HashMap<>();
        for (DurableWorkHandler handler : source) {
            String kind = handler.kind();
            if (kind == null || kind.trim().isEmpty() || result.put(kind, handler) != null) {
                throw new IllegalStateException("Durable Work Handler kind 为空或重复: " + kind);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        workers.shutdownNow();
        heartbeats.shutdownNow();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
}
