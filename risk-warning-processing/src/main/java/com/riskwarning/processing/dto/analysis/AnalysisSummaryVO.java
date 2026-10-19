package com.riskwarning.processing.dto.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import java.util.List;

/**
 * 分析概览只读展示契约。
 * 运行摘要按版本追加字段（1.2 增加 schemaVersion/analysisMode/registryVersion/datasetVersion），
 * 概览只消费展示需要的字段，其余字段忽略，避免版本升级时反序列化失败。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisSummaryVO {
    private int behaviorCount;
    /** Reasoning 覆盖摘要由权威记录派生，Retrieval 不解释此字段。 */
    private com.riskwarning.common.enums.analysis.CompletionKind completionKind;
    private int analyzedCount;
    private int noApplicableCandidateCount;
    private int needsReviewCount;
    private int unsupportedRuleCount;
    private int conflictCount;
    private int conclusionCount;
    private int decisionCount;
    private int retrievalSuccessCount;
    private int noCandidateCount;
    private int retrievalFailedCount;
    private int notAttemptedCount;
    private int notDemoInputCount;
    private int recallGapCount;
    private int insufficientEvidenceCount;
    private int snapshotUnavailableCount;
    private int waitingForAnalysisCount;
}
