package com.riskwarning.common.enums.risk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RiskLevelEnum {

    LOW_RISK("LOW_RISK", "低风险"),

    MEDIUM_RISK("MEDIUM_RISK", "中风险"),

    HIGH_RISK("HIGH_RISK", "高风险");

    private final String code;

    private final String description;

    private static final Double LOW_RISK_WEIGHT = 0.2;

    private static final Double MEDIUM_RISK_WEIGHT = 0.4;

    private static final Double HIGH_RISK_WEIGHT = 1.0;

    RiskLevelEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    @JsonCreator
    public static RiskLevelEnum fromValue(String riskLevel) {
        if (riskLevel == null) {
            return null;
        }
        for (RiskLevelEnum level : RiskLevelEnum.values()) {
            if (level.code.equalsIgnoreCase(riskLevel) || level.name().equalsIgnoreCase(riskLevel)) {
                return level;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return this.code;
    }

    /**
     * 按得分比例判定风险等级。比例越低风险越高。
     *
     * @param scoreRatio 得分比例，不得为 NaN
     * @return 对应的风险等级
     * @throws IllegalArgumentException 入参为 NaN 时抛出。NaN 表示无法计算，
     *         与「比例极低」语义相反，不能共用最低档分支被静默判为高风险
     */
    public static RiskLevelEnum getByScoreRatio(double scoreRatio) {
        if (Double.isNaN(scoreRatio)) {
            throw new IllegalArgumentException("scoreRatio 不能为 NaN，无法计算的比例不得参与风险等级判定");
        }
        if (scoreRatio >= 0.4) {
            return LOW_RISK;
        } else if (scoreRatio >= 0.2) {
            return MEDIUM_RISK;
        } else {
            return HIGH_RISK;
        }
    }

    /**
     * 按各等级的风险数量聚合总体风险等级。
     *
     * 三个入参统计的是**已触发风险**的指标数量，因此全为 0 表示没有任何指标触发风险，
     * 属最好情况，须返回最低档；不可让分母为 0 得到 NaN 后落入最高档。
     *
     * @param lowRiskCount 低风险指标数
     * @param mediumRiskCount 中风险指标数
     * @param highRiskCount 高风险指标数
     * @return 总体风险等级
     */
    public static RiskLevelEnum getByRiskCount(int lowRiskCount, int mediumRiskCount, int highRiskCount) {
        int totalRiskCount = lowRiskCount + mediumRiskCount + highRiskCount;
        if (totalRiskCount == 0) {
            return LOW_RISK;
        }
        if(highRiskCount >= 5){
            return HIGH_RISK;
        }
        if(mediumRiskCount >= 5){
            return MEDIUM_RISK;
        }
        double totalWeight = lowRiskCount * LOW_RISK_WEIGHT + mediumRiskCount * MEDIUM_RISK_WEIGHT + highRiskCount * HIGH_RISK_WEIGHT;
        double averageWeight = 1 - totalWeight / totalRiskCount;
        return RiskLevelEnum.getByScoreRatio(averageWeight);
    }
}
