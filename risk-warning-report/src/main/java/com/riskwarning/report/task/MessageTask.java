package com.riskwarning.report.task;


import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.report.service.AnalysisRunCompletionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@Slf4j
public class MessageTask {

    @Autowired
    private AnalysisRunCompletionService analysisRunCompletionService;

    @KafkaListener(topics = "assessment_completed_events", groupId = "test-consumer")
    public void onMessage(AssessmentCompletedEventMessage message) {
        AnalysisScope scope = new AnalysisScope(
                message.getProjectId(), message.getAssessmentId(), message.getAnalysisRunId());
        log.info("接收评估结束任务，开始汇总信息");
        try {
            analysisRunCompletionService.aggregateAndComplete(message.getUserId(), scope);
        } catch (RuntimeException exception) {
            log.error("报告汇总失败，运行已终止: analysisRunId={}", scope.getAnalysisRunId(), exception);
        }
    }
}
