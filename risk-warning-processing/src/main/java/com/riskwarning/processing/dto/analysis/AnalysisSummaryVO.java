package com.riskwarning.processing.dto.analysis;

import lombok.*;
import java.util.List;

/** 分析概览只读展示契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
