package com.riskwarning.processing.task;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.common.reliability.DurableWorkHandler;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.processing.entity.dto.ProcessedDocument;
import com.riskwarning.processing.service.AnalysisRunFailureService;
import com.riskwarning.processing.service.DocumentProcessingService;
import com.riskwarning.processing.service.EvidenceExtractionService;
import com.riskwarning.processing.service.FactExtractionPipeline;
import com.riskwarning.processing.service.SourceDocumentScopeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Behavior 处理持久任务：文档处理、Evidence 持久化、事实抽取与 Behavior
 * 写入 ES 完成后，用稳定派生 ID 把指标任务写入 Kafka Outbox。
 * 业务异常直接外抛，由 Durable Work 重试；重试耗尽后才把 AnalysisRun
 * 标记为 FAILED，临时故障期间运行状态保持 RUNNING。
 * 同一任务重跑依赖稳定 messageId 与持久化幂等，不依赖只执行一次的假设。
 */
@Component
@Slf4j
public class BehaviorProcessingWorkHandler implements DurableWorkHandler {

    public static final String KIND = "BEHAVIOR_PROCESSING";

    private final ProcessingMessageCodec codec;
    private final DocumentProcessingService documentProcessingService;
    private final SourceDocumentScopeValidator sourceDocumentScopeValidator;
    private final EvidenceExtractionService evidenceExtractionService;
    private final FactExtractionPipeline factExtractionPipeline;
    private final KafkaOutbox kafkaOutbox;
    private final AnalysisRunFailureService analysisRunFailureService;

    public BehaviorProcessingWorkHandler(ProcessingMessageCodec codec,
                                         DocumentProcessingService documentProcessingService,
                                         SourceDocumentScopeValidator sourceDocumentScopeValidator,
                                         EvidenceExtractionService evidenceExtractionService,
                                         FactExtractionPipeline factExtractionPipeline,
                                         KafkaOutbox kafkaOutbox,
                                         AnalysisRunFailureService analysisRunFailureService) {
        this.codec = codec;
        this.documentProcessingService = documentProcessingService;
        this.sourceDocumentScopeValidator = sourceDocumentScopeValidator;
        this.evidenceExtractionService = evidenceExtractionService;
        this.factExtractionPipeline = factExtractionPipeline;
        this.kafkaOutbox = kafkaOutbox;
        this.analysisRunFailureService = analysisRunFailureService;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void execute(DurableWorkContext context, String payload) {
        BehaviorProcessingTaskMessage message = codec.decodeBehavior(payload);
        if (message.getType() != DataSourceTypeEnum.FILE_UPLOAD) {
            throw new IllegalStateException("Behavior 任务仅支持 FILE_UPLOAD 类型: " + message.getType());
        }
        AnalysisScope analysisScope = MessageTask.requireScope(message);
        List<SourceDocumentRef> documents = MessageTask.requireDocuments(message);
        List<ProcessedDocument> internalFiles = new ArrayList<>();
        try {
            // 步骤1: 文档处理与行为提取
            sourceDocumentScopeValidator.validate(analysisScope, documents);
            internalFiles = documentProcessingService.processDocuments(analysisScope, documents);
            evidenceExtractionService.extractAndPersist(analysisScope, internalFiles);

            // 步骤2: 从权威 Evidence 抽取结构化事实并写入 ES
            factExtractionPipeline.process(analysisScope, documents);

            // 步骤3: 指标任务经 Outbox 投递，messageId 由上游消息稳定派生
            kafkaOutbox.enqueue(MessageTask.createIndicatorMessage(message));
            log.info("[Durable 行为处理完成] workId={}, analysisRunId={}, projectId={}",
                    context.getWorkId(), analysisScope.getAnalysisRunId(), analysisScope.getProjectId());
        } finally {
            // 内部文件生命周期归属本次任务执行，无论成败都要清理
            cleanupInternalFiles(analysisScope, internalFiles);
        }
    }

    private void cleanupInternalFiles(AnalysisScope scope, List<ProcessedDocument> internalFiles) {
        for (ProcessedDocument internalFile : internalFiles) {
            try {
                Files.deleteIfExists(Paths.get(internalFile.getInternalFilePath()));
            } catch (Exception cleanupException) {
                log.warn("清理内部文件失败: analysisRunId={}, path={}",
                        scope.getAnalysisRunId(), internalFile.getInternalFilePath(), cleanupException);
            }
        }
    }

    /** 从载荷恢复链路身份；解析失败由 Worker 降级到任务固有身份。 */
    @Override
    public AssessmentFlowContext flowContext(DurableWorkContext context, String payload) {
        BehaviorProcessingTaskMessage message = codec.decodeBehavior(payload);
        return AssessmentFlowContext.fromWork(context)
                .toBuilder()
                .projectId(message.getProjectId()).assessmentId(message.getAssessmentId())
                .analysisRunId(message.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId())
                .build();
    }

    /**
     * 重试耗尽后按载荷中的原始作用域终止分析运行；analysisRunId 全程不变。
     * 载荷损坏或标记动作失败只记录日志，不改变 Durable Work 已 FAILED 的事实。
     */
    @Override
    public void onExhausted(DurableWorkContext context, String payload, Throwable failure) {
        AnalysisScope scope = null;
        try {
            scope = MessageTask.requireScope(codec.decodeBehavior(payload));
        } catch (Exception decodeException) {
            log.error("[Durable 行为处理耗尽] 载荷解析失败，跳过分析运行标记: workId={}",
                    context.getWorkId(), decodeException);
            return;
        }
        try {
            analysisRunFailureService.markFailed(scope, LocalDateTime.now());
        } catch (Exception markException) {
            log.error("[Durable 行为处理耗尽] 标记分析运行失败异常: workId={}, analysisRunId={}",
                    context.getWorkId(), scope.getAnalysisRunId(), markException);
        }
        log.error("[Durable 行为处理重试耗尽] workId={}, analysisRunId={}, projectId={}",
                context.getWorkId(), scope.getAnalysisRunId(), scope.getProjectId(), failure);
    }
}
