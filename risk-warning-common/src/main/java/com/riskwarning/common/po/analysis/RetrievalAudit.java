package com.riskwarning.common.po.analysis;

import com.riskwarning.common.dto.retrieval.RetrievalCandidateSnapshot;
import com.riskwarning.common.enums.analysis.*;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/** 每个运行内每个 Behavior 的检索审计，同 Run 重试覆盖原记录。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_retrieval_audit", uniqueConstraints =
        @UniqueConstraint(name = "uq_retrieval_audit_run_behavior", columnNames = {"analysis_run_id", "behavior_id"}))
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class RetrievalAudit {
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
    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;
    @Column(name = "query_template_version", nullable = false)
    private String queryTemplateVersion;
    @Column(name = "filter_version", nullable = false)
    private String filterVersion;
    @Column(name = "filter_enabled", nullable = false)
    private Boolean filterEnabled;
    @Column(name = "filter_applied", nullable = false)
    private Boolean filterApplied;
    @Column(name = "filter_expression", nullable = false, columnDefinition = "TEXT")
    private String filterExpression;
    @Column(name = "embedding_model")
    private String embeddingModel;
    @Column(name = "embedding_version")
    private String embeddingVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "retrieval_status", nullable = false, length = 32)
    private RetrievalAuditStatus retrievalStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 32)
    private AnalysisAuditStatus analysisStatus;
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private List<RetrievalCandidateSnapshot> candidates;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "error_type")
    private String errorType;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
