package com.riskwarning.processing.dto.analysis;

import lombok.*;
import java.util.List;

/** 分析概览只读展示契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisRunSummaryVO {
    private String analysisRunId;
    private String status;
    private String startedAt;
    private String finishedAt;
    private String errorCode;
    private String errorMessage;
}
