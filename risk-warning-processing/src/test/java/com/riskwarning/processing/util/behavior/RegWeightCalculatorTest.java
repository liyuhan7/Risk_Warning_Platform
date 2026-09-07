package com.riskwarning.processing.util.behavior;

import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.regulation.Regulation;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 法规时效权重计算边界测试：行为日期是事实抽取的可选字段，
 * 缺失时必须返回中性权重而不是抛出 NPE。
 */
class RegWeightCalculatorTest {

    private static final LocalDateTime REG_PUBLISHED_AT = LocalDateTime.of(2023, 6, 1, 0, 0);

    @Test
    void missingBehaviorDateReturnsNeutralWeight() {
        Regulation reg = Regulation.builder().createdAt(REG_PUBLISHED_AT).build();
        Behavior behavior = Behavior.builder().behaviorDate(null).build();

        assertEquals(0.5, RegWeightCalculator.getTimelinessWeight(reg, behavior));
    }

    @Test
    void missingRegulationDateReturnsNeutralWeight() {
        Regulation reg = Regulation.builder().createdAt(null).build();
        Behavior behavior = Behavior.builder()
                .behaviorDate(LocalDateTime.of(2024, 1, 1, 0, 0))
                .build();

        assertEquals(0.5, RegWeightCalculator.getTimelinessWeight(reg, behavior));
    }

    @Test
    void nullArgumentsReturnNeutralWeight() {
        Behavior behavior = Behavior.builder().behaviorDate(null).build();

        assertEquals(0.5, RegWeightCalculator.getTimelinessWeight(null, behavior));
        assertEquals(0.5, RegWeightCalculator.getTimelinessWeight(
                Regulation.builder().createdAt(REG_PUBLISHED_AT).build(), null));
    }

    @Test
    void recentBehaviorWithinTwoYearsGetsHigherWeight() {
        Regulation reg = Regulation.builder().createdAt(REG_PUBLISHED_AT).build();
        Behavior behavior = Behavior.builder()
                .behaviorDate(LocalDateTime.of(2024, 6, 1, 0, 0))
                .build();

        assertEquals(0.7, RegWeightCalculator.getTimelinessWeight(reg, behavior));
    }

    @Test
    void behaviorBeyondTwoYearsGetsLowerWeight() {
        Regulation reg = Regulation.builder().createdAt(REG_PUBLISHED_AT).build();
        Behavior behavior = Behavior.builder()
                .behaviorDate(LocalDateTime.of(2026, 9, 6, 0, 0))
                .build();

        assertEquals(0.3, RegWeightCalculator.getTimelinessWeight(reg, behavior));
    }
}
