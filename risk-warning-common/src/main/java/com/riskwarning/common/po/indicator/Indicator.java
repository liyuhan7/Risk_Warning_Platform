package com.riskwarning.common.po.indicator;

import lombok.*;

import java.util.List;

/**
 * t_indicator 索引的主实体类
 */

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Indicator {

    private String id; // es默认的id字段，不需要手动设置
    // Getter 和 Setter 方法
    @Getter
    private String name;
    private String description;
    private String type;
    private Integer indicatorLevel;
    private String parentIndicatorId;
    private String dimension;
    /** P0-11 决议 3.2：语义为合规领域，ES 字段已改名 complianceDomain */
    private List<String> complianceDomain;
    private String region;
    private List<String> tags;
    private Double maxScore;
    private CalculationRule calculationRule;
    private RiskRule riskRule;
    private List<Float> nameVector;


    @Override
    public String toString() {
        return "TIndicator{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", type='" + type + '\'' +
                ", indicatorLevel=" + indicatorLevel +
                ", parentIndicatorId='" + parentIndicatorId + '\'' +
                ", dimension='" + dimension + '\'' +
                ", complianceDomain=" + complianceDomain +
                ", region='" + region + '\'' +
                ", tags=" + tags +
                ", maxScore=" + maxScore +
                ", calculationRule=" + calculationRule +
                ", riskRule=" + riskRule +
                '}';
    }
}
