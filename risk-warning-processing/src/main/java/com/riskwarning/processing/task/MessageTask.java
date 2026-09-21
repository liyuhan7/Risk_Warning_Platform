package com.riskwarning.processing.task;


import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.message.Message;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.processing.entity.dto.ProcessedDocument;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.BehaviorProcessingService;
import com.riskwarning.processing.service.DocumentProcessingService;
import com.riskwarning.processing.service.EvidenceExtractionService;
import com.riskwarning.processing.service.FactExtractionPipeline;
import com.riskwarning.processing.service.SourceDocumentScopeValidator;
import com.riskwarning.processing.service.P2RetrievalProcessingService;
import com.riskwarning.processing.config.P2ProcessingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 兼容 Kafka 消费者：未启用可靠链（assessment.reliability.enabled=false）时，
 * 沿用原有本地线程池处理流程；启用后由 DurableProcessingMessageTask 与
 * WorkHandler 接管，两者不会同时消费同一 topic。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class MessageTask {

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Autowired
    private BehaviorProcessingService behaviorProcessingService;

    @Autowired
    private SourceDocumentScopeValidator sourceDocumentScopeValidator;

    @Autowired
    private AnalysisRunFailureService analysisRunFailureService;

    @Autowired
    private EvidenceExtractionService evidenceExtractionService;

    @Autowired
    private FactExtractionPipeline factExtractionPipeline;

    @Autowired
    @Qualifier(value = "FileProcessTaskThreadPool")
    private ThreadPoolTaskExecutor fileThreadPoolExecutor;

    @Autowired
    @Qualifier(value = "BehaviorProcessTaskThreadPool")
    private ThreadPoolTaskExecutor behaviorThreadPoolExecutor;

    @Autowired
    private KafkaOutbox kafkaOutbox;

    @Autowired
    private P2RetrievalProcessingService p2RetrievalProcessingService;

    @Autowired
    private P2ProcessingProperties p2Properties = new P2ProcessingProperties();


    @KafkaListener(topics = "behavior_processing_tasks", groupId = "test-consumer",
            containerFactory = "durableKafkaListenerContainerFactory")
    public void onMessage(BehaviorProcessingTaskMessage message) {
        AnalysisScope analysisScope = requireScope(message);
        log.info("========================================");
        log.info("[Kafka消息接收] topic=behavior_processing_tasks, messageId={}, projectId={}",
                message.getMessageId(), message.getProjectId());
        log.info("[消息详情] type={}, fileCount={}", message.getType(),
                message.getFilePaths() != null ? message.getFilePaths().size() : 0);

        try {
            switch (message.getType()){
                case FILE_UPLOAD:
                    log.info("┌─────────────────────────────────────────────────────────────┐");
                    log.info("│  开始处理文件上传任务                                        │");
                    log.info("│  Project ID: {}", String.format("%-45s", message.getProjectId()) + "│");
                    log.info("└─────────────────────────────────────────────────────────────┘");

                    fileThreadPoolExecutor.execute(() -> {
                        long startTime = System.currentTimeMillis();
                        List<ProcessedDocument> internalFiles = new ArrayList<>();

                        try {
                            // 步骤1: 文档处理 - 提取行为
                            log.info("▶ 步骤 1/3: 开始文档处理和行为提取...");
                            sourceDocumentScopeValidator.validate(
                                    analysisScope, requireDocuments(message));
                            internalFiles = documentProcessingService.processDocuments(
                                    analysisScope, message.getDocuments());
                            log.info("✓ 步骤 1/3 完成: 文档处理成功，生成内部文件数={}", internalFiles.size());

                            evidenceExtractionService.extractAndPersist(analysisScope, internalFiles);
                            log.info("✓ Evidence 持久化完成: analysisRunId={}",
                                    analysisScope.getAnalysisRunId());

                            // 步骤2: 从权威 Evidence 抽取结构化事实并写入 ES
                            log.info("▶ 步骤 2/3: 开始事实抽取、分类、向量化和行为写入...");
                            int behaviorCount = factExtractionPipeline.process(
                                    analysisScope, requireDocuments(message)).size();
                            log.info("✓ 步骤 2/3 完成: 结构化行为已写入ES，数量={}", behaviorCount);

                            // 步骤3: 发送消息到指标计算任务队列
                            log.info("▶ 步骤 3/3: 发送消息到指标计算任务队列...");
                            Long assessmentId = message.getAssessmentId();
                            if (assessmentId == null) {
                                log.error("[CRITICAL] Kafka 消息中的 assessmentId 为 null! projectId={}", message.getProjectId());
                                throw new IllegalArgumentException("assessmentId must not be null in message");
                            }

                            // ✅ 创建指标计算任务消息
                            IndicatorCalculationTaskMessage indicatorMessage = createIndicatorMessage(message);

                            // 通过 Outbox 投递，与业务结果同事务可靠落库
                            kafkaOutbox.enqueue(indicatorMessage);
                            log.info("✓ 步骤 3/3 完成: 已发送指标计算任务消息, assessmentId={}", assessmentId);


                            long duration = System.currentTimeMillis() - startTime;
                            log.info("┌─────────────────────────────────────────────────────────────┐");
                            log.info("│  ✓ 行为处理流程执行成功                                     │");
                            log.info("│  Project ID: {}", String.format("%-45s", message.getProjectId()) + "│");
                            log.info("│  Assessment ID: {}", String.format("%-42s", assessmentId) + "│");
                            log.info("│  总耗时: {}", String.format("%-50s", duration + "ms") + "│");
                            log.info("│  下一步: 等待指标计算任务处理                               │");
                            log.info("└─────────────────────────────────────────────────────────────┘");

                        } catch (Exception e) {
                            markRunFailed(analysisScope, e);
                            log.error("✗ 处理文件上传任务失败: projectId={}, error={}",
                                    message.getProjectId(), e.getMessage(), e);
                        } finally {
                            log.info("Finished processing file upload for projectId: {}", message.getProjectId());
                            log.info("▶ 清理本次运行的内部文件: analysisRunId={}",
                                    analysisScope.getAnalysisRunId());
                            for (ProcessedDocument internalFile : internalFiles) {
                                try {
                                    Files.deleteIfExists(Paths.get(internalFile.getInternalFilePath()));
                                } catch (Exception cleanupException) {
                                    log.warn("清理内部文件失败: path={}",
                                            internalFile.getInternalFilePath(), cleanupException);
                                }
                            }
                            log.info("✓ 临时文件清理完成");
                        }
                    });
                    break;

                case KNOWLEDGE_QUERY:
                    log.info("[知识查询任务] 暂未实现");
                    // todo: 处理知识查询任务
                    break;

                case QUESTIONNAIRE:
                    log.info("[问卷任务] 暂未实现");
                    // todo: 处理问卷任务
                    break;

                default:
                    log.warn("[未知任务类型] type={}", message.getType());
                    break;
            }
        } catch (Exception e) {
            log.error("[Kafka消息处理异常] messageId={}, projectId={}, error={}",
                    message.getMessageId(), message.getProjectId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
        log.info("========================================");
    }

    @KafkaListener(topics = "indicator_calculation_tasks", groupId = "indicator-calculation-consumer",
            containerFactory = "durableKafkaListenerContainerFactory")
    public void onMessage(IndicatorCalculationTaskMessage message) {

        AnalysisScope analysisScope = requireScope(message);

        log.info("========================================");
        log.info("[Kafka消息接收] topic=indicator_calculation_tasks, messageId={}, projectId={}, assessmentId={}",
                message.getMessageId(), message.getProjectId(), message.getAssessmentId());
        log.info("┌─────────────────────────────────────────────────────────────┐");
        log.info("│  开始指标计算任务                                           │");
        log.info("│  Project ID: {}", String.format("%-45s", message.getProjectId()) + "│");
        log.info("│  Assessment ID: {}", String.format("%-42s", message.getAssessmentId()) + "│");
        log.info("└─────────────────────────────────────────────────────────────┘");

        behaviorThreadPoolExecutor.execute(() -> {
            try {
                log.info("▶ 开始行为评估和指标计算...");
                if (p2Properties.getMode() == P2ProcessingProperties.Mode.P2) {
                    p2RetrievalProcessingService.process(message, analysisScope);
                } else {
                    behaviorProcessingService.processProjectBehaviors(
                            message.getUserId(), message.getProjectId(), message.getAssessmentId(),
                            analysisScope.getAnalysisRunId(), message.getMessageId());
                }
            } catch (Exception e) {
                log.error("✗ 指标计算任务失败: projectId={}, assessmentId={}, error={}",
                        message.getProjectId(), message.getAssessmentId(), e.getMessage(), e);
                markRunFailed(analysisScope, e);
            }
        });
    }

    static AnalysisScope requireScope(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("processing message must not be null");
        }
        return new AnalysisScope(
                message.getProjectId(), message.getAssessmentId(), message.getAnalysisRunId());
    }

    static IndicatorCalculationTaskMessage createIndicatorMessage(BehaviorProcessingTaskMessage message) {
        AnalysisScope scope = requireScope(message);
        return new IndicatorCalculationTaskMessage(
                StringUtils.deriveMessageId(message.getMessageId(), "indicator"),
                String.valueOf(System.currentTimeMillis()),
                message.getTraceId(),
                message.getUserId(),
                scope.getProjectId(),
                scope.getAssessmentId(),
                scope.getAnalysisRunId()
        ).withDocuments(message.getDocuments());
    }

    static List<SourceDocumentRef> requireDocuments(
            BehaviorProcessingTaskMessage message) {
        if (message.getDocuments() == null || message.getDocuments().isEmpty()) {
            throw new IllegalArgumentException("processing message must contain scoped documents");
        }
        for (SourceDocumentRef document : message.getDocuments()) {
            if (document == null || document.getSourceDocumentId() == null
                    || document.getSourceDocumentId() <= 0 || document.getFilePath() == null
                    || document.getFilePath().trim().isEmpty()) {
                throw new IllegalArgumentException("source document identity is invalid");
            }
        }
        return message.getDocuments();
    }

    private void markRunFailed(AnalysisScope scope, Exception cause) {
        try {
            analysisRunFailureService.markFailed(scope, java.time.LocalDateTime.now());
        } catch (Exception failureException) {
            log.error("标记失败运行异常: analysisRunId={}", scope.getAnalysisRunId(),
                    failureException);
        }
        if (cause != null) {
            log.error("分析运行失败: analysisRunId={}", scope.getAnalysisRunId(), cause);
        }
    }
}
