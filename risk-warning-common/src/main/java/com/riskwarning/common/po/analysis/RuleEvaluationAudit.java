package com.riskwarning.common.po.analysis;

import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import javax.persistence.*;
import java.time.LocalDateTime;

/** 每个运行与指标的权威评估审计；未决项也必须保留原因。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_rule_evaluation_audit", uniqueConstraints = @UniqueConstraint(
        name = "uq_rule_evaluation_audit", columnNames = {"analysis_run_id", "indicator_id"}))
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class RuleEvaluationAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;
    @Column(name = "analysis_run_id", nullable = false, length = 64)
    private String analysisRunId;
    @Column(name = "indicator_id", nullable = false, length = 128)
    private String indicatorId;
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;
    @Column(name = "rule_version")
    private String ruleVersion;
    @Column(name = "threshold_version")
    private String thresholdVersion;
    @Column(name = "aggregation_operator")
    private String aggregationOperator;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RuleEvaluationStatus status;
    @Type(type = "jsonb")
    @Column(name = "calculation_details", columnDefinition = "jsonb")
    private IndicatorResultDetail calculationDetails;
    @Column(name = "conflict", nullable = false)
    private Boolean conflict;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
