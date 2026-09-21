package com.riskwarning.report.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.alibaba.fastjson2.JSON;
import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.enums.risk.RiskStatusEnum;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.po.risk.Risk;
import com.riskwarning.report.entity.vo.AssessmentGeneralDetails;
import com.riskwarning.report.repository.AssessmentRepository;
import com.riskwarning.report.repository.IndicatorResultRepository;
import com.riskwarning.report.service.AssessmentService;
import com.riskwarning.report.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    private final static Double THRESHOLD_RATIO = 0.5;

    @Override
    public void aggregateInformation(Long userId, Long projectId, Long assessmentId, String analysisRunId) {
        if (analysisRunId == null || analysisRunId.trim().isEmpty()) {
            throw new IllegalArgumentException("analysisRunId must not be blank");
        }
        Assessment assessment = assessmentRepository.findById(assessmentId).orElse(null);
        if (assessment == null) {
            throw new RuntimeException("Assessment not found");
        }
        // todo：查询IndicatorResult表，获取所有指标结果，计算结果小于maxScore * 0.5的产生风险，预留处置接口
        List<IndicatorResult> indicatorResults = indicatorResultRepository
                .findByAssessmentIdAndAnalysisRunId(assessmentId, analysisRunId);
        List<Risk> risks = new ArrayList<>();
        double totalScore = 0.0;
        int lowRiskCount = 0, mediumRiskCount = 0, highRiskCount = 0, totalRiskCount = 0;
        for(IndicatorResult ir : indicatorResults){
            double calculatedScore = ir.getCalculatedScore().doubleValue();
            double maxScore = ir.getMaxPossibleScore().doubleValue() == 0.0 ? calculatedScore : ir.getMaxPossibleScore().doubleValue();
            totalScore += calculatedScore;
            boolean mockFixture = "p2_mock_fixture_v1".equals(ir.getUsedCalculationRuleType());
            double scoreRatio = calculatedScore / maxScore;
            boolean triggered = mockFixture ? Boolean.TRUE.equals(ir.getRiskTriggered()) : scoreRatio < THRESHOLD_RATIO;
            if(triggered) {
                totalRiskCount++;
                RiskLevelEnum riskLevelEnum = mockFixture ? fixtureRiskLevel(ir) : RiskLevelEnum.getByScoreRatio(scoreRatio);
                lowRiskCount += riskLevelEnum == RiskLevelEnum.LOW_RISK ? 1 : 0;
                mediumRiskCount += riskLevelEnum == RiskLevelEnum.MEDIUM_RISK ? 1 : 0;
                highRiskCount += riskLevelEnum == RiskLevelEnum.HIGH_RISK ? 1 : 0;
                ir.setRiskTriggered(true);
                ir.setRiskStatus(IndicatorRiskStatus.EVALUATED);
                Risk risk = buildRisk(projectId, assessmentId, analysisRunId, ir, riskLevelEnum);
                risks.add(risk);
            } else if (mockFixture) {
                ir.setRiskTriggered(false);
                ir.setRiskStatus(IndicatorRiskStatus.EVALUATED);
            }
        }

        // 持久化指标结果的“触发风险”状态，供指标概览/分布统计使用
        indicatorResultRepository.saveAll(indicatorResults);

        try {
            if (!risks.isEmpty()) {
                BulkResponse response = elasticsearchClient.bulk(b -> {
                    for (Risk risk : risks) {
                        b.operations(op -> op
                                .index(idx -> idx
                                        .index(ElasticSearchConfig.RISK_INDEX)
                                        .id(risk.getId())
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
            } else {
                log.info("No risks triggered for assessmentId={}, skipping ES bulk index", assessmentId);
            }
        } catch (Exception e) {
            log.error("Failed to index risk documents to Elasticsearch: {}", e.getMessage());
            throw new RuntimeException(e);
        }

        double finalAvgScore = indicatorResults.isEmpty() ? 0.0 : totalScore / indicatorResults.size();
        assessment.setOverallScore(finalAvgScore);
//            // TODO: 总风险等级依靠owRiskCount, mediumRiskCoun, highRiskCount来设置
        assessment.setOverallRiskLevel(RiskLevelEnum.getByRiskCount(lowRiskCount, mediumRiskCount, highRiskCount));

        assessment.setStatus(AssessmentStatusEnum.ASSESSED);
        assessment.setDetails(JSON.toJSONString(new AssessmentGeneralDetails(
                reportService.assembleGeneral(assessment, risks),
                reportService.assembleIndicatorResult(assessment, analysisRunId)
        )));
        assessment.setAssessmentDate(LocalDateTime.now());
        assessmentRepository.save(assessment);
    }

    static Risk buildRisk(Long projectId, Long assessmentId, String analysisRunId,
                          IndicatorResult indicatorResult, RiskLevelEnum riskLevel) {
        String indicatorName = indicatorResult.getIndicatorName();
        String name = "指标风险-" + (indicatorName != null && !indicatorName.isEmpty()
                ? indicatorName : "未命名指标");
        return Risk.builder()
                .id(stableRiskId(analysisRunId, assessmentId, indicatorResult.getIndicatorEsId(), name))
                .projectId(projectId)
                .assessmentId(assessmentId)
                .analysisRunId(analysisRunId)
                .name(name)
                .dimension(indicatorResult.getDimension())
                .description("")
                .riskLevel(riskLevel)
                .probability(0.0)
                .impact(0.0)
                .detectability(0.0)
                .status(RiskStatusEnum.TO_BE_DISPOSED)
                .processingStatus("成功")
                .responsibleParty("")
                .affectedObjects(new String[]{})
                .impactScope("")
                .countermeasures("")
                .relatedIndicators(indicatorResult.getCalculationDetails().getRelatedIndicators())
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** P2 固定规则已在 processing 决策，Report 只读取结果，禁止按统一阈值重算。 */
    static RiskLevelEnum fixtureRiskLevel(IndicatorResult indicatorResult) {
        if (indicatorResult.getCalculationDetails() == null
                || indicatorResult.getCalculationDetails().getTraces() == null) {
            throw new IllegalStateException("P2 指标结果缺少规则追踪");
        }
        return indicatorResult.getCalculationDetails().getTraces().stream()
                .filter(trace -> trace.getRuleEvaluation() != null
                        && Boolean.TRUE.equals(trace.getRuleEvaluation().getRiskTriggered()))
                .map(trace -> trace.getRuleEvaluation().getRiskLevel()).filter(Objects::nonNull)
                .findFirst().orElseThrow(() -> new IllegalStateException("P2 风险缺少固定 riskLevel"));
    }

    static String stableRiskId(String analysisRunId, Long assessmentId, String indicatorEsId, String name) {
        return EvidenceChunk.sha256(analysisRunId + "|" + assessmentId + "|" + indicatorEsId + "|" + name)
                .substring(0, 32);
    }
}
