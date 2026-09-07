package com.riskwarning.processing.fact;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.fact.ExtractedFact;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;

/** 将已通过校验的模型事实装配成带完整系统作用域的 Behavior。 */
@Component
public class StructuredBehaviorAssembler {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String PROMPT_VERSION = "fact-extract-v1.0";

    public Behavior assemble(AnalysisScope scope, Long sourceDocumentId, ExtractedFact fact,
                             String modelId, LocalDateTime createdAt) {
        if (scope == null || sourceDocumentId == null || sourceDocumentId <= 0
                || fact == null || modelId == null || modelId.trim().isEmpty()
                || createdAt == null) {
            throw new IllegalArgumentException("事实装配缺少运行作用域、来源、模型或时间");
        }
        String description = fact.getDescription().trim();
        return Behavior.builder()
                .schemaVersion(SCHEMA_VERSION)
                .id(stableBehaviorId(scope.getAnalysisRunId(), sourceDocumentId, description))
                .projectId(scope.getProjectId())
                .assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId())
                .sourceDocumentId(sourceDocumentId)
                .subject(fact.getSubject().trim())
                .action(fact.getAction().trim())
                .object(trimNullable(fact.getObject()))
                .status(fact.getStatus())
                .behaviorDate(parseDate(fact.getBehaviorDate()))
                .quantitativeData(fact.getQuantitativeData())
                .quantitativeUnit(trimNullable(fact.getQuantitativeUnit()))
                .description(description)
                .confidence(fact.getConfidence())
                .evidenceIds(new ArrayList<>(fact.getEvidenceIds()))
                .extractionModel(modelId.trim())
                .extractionPromptVersion(PROMPT_VERSION)
                .tags(new ArrayList<>())
                .createdAt(createdAt)
                .build();
    }

    public static String stableBehaviorId(String analysisRunId, Long sourceDocumentId,
                                          String description) {
        String textHash = EvidenceChunk.sha256(EvidenceChunk.normalizeText(description));
        return EvidenceChunk.sha256(analysisRunId + "|" + sourceDocumentId + "|" + textHash)
                .substring(0, 32);
    }

    private LocalDateTime parseDate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() == 10 ? LocalDate.parse(value).atStartOfDay()
                : LocalDateTime.parse(value);
    }

    private String trimNullable(String value) {
        return value == null ? null : value.trim();
    }
}
