package com.riskwarning.report.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.alibaba.fastjson2.JSON;
import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.enums.risk.RiskStatusEnum;
import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.po.risk.Risk;
import com.riskwarning.common.utils.KafkaUtils;
import com.riskwarning.report.entity.vo.AssessmentGeneralDetails;
import com.riskwarning.report.repository.AssessmentRepository;
import com.riskwarning.report.repository.IndicatorResultRepository;
import com.riskwarning.report.service.AssessmentService;
import com.riskwarning.report.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class AssessmentServiceImpl implements AssessmentService {



    @Autowired
    private ReportService reportService;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private IndicatorResultRepository indicatorResultRepository;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private KafkaUtils kafkaUtils;

    private final static Double THRESHOLD_RATIO = 0.5;

    @Override
    public void aggregateInformation(Long userId, Long projectId, Long assessmentId) {
        Assessment assessment = assessmentRepository.findById(assessmentId).orElse(null);
        if (assessment == null) {
            throw new RuntimeException("Assessment not found");
        }
        // todo：查询IndicatorResult表，获取所有指标结果，计算结果小于maxScore * 0.5的产生风险，预留处置接口
        List<IndicatorResult> indicatorResults = indicatorResultRepository.findByAssessmentId(assessmentId);
        List<Risk> risks = new ArrayList<>();
        double totalScore = 0.0;
        int lowRiskCount = 0, mediumRiskCount = 0, highRiskCount = 0, totalRiskCount = 0;
        for(IndicatorResult ir : indicatorResults){
            double calculatedScore = ir.getCalculatedScore().doubleValue();
            double maxScore = ir.getMaxPossibleScore().doubleValue() == 0.0 ? calculatedScore : ir.getMaxPossibleScore().doubleValue();
            totalScore += calculatedScore;
            double scoreRatio = calculatedScore / maxScore;
            if(scoreRatio < THRESHOLD_RATIO) {
                totalRiskCount++;
                RiskLevelEnum riskLevelEnum = RiskLevelEnum.getByScoreRatio(scoreRatio);
                lowRiskCount += riskLevelEnum == RiskLevelEnum.LOW_RISK ? 1 : 0;
                mediumRiskCount += riskLevelEnum == RiskLevelEnum.MEDIUM_RISK ? 1 : 0;
                highRiskCount += riskLevelEnum == RiskLevelEnum.HIGH_RISK ? 1 : 0;
                ir.setRiskTriggered(true);
                ir.setRiskStatus(IndicatorRiskStatus.EVALUATED);
                String indicatorName = ir.getIndicatorName();
                Risk risk = Risk.builder()
                        .projectId(projectId)
                        .assessmentId(assessmentId)
                        .name("指标风险-" + (indicatorName != null && !indicatorName.isEmpty() ? indicatorName : "未命名指标"))
                        .dimension(ir.getDimension())
                        .description("")
                        .riskLevel(riskLevelEnum)
                        .probability(0.0)
                        .impact(0.0)
                        .detectability(0.0)
                        .status(RiskStatusEnum.TO_BE_DISPOSED)
                        .processingStatus("成功")
                        .responsibleParty("")
                        .affectedObjects(new String[]{})
                        .impactScope("")
                        .countermeasures("")
                        .relatedIndicators(ir.getCalculationDetails().getRelatedIndicators())
                        .createAt(LocalDateTime.now())
                        .build();
                risks.add(risk);
            }
        }

        // 持久化指标结果的“触发风险”状态，供指标概览/分布统计使用
        indicatorResultRepository.saveAll(indicatorResults);

        try {
            BulkResponse response = elasticsearchClient.bulk(b -> {
                for (Risk risk : risks) {
                    b.operations(op -> op
                            .index(idx -> idx
                                    .index(ElasticSearchConfig.RISK_INDEX)
                                    .document(risk)
                            )
                    );
                }
                return b;
            });
            if (response.errors()) {
                log.error("Bulk insert encountered errors: {}", response.items().toString());
                throw new Exception("Bulk insert to Elasticsearch failed");
            }
        } catch (Exception e) {
            log.error("Failed to index risk documents to Elasticsearch: {}", e.getMessage());
            throw new RuntimeException(e);
        }

        assessment.setOverallScore(totalScore);
//            // TODO: 总风险等级依靠owRiskCount, mediumRiskCoun, highRiskCount来设置
        assessment.setOverallRiskLevel(RiskLevelEnum.getByRiskCount(lowRiskCount, mediumRiskCount, highRiskCount));
        assessment.setStatus(AssessmentStatusEnum.ASSESSED);
        assessment.setDetails(JSON.toJSONString(new AssessmentGeneralDetails(
                reportService.assembleGeneral(assessment, risks),
                reportService.assembleIndicatorResult(assessment)
        )));
        assessment.setAssessmentDate(LocalDateTime.now());
        assessmentRepository.save(assessment);

        // 发送通知消息，通知前端评估完成
        sendAssessmentCompletedNotification(userId, projectId, assessmentId, assessment);
    }

    /**
     * 发送评估完成通知
     */
    private void sendAssessmentCompletedNotification(Long userId, Long projectId, Long assessmentId, Assessment assessment) {
        try {
            NotificationMessage notificationMessage = new NotificationMessage();
            notificationMessage.setMessageId(UUID.randomUUID().toString());
            notificationMessage.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            notificationMessage.setTraceId(UUID.randomUUID().toString());
            notificationMessage.setUserId(userId);
            notificationMessage.setProjectId(projectId);
            notificationMessage.setAssessmentId(assessmentId);
            notificationMessage.setNotificationType(NotificationMessage.NotificationType.ASSESSMENT_COMPLETED);
            notificationMessage.setTitle("评估完成通知");
            notificationMessage.setContent(String.format("项目评估已完成，总体风险等级：%s，评估得分：%.2f",
                    assessment.getOverallRiskLevel().getDescription(),
                    assessment.getOverallScore()));
            notificationMessage.setExtraData(JSON.toJSONString(new AssessmentNotificationData(
                    assessmentId,
                    projectId,
                    assessment.getOverallRiskLevel().name(),
                    assessment.getOverallScore()
            )));

            kafkaUtils.sendMessage(notificationMessage);
            log.info("评估完成通知已发送，assessmentId: {}, userId: {}", assessmentId, userId);
        } catch (Exception e) {
            log.error("发送评估完成通知失败: {}", e.getMessage());
        }
    }

    /**
     * 评估通知数据
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class AssessmentNotificationData {
        private Long assessmentId;
        private Long projectId;
        private String riskLevel;
        private Double overallScore;
    }
}
