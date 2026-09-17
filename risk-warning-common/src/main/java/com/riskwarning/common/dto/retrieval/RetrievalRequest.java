package com.riskwarning.common.dto.retrieval;

import lombok.*;

/** 已构建的事实查询；三项 ID 透传，本服务不承担 Behavior 数据隔离查询。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalRequest {
    private String queryText;
    private String behaviorId;
    private String analysisRunId;
    private Long assessmentId;
    /** null 使用服务默认值。 */
    private Integer indicatorTopK;
    private Integer regulationTopK;
    private RetrievalFilter filter;
}
