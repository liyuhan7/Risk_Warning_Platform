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
    /**
     * 运行级分析来源标识，由该运行的结论与审计推导，不作为可写字段。
     * LEGACY_MOCK_DEMO：存在演示结论；DECISION_PRESENT：存在非演示结论；
     * P2_RETRIEVAL：真实检索候选等待后续分析；P2_RETRIEVAL_DEGRADED：检索未产出可分析候选；
     * NO_ARTIFACTS：本次运行没有结论也没有审计。
     */
    private String analysisMode;
}
