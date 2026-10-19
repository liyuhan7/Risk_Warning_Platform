package com.riskwarning.common.po.analysis;

import com.riskwarning.common.dto.scoring.ScoringDecision.Judgment;
import com.riskwarning.common.enums.analysis.GapType;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.Data;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 独立保存每个行为在每项要求下的判定和分数。 */
@Data
@Entity
@Table(name = "t_requirement_evaluation", uniqueConstraints = @UniqueConstraint(name = "uq_requirement_evaluation",
        columnNames = {"analysis_run_id", "indicator_id", "requirement_id", "behavior_id"}))
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class RequirementEvaluation {
    @Id @Column(length = 64)
    private String id;
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;
    @Column(name = "analysis_run_id", nullable = false, length = 64)
    private String analysisRunId;
    @Column(name = "indicator_id", nullable = false, length = 128)
    private String indicatorId;
    @Column(name = "requirement_id", nullable = false, length = 128)
    private String requirementId;
    @Column(name = "behavior_id", nullable = false, length = 128)
    private String behaviorId;
    @Column(name = "regulation_id", nullable = false, length = 128)
    private String regulationId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private Judgment judgment;
    @Column(columnDefinition = "numeric")
    private BigDecimal satisfaction;
    @Column(columnDefinition = "numeric")
    private BigDecimal earned;
    @Column(name = "maximum_score", columnDefinition = "numeric")
    private BigDecimal maximum;
    @Column(name = "observed_value", columnDefinition = "numeric")
    private BigDecimal observedValue;
    @Column(name = "observed_unit")
    private String observedUnit;
    @Column(name = "condition_met")
    private Boolean conditionMet;
    @Enumerated(EnumType.STRING) @Column(name = "gap_type")
    private GapType gapType;
    @Column(name = "gap_value", columnDefinition = "numeric")
    private BigDecimal gapValue;
    @Column(name = "gap_unit")
    private String gapUnit;
    @Column(name = "violation_degree")
    private String violationDegree;
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;
    @Column(name = "source_bundle_hash", nullable = false, length = 64)
    private String sourceBundleHash;
    @Column(name = "registry_version", nullable = false)
    private String registryVersion;
    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;
    @Type(type = "jsonb") @Column(name = "evidence_ids", nullable = false, columnDefinition = "jsonb")
    private List<String> evidenceIds;
    @Column(nullable = false)
    private boolean conflict;
    @Column(columnDefinition = "text")
    private String reason;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
