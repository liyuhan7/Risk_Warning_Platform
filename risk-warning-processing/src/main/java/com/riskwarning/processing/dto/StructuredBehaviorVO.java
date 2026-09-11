package com.riskwarning.processing.dto;

import com.riskwarning.common.po.behavior.Behavior;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 结构化事实展示契约。
 *
 * 与 {@link EvidenceVO} 一致，时间统一格式化为字符串；抽取未命中的字段保持 null，
 * 页面须按"证据未支持"呈现，向量字段不对外返回。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredBehaviorVO {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String id;

    private String subject;

    private String action;

    private String object;

    private String status;

    private String behaviorDate;

    /** 数值事实；对应实体的 quantitativeData，对外统一命名 quantitativeValue。 */
    private Double quantitativeValue;

    private String quantitativeUnit;

    private String description;

    private Double confidence;

    private List<String> tags;

    private String type;

    private String dimension;

    private List<String> evidenceIds;

    private String extractionModel;

    private String extractionPromptVersion;

    private String schemaVersion;

    private Long projectId;

    private Long assessmentId;

    private String analysisRunId;

    private Long sourceDocumentId;

    private String createdAt;

    public static StructuredBehaviorVO from(Behavior behavior) {
        return StructuredBehaviorVO.builder()
                .id(behavior.getId())
                .subject(behavior.getSubject())
                .action(behavior.getAction())
                .object(behavior.getObject())
                .status(behavior.getStatus())
                .behaviorDate(behavior.getBehaviorDate() == null
                        ? null : behavior.getBehaviorDate().format(TIME_FORMATTER))
                .quantitativeValue(behavior.getQuantitativeData())
                .quantitativeUnit(behavior.getQuantitativeUnit())
                .description(behavior.getDescription())
                .confidence(behavior.getConfidence())
                .tags(behavior.getTags())
                .type(behavior.getType())
                .dimension(behavior.getDimension())
                .evidenceIds(behavior.getEvidenceIds())
                .extractionModel(behavior.getExtractionModel())
                .extractionPromptVersion(behavior.getExtractionPromptVersion())
                .schemaVersion(behavior.getSchemaVersion())
                .projectId(behavior.getProjectId())
                .assessmentId(behavior.getAssessmentId())
                .analysisRunId(behavior.getAnalysisRunId())
                .sourceDocumentId(behavior.getSourceDocumentId())
                .createdAt(behavior.getCreatedAt() == null
                        ? null : behavior.getCreatedAt().format(TIME_FORMATTER))
                .build();
    }
}