package com.riskwarning.report.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.alibaba.fastjson2.JSON;
import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.common.enums.RiskDimensionEnum;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.po.risk.Risk;
import com.riskwarning.report.entity.vo.AssessmentGeneralDetails;
import com.riskwarning.report.entity.vo.general.*;
import com.riskwarning.report.entity.vo.indicator.IndicatorDistributionVO;
import com.riskwarning.report.entity.vo.indicator.ScoreRatioDistributionItemVO;
import com.riskwarning.report.entity.vo.risk.RiskVO;
import com.riskwarning.report.repository.AssessmentRepository;
import com.riskwarning.report.repository.IndicatorResultRepository;
import com.riskwarning.report.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private IndicatorResultRepository indicatorResultRepository;

    @Autowired
    private ElasticsearchClient esClient;

    @Override
    public IndicatorDistributionVO assembleIndicatorResult(Assessment assessment) {

        // 检查assessment的detail属性是否已经存在indicatorDistribution信息，若存在则直接反序列化返回，避免重复计算
        if(assessment.getDetails() != null && !assessment.getDetails().isEmpty()) {
            try{
                AssessmentGeneralDetails assessmentGeneralDetails = JSON.parseObject(assessment.getDetails(), AssessmentGeneralDetails.class);
                return assessmentGeneralDetails.getIndicatorDistributionVO();
            } catch (Exception e) {
                log.error("评估报告已存在详细信息但反序列化失败", e);
                throw new BusinessException("评估报告已存在详细信息但反序列化失败");
            }

        }
        // TODO: 目前写在内存中进行聚合，之后可能考虑使用数据库进行聚合计算
        List<IndicatorResult> indicatorResults = indicatorResultRepository.findByAssessmentId(assessment.getId());
        IndicatorDistributionVO indicatorDistributionVO = new IndicatorDistributionVO();
        indicatorDistributionVO.setAssessmentId(assessment.getId());
        indicatorDistributionVO.setRiskDimensionEnum(null);
        indicatorDistributionVO.setTotalScore(assessment.getOverallScore());
        indicatorDistributionVO.setTotalCount(indicatorResults.size());
        indicatorDistributionVO.setSafeCount(0);
        indicatorDistributionVO.setRiskTriggeredCount(0);
        indicatorDistributionVO.setAssessmentTime(assessment.getAssessmentDate());
        indicatorDistributionVO.setScoreDistributions(new ArrayList<>());
        indicatorDistributionVO.setDimensionDistributions(new HashMap<>());

        for(int i = 0;i < 4;i++){
            indicatorDistributionVO.getScoreDistributions().add(
                    ScoreRatioDistributionItemVO.builder()
                            .startScoreRatio(i * 0.25)
                            .endScoreRatio((i + 1) * 0.25)
                            .ratio(0.0)
                            .totalScore(0.0)
                            .totalCount(0)
                            .riskTriggeredCount(0)
                            .safeCount(0)
                            .build()
            );
        }

        for(IndicatorResult indicatorResult : indicatorResults) {
            // 计算分数分布
            if(indicatorResult.getRiskTriggered()){
                indicatorDistributionVO.setRiskTriggeredCount(indicatorDistributionVO.getRiskTriggeredCount() + 1);
            } else {
                indicatorDistributionVO.setSafeCount(indicatorDistributionVO.getSafeCount() + 1);
            }
            // 计算总体分数分布
            int targetIndex = (int)Math.min(3, Math.floor((indicatorResult.getCalculatedScore() /
                    (indicatorResult.getMaxPossibleScore() == 0.0 ? indicatorResult.getCalculatedScore() : indicatorResult.getMaxPossibleScore())) / 0.25));
            ScoreRatioDistributionItemVO scoreRatioDistributionItemVO = indicatorDistributionVO.getScoreDistributions().get(targetIndex);
            scoreRatioDistributionItemVO.setTotalCount(scoreRatioDistributionItemVO.getTotalCount() + 1);
            scoreRatioDistributionItemVO.setTotalScore(scoreRatioDistributionItemVO.getTotalScore() + indicatorResult.getCalculatedScore());
            if(indicatorResult.getRiskTriggered()){
                scoreRatioDistributionItemVO.setRiskTriggeredCount(scoreRatioDistributionItemVO.getRiskTriggeredCount() + 1);
            } else {
                scoreRatioDistributionItemVO.setSafeCount(scoreRatioDistributionItemVO.getSafeCount() + 1);
            }
            //计算各维度
            IndicatorDistributionVO dimensionVO = indicatorDistributionVO.getDimensionDistributions()
                    .getOrDefault(RiskDimensionEnum.fromValue(indicatorResult.getDimension()),
                            IndicatorDistributionVO.builder()
                                    .riskDimensionEnum(RiskDimensionEnum.fromValue(indicatorResult.getDimension()))
                                    .assessmentId(assessment.getId())
                                    .assessmentTime(assessment.getAssessmentDate())
                                    .totalScore(0.0)
                                    .totalCount(0)
                                    .riskTriggeredCount(0)
                                    .safeCount(0)
                                    .assessmentTime(assessment.getAssessmentDate())
                                    .scoreDistributions(new ArrayList<>())
                                    .dimensionDistributions(new HashMap<>())
                                    .build());
            for(int i = 0;i < 4;i++){
                if(dimensionVO.getScoreDistributions().size() < 4){
                    dimensionVO.getScoreDistributions().add(
                            ScoreRatioDistributionItemVO.builder()
                                    .startScoreRatio(i * 0.25)
                                    .endScoreRatio((i + 1) * 0.25)
                                    .ratio(0.0)
                                    .totalScore(0.0)
                                    .totalCount(0)
                                    .riskTriggeredCount(0)
                                    .safeCount(0)
                                    .build()
                    );
                }
            }
            dimensionVO.setTotalCount(dimensionVO.getTotalCount() + 1);
            dimensionVO.setTotalScore(dimensionVO.getTotalScore() + indicatorResult.getCalculatedScore());
            if(indicatorResult.getRiskTriggered()){
                dimensionVO.setRiskTriggeredCount(dimensionVO.getRiskTriggeredCount() + 1);
            } else {
                dimensionVO.setSafeCount(dimensionVO.getSafeCount() + 1);
            }
            indicatorDistributionVO.getDimensionDistributions().put(RiskDimensionEnum.fromValue(indicatorResult.getDimension()), dimensionVO);
        }

        return indicatorDistributionVO;
    }

    @Override
    public List<RiskVO> assembleRisk(Long assessmentId) {
        List<Risk> risks = fetchRisksFromES(assessmentId);
        List<RiskVO> riskVOList = new ArrayList<>();
        for(Risk risk : risks) {
            RiskVO riskVO = new RiskVO();
            BeanUtils.copyProperties(risk, riskVO);
            // BeanUtils.copyProperties 不会自动将枚举类型转为 String，需要手动设置
            if (risk.getRiskLevel() != null) {
                riskVO.setRiskLevel(risk.getRiskLevel().name());
            }
            if (risk.getStatus() != null) {
                riskVO.setStatus(risk.getStatus().name());
            }
            if (riskVO.getProcessingStatus() == null || riskVO.getProcessingStatus().isEmpty()) {
                riskVO.setProcessingStatus("成功");
            }
            riskVOList.add(riskVO);
        }
        return riskVOList;
    }

    @Override
    public AssessmentDetailVO assembleGeneral(Assessment assessment) {
        // 检查assessment的detail属性是否已经存在general信息，若存在则直接反序列化返回，避免重复计算
        if(assessment.getDetails() != null && !assessment.getDetails().isEmpty()) {
            try{
                AssessmentGeneralDetails assessmentGeneralDetails = JSON.parseObject(assessment.getDetails(), AssessmentGeneralDetails.class);
                return assessmentGeneralDetails.getAssessmentDetailVO();
            } catch (Exception e) {
                log.error("评估报告已存在详细信息但反序列化失败", e);
                throw new BusinessException("评估报告已存在详细信息但反序列化失败");
            }
        }
        List<Risk> risks = fetchRisksFromES(assessment.getId());
        return assembleGeneralWithRisks(assessment, risks);
    }

    @Override
    public AssessmentDetailVO assembleGeneral(Assessment assessment, List<Risk> risks) {
        return assembleGeneralWithRisks(assessment, risks != null ? risks : new ArrayList<>());
    }

    /**
     * 根据 assessment 与风险列表构建汇总 VO（不查 ES），并正确设置 totalRisks
     */
    private AssessmentDetailVO assembleGeneralWithRisks(Assessment assessment, List<Risk> risks) {
        AssessmentDetailVO assessmentDetailVO = new AssessmentDetailVO();
        assessmentDetailVO.setProjectId(assessment.getProjectId());
        assessmentDetailVO.setAssessmentId(assessment.getId());
        assessmentDetailVO.setAssessmentDate(assessment.getAssessmentDate());
        assessmentDetailVO.setOverallResult(OverallResult.builder()
                .overallScore(assessment.getOverallScore())
                .overallRiskLevel(assessment.getOverallRiskLevel())
                .status(assessment.getStatus())
                .build()
        );
        assessmentDetailVO.setRiskSummary(new RiskSummary());
        assessmentDetailVO.setIndicatorOverview(new IndicatorOverview());
        assessmentDetailVO.setDimensionRiskDistribution(new HashMap<>());
        for(RiskDimensionEnum riskDimensionEnum : RiskDimensionEnum.values()) {
            assessmentDetailVO.getDimensionRiskDistribution().put(riskDimensionEnum, new DimensionRiskDistribution());
        }
        assessmentDetailVO.getIndicatorOverview().setBehaviorIndicators(risks.size());
        for(Risk risk : risks) {
            RiskDimensionEnum dimensionEnum = RiskDimensionEnum.fromValue(risk.getDimension());
            RiskLevelEnum riskLevelEnum = risk.getRiskLevel();
            if (riskLevelEnum == null) {
                log.warn("[assembleGeneral] risk_level is null for risk id={}, skipping risk level aggregation", risk.getId());
                continue;
            }
            DimensionRiskDistribution distribution = assessmentDetailVO.getDimensionRiskDistribution().get(dimensionEnum);
            distribution.setRiskCount(nullSafe(distribution.getRiskCount()) + 1);
            switch (riskLevelEnum) {
                case LOW_RISK:
                    assessmentDetailVO.getRiskSummary().setLowRiskCount(nullSafe(assessmentDetailVO.getRiskSummary().getLowRiskCount()) + 1);
                    distribution.setLowRiskCount(nullSafe(distribution.getLowRiskCount()) + 1);
                    break;
                case MEDIUM_RISK:
                    assessmentDetailVO.getRiskSummary().setMediumRiskCount(nullSafe(assessmentDetailVO.getRiskSummary().getMediumRiskCount()) + 1);
                    distribution.setMediumRiskCount(nullSafe(distribution.getMediumRiskCount()) + 1);
                    break;
                case HIGH_RISK:
                    assessmentDetailVO.getRiskSummary().setHighRiskCount(nullSafe(assessmentDetailVO.getRiskSummary().getHighRiskCount()) + 1);
                    distribution.setHighRiskCount(nullSafe(distribution.getHighRiskCount()) + 1);
                    break;
                default:
                    log.error("riskLevelEnum error, unexpected value: {}", riskLevelEnum);
            }
        }
        // 风险总数 = 高+中+低（与前端展示一致）
        int total = nullSafe(assessmentDetailVO.getRiskSummary().getHighRiskCount())
                + nullSafe(assessmentDetailVO.getRiskSummary().getMediumRiskCount())
                + nullSafe(assessmentDetailVO.getRiskSummary().getLowRiskCount());
        assessmentDetailVO.getRiskSummary().setTotalRisks(total);
        return assessmentDetailVO;
    }

    private static int nullSafe(Integer n) {
        return n != null ? n : 0;
    }


    public List<Risk> fetchRisksFromES(Long assessmentId) {
        try {
            log.info("[Fetching Risks] assessmentId={}, dimension={}, riskLevel={}",
                    assessmentId);

            SearchResponse<Risk> resp = esClient.search(s -> s
                            .index(ElasticSearchConfig.RISK_INDEX)
                            .size(1000)  // 假设一个项目不会超过1000个behaviors，如需要可以改成scroll
                            .query(q -> q
                                    .bool(b -> b
                                            .must(m1 -> m1.term(t -> t.field("assessment_id").value(assessmentId)))
                                    )
                            )
                            .sort(sort -> sort
                                    .field(f -> f
                                            .field("create_at")
                                            .order(SortOrder.Desc)
                                    )
                            ),
                    Risk.class
            );

            List<Risk> risks = new ArrayList<>();
            if (resp != null && resp.hits() != null && resp.hits().hits() != null) {
                for (Hit<Risk> hit : resp.hits().hits()) {
                    Risk risk = hit.source();
                    if (risk != null) {
                        risks.add(risk);
                        log.debug("[Risk Fetched] assessmentId={}, riskId={}", assessmentId, risk.getId());
                    }
                }
            }

            log.info("[Fetching Risks Completed] assessmentId={}, totalRisks={}",
                    assessmentId, risks.size());
            return risks;
        } catch (Exception e) {
            log.error("[Fetching Risks Failed] assessmentId={}", assessmentId, e);
            return Collections.emptyList();
        }
    }

    // TODO: 根据某个指标结果查询相关风险，风险对应法规的详细信息接口

}
