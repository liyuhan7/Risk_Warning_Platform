package com.riskwarning.org.service;

import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.utils.FileUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.AssessmentRepository;
import com.riskwarning.org.repository.FileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 上传确认业务事务：文件合并、评估建档、运行创建与首条任务消息登记。
 * 以 sourceTaskId 幂等，同一上传任务重复执行不会重复建档或重复派发。
 */
@Service
@Slf4j
public class UploadConfirmProcessor {

    private final AssessmentRepository assessmentRepository;
    private final FileRepository fileRepository;
    private final AnalysisRunService analysisRunService;
    private final AnalysisRunMessageDispatcher analysisRunMessageDispatcher;
    private final TransactionTemplate transactions;
    private final RedisUtil redisUtil;
    private final AssessmentFlowLogger flowLogger;

    public UploadConfirmProcessor(AssessmentRepository assessmentRepository,
                                  FileRepository fileRepository,
                                  AnalysisRunService analysisRunService,
                                  AnalysisRunMessageDispatcher analysisRunMessageDispatcher,
                                  TransactionTemplate transactions,
                                  RedisUtil redisUtil,
                                  AssessmentFlowLogger flowLogger) {
        this.assessmentRepository = assessmentRepository;
        this.fileRepository = fileRepository;
        this.analysisRunService = analysisRunService;
        this.analysisRunMessageDispatcher = analysisRunMessageDispatcher;
        this.transactions = transactions;
        this.redisUtil = redisUtil;
        this.flowLogger = flowLogger;
    }

    /** 幂等执行；返回评估 ID 与是否为已提交任务的重复执行。 */
    public UploadConfirmOutcome process(UploadConfirmDto task) {
        requireProject(task);
        List<UploadFileDto> files = resolveFiles(task);
        String taskId = task.getTaskId() != null && !task.getTaskId().trim().isEmpty()
                ? task.getTaskId()
                : StringUtils.deriveUploadTaskId(task.getProjectId(), uploadIdsOf(files));
        try {
            return transactions.execute(status -> executeBusiness(task, files, taskId));
        } catch (DataIntegrityViolationException exception) {
            // 撞唯一索引说明另一执行已提交同一上传任务，以数据库为准判断
            Optional<Assessment> committed = assessmentRepository.findBySourceTaskId(taskId);
            if (committed.isPresent()) {
                log.info("上传任务已有提交记录，跳过重复建档: taskId={}, assessmentId={}",
                        taskId, committed.get().getId());
                return UploadConfirmOutcome.alreadyCompleted(committed.get().getId());
            }
            throw exception;
        }
    }

    private UploadConfirmOutcome executeBusiness(UploadConfirmDto task,
                                                 List<UploadFileDto> files, String taskId) {
        Optional<Assessment> committed = assessmentRepository.findBySourceTaskId(taskId);
        if (committed.isPresent()) {
            // 前次执行的业务事务已提交，不重复建档、不重复派发
            log.info("上传任务已有提交记录，跳过重复建档: taskId={}, assessmentId={}",
                    taskId, committed.get().getId());
            flowLogger.info(AssessmentFlowEvent.builder(
                            AssessmentFlowStage.ANALYSIS_RUN_START, AssessmentFlowStatus.DUPLICATE)
                    .projectId(task.getProjectId()).taskId(taskId)
                    .assessmentId(committed.get().getId())
                    .build());
            return UploadConfirmOutcome.alreadyCompleted(committed.get().getId());
        }
        LocalDateTime now = LocalDateTime.now();
        Assessment assessment = Assessment.builder()
                .projectId(task.getProjectId())
                .sourceTaskId(taskId)
                .assessmentDate(now)
                .overallScore(null)
                .overallRiskLevel(null)
                .details(null)
                .status(AssessmentStatusEnum.TO_BE_ASSESSED)
                .createdAt(now)
                .build();
        // 尽早落库触发唯一索引，避免文件合并后才发现重复
        assessment = assessmentRepository.saveAndFlush(assessment);

        // 一文件一行：每个上传文件独立建档，id 即 sourceDocumentId
        List<SourceDocumentRef> documents = new ArrayList<>();
        for (UploadFileDto uploadFileDto : files) {
            // todo: 文件需要保存到远程存储，这里只是本地合并，后续需要改造
            String targetFilePath = Constants.getPersistFileDirPath(uploadFileDto.getProjectId())
                    + StringUtils.generateFileName(uploadFileDto.getProjectId(), uploadFileDto.getUploadId())
                    + "." + uploadFileDto.getFileSuffix();
            // 成功清理前保留源分片，重试时覆盖同一稳定目标文件
            FileUtils.union(uploadFileDto.getFilePath(), targetFilePath, false);

            ProjectFile projectFile = fileRepository.save(ProjectFile.builder()
                    .projectId(task.getProjectId())
                    .userId(uploadFileDto.getUserId())
                    .assessmentId(assessment.getId())
                    .filePath(targetFilePath)
                    .originalFileName(uploadFileDto.getOriginalFileName())
                    .build());
            documents.add(new SourceDocumentRef(projectFile.getId(), targetFilePath));
        }
        if (documents.isEmpty()) {
            throw new BusinessException("本次评估没有可处理的源文件");
        }

        AnalysisScope analysisScope = new AnalysisScope(
                task.getProjectId(), assessment.getId(), UUID.randomUUID().toString());
        try {
            analysisRunService.start(analysisScope, now);
        } catch (RuntimeException failure) {
            // 日志动作自身不得吞掉或替换业务异常
            flowLogger.error(AssessmentFlowEvent.builder(
                            AssessmentFlowStage.ANALYSIS_RUN_START, AssessmentFlowStatus.FAILED)
                    .projectId(task.getProjectId()).assessmentId(assessment.getId())
                    .taskId(taskId).analysisRunId(analysisScope.getAnalysisRunId())
                    .failure(failure)
                    .build());
            throw failure;
        }
        flowLogger.info(AssessmentFlowEvent.builder(
                        AssessmentFlowStage.ANALYSIS_RUN_START, AssessmentFlowStatus.SUCCEEDED)
                .projectId(task.getProjectId()).assessmentId(assessment.getId())
                .taskId(taskId).analysisRunId(analysisScope.getAnalysisRunId())
                .build());
        // 首条任务消息从稳定任务身份确定性派生，重复执行不产生新的逻辑消息
        BehaviorProcessingTaskMessage behaviorProcessingTaskMessage =
                BehaviorProcessingTaskMessage.forDocuments(
                        StringUtils.deriveMessageId(taskId, "behavior"),
                        String.valueOf(System.currentTimeMillis()),
                        StringUtils.generateTraceId(),
                        task.getUserId(),
                        task.getProjectId(),
                        assessment.getId(),
                        analysisScope.getAnalysisRunId(),
                        DataSourceTypeEnum.FILE_UPLOAD,
                        documents);
        analysisRunMessageDispatcher.dispatch(analysisScope, behaviorProcessingTaskMessage);
        return UploadConfirmOutcome.created(assessment.getId());
    }

    /** 旧格式消息没有快照，回读 Redis 上传元数据。 */
    private List<UploadFileDto> resolveFiles(UploadConfirmDto task) {
        if (task.getFiles() != null && !task.getFiles().isEmpty()) {
            return task.getFiles();
        }
        Map<Object, Object> uploadFileMap = redisUtil.hmget(
                String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, task.getProjectId()));
        if (uploadFileMap == null || uploadFileMap.isEmpty()) {
            throw new BusinessException("上传任务不存在或已过期");
        }
        List<UploadFileDto> files = new ArrayList<>();
        for (Object value : uploadFileMap.values()) {
            files.add((UploadFileDto) value);
        }
        files.sort(Comparator.comparing(UploadFileDto::getUploadId));
        return files;
    }

    private List<String> uploadIdsOf(List<UploadFileDto> files) {
        List<String> uploadIds = new ArrayList<>();
        for (UploadFileDto file : files) {
            uploadIds.add(file.getUploadId());
        }
        return uploadIds;
    }

    private void requireProject(UploadConfirmDto task) {
        if (task == null || task.getProjectId() == null) {
            throw new BusinessException("上传确认任务缺少项目标识");
        }
    }

    /** 执行结果：assessmentId 恒非空；alreadyCompleted 表示业务此前已提交。 */
    public static final class UploadConfirmOutcome {

        private final Long assessmentId;
        private final boolean alreadyCompleted;

        private UploadConfirmOutcome(Long assessmentId, boolean alreadyCompleted) {
            this.assessmentId = assessmentId;
            this.alreadyCompleted = alreadyCompleted;
        }

        public static UploadConfirmOutcome created(Long assessmentId) {
            return new UploadConfirmOutcome(assessmentId, false);
        }

        public static UploadConfirmOutcome alreadyCompleted(Long assessmentId) {
            return new UploadConfirmOutcome(assessmentId, true);
        }

        public Long getAssessmentId() { return assessmentId; }
        public boolean isAlreadyCompleted() { return alreadyCompleted; }
    }
}
