package com.riskwarning.common.enums;

import lombok.Getter;

public enum RiskDimensionEnum {

    ENTERPRISE_RELATED_RISK("企业关联方风险", 0),

    PRODUCT_LEGITIMACY_RISK("产品合规风险", 1),

    LABOR_LEGITIMACY_RISK("劳务合规风险", 2),

    ENTERPRISE_CREDIT_RISK("企业信用风险", 3),

    ENTERPRISE_INTERNATIONAL_COOPERATION_RISK("企业国际合作风险", 4),

    SUPPLY_CHAIN_RISK("供应链风险", 5);

    @Getter
    private final String description;

    private final int code;

    RiskDimensionEnum(String description, int code) {
        this.description = description;
        this.code = code;
    }

    public static RiskDimensionEnum getByCode(int code) {
        for (RiskDimensionEnum dimension : RiskDimensionEnum.values()) {
            if (dimension.code == code) {
                return dimension;
            }
        }
        return null;
    }

    public static RiskDimensionEnum fromValue(String dimension) {
        for (RiskDimensionEnum dim : RiskDimensionEnum.values()) {
            if (dim.description.equals(dimension)) {
                return dim;
            }
        }
        throw new IllegalArgumentException(dimension);
    }
}
