package com.riskwarning.common.po.analysis;

import com.riskwarning.common.enums.analysis.*;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/** P0 冻结的合规分析结果，PostgreSQL 为权威存储。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_analysis_result")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class AnalysisResult {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "schema_version", nullable = false, length = 8)
    private String schemaVersion;
    @Column(name = "analysis_run_id", nullable = false, length = 64)
    private String analysisRunId;
    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;
    @Column(name = "behavior_id", nullable = false, length = 128)
    private String behaviorId;
    @Type(type = "jsonb")
    @Column(name = "evidence_ids", nullable = false, columnDefinition = "jsonb")
    private List<String> evidenceIds;
    @Column(name = "indicator_id", nullable = false, length = 128)
    private String indicatorId;
    @Type(type = "jsonb")
    @Column(name = "regulation_ids", columnDefinition = "jsonb")
    private List<String> regulationIds;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Applicability applicable;
    @Column(columnDefinition = "TEXT")
    private String requirement;
    @Column(name = "enterprise_fact", nullable = false, columnDefinition = "TEXT")
    private String enterpriseFact;
    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_status", nullable = false, length = 32)
    private ComplianceStatus complianceStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "gap_type", length = 32)
    private GapType gapType;
    @Column(name = "gap_value")
    private Double gapValue;
    @Column(name = "gap_unit")
    private String gapUnit;
    @Column(nullable = false)
    private Double confidence;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String reasoning;
    @Column(name = "model_version", nullable = false)
    private String modelVersion;
    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;
    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;
}
