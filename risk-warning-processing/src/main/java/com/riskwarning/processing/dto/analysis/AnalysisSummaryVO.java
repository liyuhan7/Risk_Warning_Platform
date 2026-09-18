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
    private int conclusionCount;
    private int decisionCount;
    private int retrievalSuccessCount;
    private int noCandidateCount;
    private int retrievalFailedCount;
    private int notAttemptedCount;
    private int notDemoInputCount;
    private int recallGapCount;
    private int insufficientEvidenceCount;
}
