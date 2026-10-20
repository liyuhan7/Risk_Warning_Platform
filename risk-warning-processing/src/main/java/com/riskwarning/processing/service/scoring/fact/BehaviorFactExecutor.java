package com.riskwarning.processing.service.scoring.fact;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/** 进程共享的行为并发闸门；任务结束前不会启动指标聚合和报告。 */
@Component
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class BehaviorFactExecutor implements DisposableBean {
    private final ExecutorService executor;
    private final Semaphore permits;

    public BehaviorFactExecutor(BehaviorFactProperties properties) {
        properties.validate(); permits = new Semaphore(properties.getFactConcurrency(), true);
        executor = Executors.newFixedThreadPool(properties.getFactConcurrency(), runnable -> {
            Thread thread = new Thread(runnable, "behavior-fact-worker"); thread.setDaemon(true); return thread;
        });
    }

    public void execute(List<Runnable> jobs) {
        List<Future<?>> futures = new ArrayList<>(); RuntimeException failure = null;
        try {
            for (Runnable job : jobs) {
                permits.acquire();
                try { futures.add(executor.submit(() -> { try { job.run(); } finally { permits.release(); } })); }
                catch (RuntimeException rejected) { permits.release(); failure = rejected; break; }
            }
            // 即使某个行为失败，也等待已提交任务退出，防止终态后继续写入。
            for (Future<?> future : futures) {
                try { future.get(); }
                catch (ExecutionException error) { if (failure == null) failure = error.getCause() instanceof RuntimeException ? (RuntimeException)error.getCause() : new IllegalStateException("事实任务失败", error.getCause()); }
            }
        } catch (InterruptedException interrupted) {
            futures.forEach(future -> future.cancel(true)); Thread.currentThread().interrupt(); throw new IllegalStateException("事实任务被中断", interrupted);
        }
        if (failure != null) throw failure;
    }

    @Override public void destroy() { executor.shutdownNow(); }
}
