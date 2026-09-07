package com.riskwarning.org.task;

import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.utils.FileUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.AssessmentRepository;
import com.riskwarning.org.repository.FileRepository;
import com.riskwarning.org.service.AnalysisRunMessageDispatcher;
import com.riskwarning.org.service.AnalysisRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

@Component
@Slf4j
public class ExecuteQueueTask {

    private ExecutorService executorService = Executors.newFixedThreadPool(10);


    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AnalysisRunService analysisRunService;

    @Autowired
    private AnalysisRunMessageDispatcher analysisRunMessageDispatcher;

    @PostConstruct
    public void consumeTransferQueue() {
        executorService.execute(() -> {
            while (true) {
                UploadConfirmDto uploadConfirmDto = (UploadConfirmDto) redisUtil.rightPop(RedisKey.REDIS_KEY_CONFIRMED_FILE_QUEUE);
                if(uploadConfirmDto == null) {
                    try {
                        Thread.sleep(1000); // 如果队列为空，等待1秒后再检查
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    continue;
                }
                executorService.execute(() -> {
                    transactionTemplate.execute(status -> {
                        try {
                            Map<Object, Object> uploadFileMap = redisUtil.hmget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, uploadConfirmDto.getProjectId()));
                            Assessment assessment = Assessment.builder()
                                    .projectId(uploadConfirmDto.getProjectId())
                                    .assessmentDate(LocalDateTime.now())
                                    .overallScore(null)
                                    .overallRiskLevel(null)
                                    .details(null)
                                    .status(AssessmentStatusEnum.TO_BE_ASSESSED)
                                    .createdAt(LocalDateTime.now())
                                    .build();
                            assessmentRepository.save(assessment);

                            // 一文件一行：每个上传文件独立建档，id 即 sourceDocumentId
                            List<SourceDocumentRef> savedDocuments = new ArrayList<>();
                            for(Object value : uploadFileMap.values()) {
                                UploadFileDto uploadFileDto = (UploadFileDto) value;
                                // todo: 文件需要保存到远程存储，这里只是本地合并，后续需要改造
                                String targetFilePath = Constants.getPersistFileDirPath(uploadFileDto.getProjectId())
                                        + StringUtils.generateFileName(uploadFileDto.getProjectId(), uploadFileDto.getUploadId()) + "." + uploadFileDto.getFileSuffix();
                                FileUtils.union(
                                        uploadFileDto.getFilePath(),
                                        targetFilePath,
                                        true
                                );

                                ProjectFile projectFile = fileRepository.save(ProjectFile.builder()
                                        .projectId(uploadConfirmDto.getProjectId())
                                        .userId(uploadFileDto.getUserId())
                                        .assessmentId(assessment.getId())
                                        .filePath(targetFilePath)
                                        .originalFileName(uploadFileDto.getOriginalFileName())
                                        .build());
                                savedDocuments.add(new SourceDocumentRef(
                                        projectFile.getId(), targetFilePath));
                            }


                            if (savedDocuments.isEmpty()) {
                                throw new BusinessException("本次评估没有可处理的源文件");
                            }
                            AnalysisScope analysisScope = new AnalysisScope(
                                    uploadConfirmDto.getProjectId(),
                                    assessment.getId(),
                                    UUID.randomUUID().toString()
                            );
                            analysisRunService.start(analysisScope, LocalDateTime.now());
                            // 发送消息队列
                            BehaviorProcessingTaskMessage behaviorProcessingTaskMessage =
                                    BehaviorProcessingTaskMessage.forDocuments(
                                    StringUtils.generateMessageId(),
                                    String.valueOf(System.currentTimeMillis()),
                                    StringUtils.generateTraceId(),
                                    uploadConfirmDto.getUserId(),
                                    uploadConfirmDto.getProjectId(),
                                    assessment.getId(),
                                    analysisScope.getAnalysisRunId(),
                                    DataSourceTypeEnum.FILE_UPLOAD,
                                    savedDocuments
                            );
                            analysisRunMessageDispatcher.dispatchAfterCommit(
                                    analysisScope, behaviorProcessingTaskMessage);
                            return true;
                        } catch (Exception e) {
                            log.error("Error processing file upload confirm queue", e);
                            if(uploadConfirmDto != null && uploadConfirmDto.getRetryCount() >= Constants.UPLOAD_CONFIRM_RETRY_LIMIT) {
                                throw new BusinessException("项目" + uploadConfirmDto.getProjectId() + "文件确认失败，重试次数已达上限");
                            }
                            return false;
                        } finally {
                            // 删除缓存
                            redisUtil.del(String.format(RedisKey.REDIS_KEY_FILE, uploadConfirmDto.getProjectId()));
                            redisUtil.del(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, uploadConfirmDto.getProjectId()));
                            FileUtils.delDirectory(Constants.getTempFileDirPath(uploadConfirmDto.getProjectId(), ""));
                        }
                    });
                });
            }
        });

    }

}
