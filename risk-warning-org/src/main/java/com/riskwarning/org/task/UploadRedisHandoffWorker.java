package com.riskwarning.org.task;

import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.reliability.DurableWorkStore;
import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 上传确认 Redis→PostgreSQL 移交器：claim → DurableWork 入库 → ACK。
 * PROCESSING 是单一恢复槽位，只允许一个移交线程串行执行。
 * Durable Work 入库失败不 ACK，任务保留在 PROCESSING 等待重试。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled", havingValue = "true")
public class UploadRedisHandoffWorker {

    private static final long POLL_INTERVAL_MILLIS = 1000L;

    /** 移交失败尝试上限；达到后任务移入 DEAD，避免永久错误阻塞单槽位队列。 */
    static final int MAX_ENQUEUE_ATTEMPTS = 5;

    /** 失败计数 TTL：超过该窗口未复现的临时故障不参与累计。 */
    static final long FAILURE_COUNTER_TTL_SECONDS = 3600L;

    private final UploadTaskQueue uploadTaskQueue;
    private final DurableWorkStore durableWorkStore;
    private final UploadConfirmTaskCodec codec;
    private final RedisUtil redisUtil;
    private final AssessmentFlowLogger flowLogger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public UploadRedisHandoffWorker(UploadTaskQueue uploadTaskQueue,
            DurableWorkStore durableWorkStore,
            UploadConfirmTaskCodec codec,
            RedisUtil redisUtil,
            AssessmentFlowLogger flowLogger) {
        this.uploadTaskQueue = uploadTaskQueue;
        this.durableWorkStore = durableWorkStore;
        this.codec = codec;
        this.redisUtil = redisUtil;
        this.flowLogger = flowLogger;
    }

    @PostConstruct
    public void start() {
        running.set(true);
        workerThread = new Thread(this::runLoop, "upload-redis-handoff");
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
                if (!handoffOnce()) {
                    sleepQuietly(POLL_INTERVAL_MILLIS);
                }
            } catch (RuntimeException exception) {
                log.error("上传确认移交循环异常，稍后重试", exception);
                sleepQuietly(POLL_INTERVAL_MILLIS);
            }
        }
    }

    /** 单步移交，便于测试与循环复用；返回是否领取到了任务。 */
    boolean handoffOnce() {
        ClaimedRedisItem<UploadConfirmDto> claimed = uploadTaskQueue.claim();
        if (claimed == null) {
            return false;
        }
        logClaimed(claimed.getValue());
        try {
            UploadConfirmDto task = normalize(claimed.getValue());
            durableWorkStore.enqueue(UploadConfirmWorkHandler.KIND, task.getTaskId(), codec.encode(task));
            uploadTaskQueue.acknowledge(claimed);
            uploadTaskQueue.clearFailure(uploadTaskQueue.payloadHash(claimed));
            flowLogger.info(AssessmentFlowEvent.builder(
                    AssessmentFlowStage.UPLOAD_HANDOFF, AssessmentFlowStatus.SUCCEEDED)
                    .projectId(task.getProjectId()).taskId(task.getTaskId())
                    .kind(UploadConfirmWorkHandler.KIND)
                    .build());
        } catch (UploadSnapshotMissingException exception) {
            log.error("上传确认任务缺少恢复输入，移入死信", exception);
            uploadTaskQueue.deadLetter(claimed, exception.getClass().getSimpleName());
            flowLogger.error(AssessmentFlowEvent.builder(
                    AssessmentFlowStage.UPLOAD_HANDOFF, AssessmentFlowStatus.FAILED)
                    .projectId(claimed.getValue() == null ? null : claimed.getValue().getProjectId())
                    .taskId(claimed.getValue() == null ? null : claimed.getValue().getTaskId())
                    .kind(UploadConfirmWorkHandler.KIND)
                    .failure(exception)
                    .build());
        } catch (Exception exception) {
            String payloadHash = uploadTaskQueue.payloadHash(claimed);
            long attempts = uploadTaskQueue.recordFailure(payloadHash, FAILURE_COUNTER_TTL_SECONDS);
            flowLogger.error(AssessmentFlowEvent.builder(
                    AssessmentFlowStage.UPLOAD_HANDOFF, AssessmentFlowStatus.FAILED)
                    .projectId(claimed.getValue() == null ? null : claimed.getValue().getProjectId())
                    .taskId(claimed.getValue() == null ? null : claimed.getValue().getTaskId())
                    .kind(UploadConfirmWorkHandler.KIND)
                    .failure(exception)
                    .build());
            if (attempts >= MAX_ENQUEUE_ATTEMPTS) {
                // 持久错误不应无限占用单一恢复槽位；移入 DEAD 后队列恢复放行。
                uploadTaskQueue.deadLetter(claimed, exception.getClass().getSimpleName());
                uploadTaskQueue.clearFailure(payloadHash);
                log.error("[可靠链失败] 上传确认移交达到尝试上限，任务移入 DEAD: attempts={}, taskId={}",
                        attempts, claimed.getValue() == null ? null : claimed.getValue().getTaskId(),
                        exception);
            } else {
                // 不 ACK：任务留在 PROCESSING，下一轮优先恢复同一任务
                log.error("上传确认任务写入 Durable Work 失败，保留在 PROCESSING 等待重试: attempts={}",
                        attempts, exception);
                sleepQuietly(POLL_INTERVAL_MILLIS);
            }
        }
        return true;
    }

    /** 领取即记录；旧格式任务可能缺 taskId，由占位符表达。 */
    private void logClaimed(UploadConfirmDto task) {
        flowLogger.info(AssessmentFlowEvent.builder(
                AssessmentFlowStage.UPLOAD_CLAIM, AssessmentFlowStatus.SUCCEEDED)
                .projectId(task == null ? null : task.getProjectId())
                .taskId(task == null ? null : task.getTaskId())
                .kind(UploadConfirmWorkHandler.KIND)
                .build());
    }

    /** 旧格式消息缺 taskId / 快照时，移交前从 Redis 冻结快照并派生稳定任务身份。 */
    private UploadConfirmDto normalize(UploadConfirmDto original) {
        if (original == null || original.getProjectId() == null) {
            throw new UploadSnapshotMissingException("上传确认任务缺少项目标识");
        }
        UploadConfirmDto task = original;
        if (task.getFiles() == null || task.getFiles().isEmpty()) {
            Map<Object, Object> uploads = redisUtil.hmget(String.format(
                    RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, task.getProjectId()));
            if (uploads == null || uploads.isEmpty()) {
                throw new UploadSnapshotMissingException(
                        "项目" + task.getProjectId() + "的上传元数据已过期，无法移交");
            }
            try {
                List<UploadFileDto> files = new ArrayList<>();
                for (Object value : uploads.values()) {
                    files.add((UploadFileDto) value);
                }
                files.sort(Comparator.comparing(UploadFileDto::getUploadId));
                task = UploadConfirmDto.builder()
                        .taskId(task.getTaskId())
                        .projectId(task.getProjectId())
                        .userId(task.getUserId())
                        .files(files)
                        .build();
            } catch (RuntimeException exception) {
                throw new UploadSnapshotMissingException(
                        "项目" + task.getProjectId() + "的上传元数据损坏，无法移交", exception);
            }
        }
        if (task.getTaskId() == null || task.getTaskId().trim().isEmpty()) {
            try {
                task.setTaskId(StringUtils.deriveUploadTaskId(
                        task.getProjectId(), uploadIdsOf(task.getFiles())));
            } catch (IllegalArgumentException exception) {
                throw new UploadSnapshotMissingException("上传确认快照缺少 uploadId，无法派生任务身份", exception);
            }
        }
        return task;
    }

    private List<String> uploadIdsOf(List<UploadFileDto> files) {
        List<String> uploadIds = new ArrayList<>();
        for (UploadFileDto file : files) {
            uploadIds.add(file.getUploadId());
        }
        return uploadIds;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
