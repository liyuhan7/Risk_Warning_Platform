package com.riskwarning.processing.repository;

import com.riskwarning.common.po.indicator.IndicatorResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 指标结果持久化接口
 */
@Repository
public interface IndicatorResultRepository extends JpaRepository<IndicatorResult, Long> {


    /**
     * 根据评估、运行与指标查找结果，避免不同运行互相覆盖。
     */
    Optional<IndicatorResult> findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(
            Long assessmentId, String analysisRunId, String indicatorEsId);

    /**
     * 统计指定 assessmentId 的记录数
     */
    long countByAssessmentId(Long assessmentId);

    /**
     * CAS更新，比较calculated_at字段进行乐观锁更新
     * 只有当数据库中的 calculated_at 等于 oldCalculatedAt 时才会更新成功
     *
     * @param result 完整的指标结果对象（包含要更新的所有字段）
     * @param oldCalculatedAt 旧的计算时间（用于乐观锁比较）
     * @return 更新影响的行数，1表示更新成功，0表示更新失败（版本冲突）
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE t_indicator_result SET " +
            "project_id = :#{#result.projectId}, " +
            "assessment_id = :#{#result.assessmentId}, " +
            "analysis_run_id = :#{#result.analysisRunId}, " +
            "indicator_es_id = :#{#result.indicatorEsId}, " +
            "indicator_name = :#{#result.indicatorName}, " +
            "indicator_level = :#{#result.indicatorLevel}, " +
            "dimension = :#{#result.dimension}, " +
            "type = :#{#result.type}, " +
            "calculated_score = :#{#result.calculatedScore}, " +
            "max_possible_score = :#{#result.maxPossibleScore}, " +
            "used_calculation_rule_type = :#{#result.usedCalculationRuleType}, " +
            "calculation_details = CAST(:calculationDetailsJson AS jsonb), " +
            "risk_triggered = :#{#result.riskTriggered}, " +
            "risk_status = CAST(:riskStatus AS indicator_risk_status_enum), " +
            "calculated_at = :#{#result.calculatedAt} " +
            "WHERE id = :#{#result.id} AND calculated_at = :oldCalculatedAt",
            nativeQuery = true)
    int updateWithOptimisticLock(
            @Param("result") IndicatorResult result,
            @Param("calculationDetailsJson") String calculationDetailsJson,
            @Param("riskStatus") String riskStatus,
            @Param("oldCalculatedAt") LocalDateTime oldCalculatedAt
    );

}
