package com.riskwarning.common.po.analysis;

import com.riskwarning.common.enums.analysis.*;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/** 逐 Run/Behavior 的模型调用审计，只保留响应，不存储完整 Prompt。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_analysis_attempt", uniqueConstraints = @UniqueConstraint(
        name = "uq_analysis_attempt", columnNames = {"analysis_run_id", "behavior_id", "attempt_no"}))
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class AnalysisAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;
    @Column(name = "analysis_run_id", nullable = false, length = 64)
    private String analysisRunId;
    @Column(name = "behavior_id", nullable = false, length = 128)
    private String behaviorId;
    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;
    @Column(name = "context_hash", nullable = false, length = 64)
    private String contextHash;
    @Column(name = "model_version", nullable = false)
    private String modelVersion;
    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;
    @Type(type = "jsonb")
    @Column(name = "validation_errors", columnDefinition = "jsonb")
    private List<String> validationErrors;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AnalysisAttemptStatus status;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
