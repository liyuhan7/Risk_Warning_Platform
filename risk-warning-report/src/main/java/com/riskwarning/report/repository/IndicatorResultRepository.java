package com.riskwarning.report.repository;

import com.riskwarning.common.po.indicator.IndicatorResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 指标结果持久化接口
 */
@Repository
public interface IndicatorResultRepository extends JpaRepository<IndicatorResult, Long> {


    /** 按评估和独立运行读取结果，避免跨轮汇总。 */
    List<IndicatorResult> findByAssessmentIdAndAnalysisRunId(Long assessmentId, String analysisRunId);

    /** 成功切换后删除本评估中非当前运行的指标结果。 */
    @Modifying
    @Transactional
    long deleteByAssessmentIdAndAnalysisRunIdNot(Long assessmentId, String analysisRunId);



}
