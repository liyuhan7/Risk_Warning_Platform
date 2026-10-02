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
    /** 最新运行不可用时，是否选择了最近可用运行。 */
    private boolean fallbackToUsableRun;
    /** 当前分析模式标识；未开始时为 NOT_STARTED。 */
    private String analysisMode;
}
