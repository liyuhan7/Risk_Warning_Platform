package com.riskwarning.common.po.indicator;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;
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
    private List<String> industry;
    private String region;
    private List<String> tags;
    private Double maxScore;
    private CalculationRule calculationRule;
    private RiskRule riskRule;
    private LocalDateTime createAt;
    @JsonProperty("name_vector")
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
                ", industry=" + industry +
                ", region='" + region + '\'' +
                ", tags=" + tags +
                ", maxScore=" + maxScore +
                ", calculationRule=" + calculationRule +
                ", riskRule=" + riskRule +
                ", createAt=" + createAt +
                '}';
    }
}
