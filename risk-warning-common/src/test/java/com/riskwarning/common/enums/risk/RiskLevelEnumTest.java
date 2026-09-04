package com.riskwarning.common.enums.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 风险等级聚合的边界行为验证。
 *
 * 覆盖 PLAN.md:180「对零风险集合运行聚合单元测试，确认不会产生错误风险等级」，
 * 对应缺陷 D-09。
 */
class RiskLevelEnumTest {

    /**
     * 三个计数器全为 0 表示没有任何指标触发风险，属最好情况。
     * 修复前分母为 0 得到 NaN，经 getByScoreRatio 落入 else 分支被判为高风险。
     */
    @Test
    void shouldReturnLowRiskWhenNoIndicatorTriggersRisk() {
        assertEquals(RiskLevelEnum.LOW_RISK, RiskLevelEnum.getByRiskCount(0, 0, 0));
    }

    /**
     * NaN 代表无法计算，与「比例极低」语义相反，不得共用 else 分支静默降级为高风险。
     */
    @Test
    void shouldRejectNaNScoreRatio() {
        assertThrows(IllegalArgumentException.class,
                () -> RiskLevelEnum.getByScoreRatio(Double.NaN));
    }

    /**
     * 修复只针对边界，既有两档阈值（0.4 / 0.2）的判定结果必须不变。
     */
    @Test
    void shouldKeepExistingScoreRatioThresholds() {
        assertEquals(RiskLevelEnum.LOW_RISK, RiskLevelEnum.getByScoreRatio(1.0));
        assertEquals(RiskLevelEnum.LOW_RISK, RiskLevelEnum.getByScoreRatio(0.4));
        assertEquals(RiskLevelEnum.MEDIUM_RISK, RiskLevelEnum.getByScoreRatio(0.39));
        assertEquals(RiskLevelEnum.MEDIUM_RISK, RiskLevelEnum.getByScoreRatio(0.2));
        assertEquals(RiskLevelEnum.HIGH_RISK, RiskLevelEnum.getByScoreRatio(0.19));
        assertEquals(RiskLevelEnum.HIGH_RISK, RiskLevelEnum.getByScoreRatio(0.0));
    }

    /**
     * 高风险与中风险的数量短路规则先于加权计算生效，修复不得改变其结果。
     */
    @Test
    void shouldKeepCountShortCircuitRules() {
        assertEquals(RiskLevelEnum.HIGH_RISK, RiskLevelEnum.getByRiskCount(0, 0, 5));
        assertEquals(RiskLevelEnum.MEDIUM_RISK, RiskLevelEnum.getByRiskCount(0, 5, 0));
    }

    /**
     * 非零路径的加权结果，用于锁定修复未改动既有计算。
     * 全低风险：averageWeight = 1 - 0.2 = 0.8 → LOW_RISK。
     * 全高风险：averageWeight = 1 - 1.0 = 0.0 → HIGH_RISK。
     */
    @Test
    void shouldKeepWeightedAggregationForNonZeroCounts() {
        assertEquals(RiskLevelEnum.LOW_RISK, RiskLevelEnum.getByRiskCount(1, 0, 0));
        assertEquals(RiskLevelEnum.LOW_RISK, RiskLevelEnum.getByRiskCount(4, 0, 0));
        assertEquals(RiskLevelEnum.HIGH_RISK, RiskLevelEnum.getByRiskCount(0, 0, 1));
        assertEquals(RiskLevelEnum.HIGH_RISK, RiskLevelEnum.getByRiskCount(0, 0, 4));
    }
}
