package com.riskwarning.processing.dto.analysis;

import lombok.*;
import java.util.List;

/** 分析概览只读展示契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisOverviewVO {
    private Long assessmentId;
    private Long projectId;
    private String projectName;
    private String assessmentDate;
    private String displayStatus;
    private boolean truncated;
    private AnalysisRunSummaryVO run;
    private AnalysisSummaryVO summary;
    private List<BehaviorAnalysisGroupVO> behaviorGroups;
}
