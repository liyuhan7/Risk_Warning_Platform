package com.riskwarning.org.task;

import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.common.reliability.DurableWorkHandler;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.service.UploadConfirmCleanup;
import com.riskwarning.org.service.UploadConfirmProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 上传确认持久任务：业务事务幂等提交后执行恢复输入清理。
 * 清理失败会随任务重试；重试路径命中 sourceTaskId 幂等检查后只补做清理。
 */
@Component
@Slf4j
public class UploadConfirmWorkHandler implements DurableWorkHandler {

    public static final String KIND = "UPLOAD_CONFIRM";

    private final UploadConfirmTaskCodec codec;
    private final UploadConfirmProcessor processor;
    private final UploadConfirmCleanup cleanup;

    public UploadConfirmWorkHandler(UploadConfirmTaskCodec codec,
                                    UploadConfirmProcessor processor,
                                    UploadConfirmCleanup cleanup) {
        this.codec = codec;
        this.processor = processor;
        this.cleanup = cleanup;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void execute(DurableWorkContext context, String payload) {
        UploadConfirmDto task = codec.decode(payload);
        UploadConfirmProcessor.UploadConfirmOutcome outcome = processor.process(task);
        if (outcome.isAlreadyCompleted()) {
            log.info("上传确认任务为重复执行，仅补做清理: workId={}, assessmentId={}",
                    context.getWorkId(), outcome.getAssessmentId());
        }
        cleanup.cleanup(task);
    }

    /** 上传处理重试耗尽只输出可接入告警的稳定错误标记；评估停留在待评估状态等待人工重放。 */
    @Override
    public void onExhausted(DurableWorkContext context, String payload, Throwable failure) {
        log.error("[可靠链失败] 上传确认处理重试耗尽，业务未派发分析: workId={}", context.getWorkId(), failure);
    }

    /** 上传任务只有任务级身份，没有 analysisRunId。 */
    @Override
    public AssessmentFlowContext flowContext(DurableWorkContext context, String payload) {
        UploadConfirmDto task = codec.decode(payload);
        return AssessmentFlowContext.fromWork(context)
                .toBuilder()
                .projectId(task.getProjectId())
                .taskId(task.getTaskId())
                .build();
    }
}
