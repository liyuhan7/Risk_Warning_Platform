package com.riskwarning.report.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.alibaba.fastjson2.JSON;
import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.report.repository.AnalysisRunRepository;
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
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private AnalysisRunRepository analysisRunRepository;

    @Value("${report.risk-index:t_risk}")
    private String riskIndex = ElasticSearchConfig.RISK_INDEX;


    @Override
    public void aggregateInformation(Long userId, Long projectId, Long assessmentId, String analysisRunId) {
        if (analysisRunId == null || analysisRunId.trim().isEmpty()) {
            throw new IllegalArgumentException("analysisRunId must not be blank");
        }
        Assessment assessment = assessmentRepository.findById(assessmentId).orElse(null);
        if (assessment == null) {
            throw new RuntimeException("Assessment not found");
        }
        AnalysisRun run = analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(analysisRunId, assessmentId, projectId)
                .orElseThrow(() -> new IllegalStateException("报告分析运行不存在"));
        if (run.getExecutionMode() != AnalysisExecutionMode.REASONING)
            throw new IllegalStateException("报告只接受合规分析主链的冻结结果");
        List<IndicatorResult> indicatorResults = indicatorResultRepository
                .findByAssessmentIdAndAnalysisRunId(assessmentId, analysisRunId);
        ReasoningReportProjection.validate(run, indicatorResults);
        List<Risk> risks = new ArrayList<>();
        double totalScore = 0.0;
        int lowRiskCount = 0, mediumRiskCount = 0, highRiskCount = 0, totalRiskCount = 0;
        for(IndicatorResult ir : indicatorResults){
            double calculatedScore = ir.getCalculatedScore().doubleValue();
            totalScore += calculatedScore;
            boolean triggered = Boolean.TRUE.equals(ir.getRiskTriggered());
            if(triggered) {
                totalRiskCount++;
                RiskLevelEnum riskLevelEnum = ir.getRiskLevel();
                lowRiskCount += riskLevelEnum == RiskLevelEnum.LOW_RISK ? 1 : 0;
                mediumRiskCount += riskLevelEnum == RiskLevelEnum.MEDIUM_RISK ? 1 : 0;
                highRiskCount += riskLevelEnum == RiskLevelEnum.HIGH_RISK ? 1 : 0;
                Risk risk = buildRisk(projectId, assessmentId, analysisRunId, ir, riskLevelEnum);
                {
                    risk.setRelatedIndicators(FrozenReportEvidence.project(ir));
                    risk.setDescription(FrozenReportEvidence.description(ir));
                    risk.setCreatedAt(ir.getCalculatedAt());
                }
                risks.add(risk);
            }
        }


        try {
            if (!risks.isEmpty()) {
                BulkResponse response = elasticsearchClient.bulk(b -> {
                    for (Risk risk : risks) {
                        b.operations(op -> op
                                .index(idx -> idx
                                        .index(riskIndex)
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
        assessment.setOverallRiskLevel(RiskLevelEnum.getByRiskCount(lowRiskCount, mediumRiskCount, highRiskCount));

        assessment.setStatus(AssessmentStatusEnum.ASSESSED);
        assessment.setAssessmentDate(LocalDateTime.now());
        assessment.setDetails(JSON.toJSONString(new AssessmentGeneralDetails(
                reportService.assembleGeneral(assessment, risks),
                reportService.assembleIndicatorResult(assessment, analysisRunId)
        )));
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

    /** Retrieval 固定规则已在 processing 决策，Report 只读取结果，禁止按统一阈值重算。 */
    static String stableRiskId(String analysisRunId, Long assessmentId, String indicatorEsId, String name) {
        return EvidenceChunk.sha256(analysisRunId + "|" + assessmentId + "|" + indicatorEsId + "|" + name)
                .substring(0, 32);
    }
}
