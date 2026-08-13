package com.riskwarning.common.po.report;


import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "t_assessment_result")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Assessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private LocalDateTime assessmentDate;

    private Double overallScore;

    private RiskLevelEnum overallRiskLevel;

    @Type(type = "jsonb")
    @Column(name = "details", columnDefinition = "jsonb")
    private String details;

    private String recommendations;

    @Type(type = "pgsql_enum")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "assessment_status_enum")
    private AssessmentStatusEnum status;

    private LocalDateTime createdAt;
}
