package com.riskwarning.common.po.indicator;


import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.utils.PostgreSQLEnumType;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 对应表: public.t_indicator_result
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_indicator_result")
@TypeDef(name = "pgsql_enum", typeClass = PostgreSQLEnumType.class)
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class IndicatorResult implements Serializable {
    private static final long serialVersionUID = 1L;

    // id BIGSERIAL PRIMARY KEY
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // project_id BIGINT NOT NULL
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    // assessment_id BIGINT NOT NULL
    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    /** 产生本结果的独立分析运行；历史记录允许为空。 */
    @Column(name = "analysis_run_id", length = 64)
    private String analysisRunId;

    // indicator_es_id TEXT NOT NULL
    @Column(name = "indicator_es_id", nullable = false)
    private String indicatorEsId;

    // indicator_name TEXT NOT NULL
    @Column(name = "indicator_name", nullable = false)
    private String indicatorName;

    // indicator_level INTEGER NOT NULL
    @Column(name = "indicator_level", nullable = false)
    private Integer indicatorLevel;

    // dimension TEXT
    @Column(name = "dimension")
    private String dimension;

    // "type" TEXT
    @Column(name = "type")
    private String type;

    // calculated_score NUMERIC NOT NULL
    @Column(name = "calculated_score", nullable = false)
    private Double calculatedScore;

    // max_possible_score NUMERIC NOT NULL DEFAULT 100
    @Column(name = "max_possible_score", nullable = false)
    private Double maxPossibleScore;

    // used_calculation_rule_type TEXT NOT NULL
    @Column(name = "used_calculation_rule_type", nullable = false)
    private String usedCalculationRuleType;

    // calculation_details JSONB（以结构化 JSON 存储）
    @Type(type = "jsonb")
    @Column(name = "calculation_details", columnDefinition = "jsonb")
    private IndicatorResultDetail calculationDetails;

    // risk_triggered BOOLEAN NOT NULL DEFAULT FALSE
    @Column(name = "risk_triggered", nullable = false)
    private Boolean riskTriggered;

    // risk_status indicator_risk_status_enum NOT NULL DEFAULT '未评估'
    @Type(type = "pgsql_enum")
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_status", nullable = false, columnDefinition = "indicator_risk_status_enum")
    private IndicatorRiskStatus riskStatus;

    // calculated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    // created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
