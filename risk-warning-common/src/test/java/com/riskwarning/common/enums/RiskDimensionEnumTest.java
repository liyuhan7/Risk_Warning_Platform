package com.riskwarning.common.enums;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RiskDimensionEnum 取值与 ES 存量维度名一致性的回归测试。
 * ES 侧 t_indicator 的 dimension 取值是权威事实源（D-28），
 * 枚举 description 必须逐字一致，fromValue 不得依赖任何运行时特例分支
 */
class RiskDimensionEnumTest {

    @Test
    void 遍历ES实际存量维度值均可反查且不触发特例分支() {
        Set<String> esDimensionValues = new HashSet<>();
        esDimensionValues.add("企业关联方风险");
        esDimensionValues.add("产品合规风险");
        esDimensionValues.add("劳务合规风险");
        esDimensionValues.add("企业信用风险");
        esDimensionValues.add("企业国际合作风险");
        esDimensionValues.add("供应链风险");

        for (String value : esDimensionValues) {
            assertNotNull(RiskDimensionEnum.fromValue(value), "ES 维度值无法反查: " + value);
        }
    }

    @Test
    void 国际合作维度以ES取值为权威命名() {
        assertEquals("企业国际合作风险",
                RiskDimensionEnum.ENTERPRISE_INTERNATIONAL_COOPERATION_RISK.getDescription());
    }

    @Test
    void 未知维度名直接抛出而非兜底() {
        assertThrows(IllegalArgumentException.class,
                () -> RiskDimensionEnum.fromValue("国际化经营风险"));
    }

    @Test
    void code与枚举一一对应() {
        assertEquals(RiskDimensionEnum.ENTERPRISE_RELATED_RISK, RiskDimensionEnum.getByCode(0));
        assertEquals(RiskDimensionEnum.PRODUCT_LEGITIMACY_RISK, RiskDimensionEnum.getByCode(1));
        assertEquals(RiskDimensionEnum.LABOR_LEGITIMACY_RISK, RiskDimensionEnum.getByCode(2));
        assertEquals(RiskDimensionEnum.ENTERPRISE_CREDIT_RISK, RiskDimensionEnum.getByCode(3));
        assertEquals(RiskDimensionEnum.ENTERPRISE_INTERNATIONAL_COOPERATION_RISK, RiskDimensionEnum.getByCode(4));
        assertEquals(RiskDimensionEnum.SUPPLY_CHAIN_RISK, RiskDimensionEnum.getByCode(5));
        assertEquals(null, RiskDimensionEnum.getByCode(-1));
    }
}
