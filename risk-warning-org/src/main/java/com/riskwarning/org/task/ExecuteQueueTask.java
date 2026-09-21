package com.riskwarning.org.task;

import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.service.UploadConfirmCleanup;
import com.riskwarning.org.service.UploadConfirmProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 上传确认兼容消费者：未启用可靠链（assessment.reliability.enabled=false）时，
 * 在本进程内直接执行业务并 ACK。业务幂等与清理逻辑与 Durable 链共用同一实现。
 * PROCESSING 是单一恢复槽位，只允许一个消费线程串行执行。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled", havingValue = "false", matchIfMissing = true)
public class ExecuteQueueTask {

    private static final long POLL_INTERVAL_MILLIS = 1000L;

    private final UploadTaskQueue uploadTaskQueue;
    private final UploadConfirmProcessor processor;
    private final UploadConfirmCleanup cleanup;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public ExecuteQueueTask(UploadTaskQueue uploadTaskQueue,
            UploadConfirmProcessor processor,
            UploadConfirmCleanup cleanup) {
        this.uploadTaskQueue = uploadTaskQueue;
        this.processor = processor;
        this.cleanup = cleanup;
    }

    @PostConstruct
    public void start() {
        running.set(true);
        workerThread = new Thread(this::runLoop, "upload-confirm-consumer");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!consumeOnce()) {
                    sleepQuietly(POLL_INTERVAL_MILLIS);
                }
            } catch (RuntimeException exception) {
                log.error("上传确认消费循环异常，稍后重试", exception);
                sleepQuietly(POLL_INTERVAL_MILLIS);
            }
        }
    }

    /** 单步消费，便于测试与循环复用；返回是否领取到了任务。 */
    boolean consumeOnce() {
        ClaimedRedisItem<UploadConfirmDto> claimed = uploadTaskQueue.claim();
        if (claimed == null) {
            return false;
        }
        try {
            processor.process(claimed.getValue());
            cleanup.cleanup(claimed.getValue());
            // 清理成功后才 ACK：清理失败时任务留在 PROCESSING，重试只补做清理
            uploadTaskQueue.acknowledge(claimed);
        } catch (Exception exception) {
            // 不 ACK：任务留在 PROCESSING，下一轮优先恢复同一任务
            log.error("上传确认任务执行失败，保留在 PROCESSING 等待重试", exception);
            sleepQuietly(POLL_INTERVAL_MILLIS);
        }
        return true;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
