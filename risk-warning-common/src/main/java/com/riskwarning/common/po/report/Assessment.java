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

    /** 创建本评估的上传确认稳定任务标识；唯一索引阻止同一上传任务重复建档。历史记录允许为空。 */
    @Column(name = "source_task_id", length = 160)
    private String sourceTaskId;

    private LocalDateTime assessmentDate;

    private Double overallScore;

    /** STRING 持久化（P0-11 决议 2.4）：列已由 INT 改 VARCHAR，枚举序号不再入库 */
    @Enumerated(EnumType.STRING)
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
