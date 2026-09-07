package com.riskwarning.org.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.utils.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/** 在运行记录提交后投递首条任务消息。 */
@Service
@Slf4j
public class AnalysisRunMessageDispatcher {

    private final KafkaUtils kafkaUtils;
    private final AnalysisRunService analysisRunService;

    public AnalysisRunMessageDispatcher(KafkaUtils kafkaUtils, AnalysisRunService analysisRunService) {
        this.kafkaUtils = kafkaUtils;
        this.analysisRunService = analysisRunService;
    }

    public void dispatchAfterCommit(AnalysisScope scope, BehaviorProcessingTaskMessage message) {
        if (scope == null || message == null || !scope.equals(new AnalysisScope(
                message.getProjectId(), message.getAssessmentId(), message.getAnalysisRunId()))) {
            throw new IllegalArgumentException("消息中的运行作用域与待投递运行不一致");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("分析任务消息必须在事务内登记");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    kafkaUtils.sendMessageAndWait(message);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    markFailed(scope, exception);
                } catch (Exception exception) {
                    markFailed(scope, exception);
                }
            }
        });
    }

    private void markFailed(AnalysisScope scope, Exception exception) {
        log.error("分析任务消息投递失败: analysisRunId={}", scope.getAnalysisRunId(), exception);
        try {
            analysisRunService.markDispatchFailed(scope, LocalDateTime.now());
        } catch (Exception statusException) {
            log.error("消息投递失败后更新运行状态失败: analysisRunId={}",
                    scope.getAnalysisRunId(), statusException);
        }
    }
}
