package com.riskwarning.processing.dto.analysis;

import lombok.*;

import java.util.List;

/**
 * 后续分析（P3）的只读上下文契约：每个事实的结构化字段、证据引用与真实检索候选。
 * 本契约只描述已经持久化的 P2 产物，不包含合规判断、规则结论或风险定级。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisContextVO {
    /** 上下文契约版本；新增可选键递增次版本，改变既有键语义属破坏性变更。 */
    private String contextVersion;
    private Long assessmentId;
    private Long projectId;
    private String analysisRunId;
    private String runStatus;
    /** 与概览一致的运行级来源标识，用于区分演示结论与真实检索基座。 */
    private String analysisMode;
    private String generatedAt;
    private int behaviorCount;
    /** 满足全部门禁、可进入后续分析的事实数。 */
    private int readyForAnalysisCount;
    /** 被降级门禁拦截的事实数，等于不足项计数之和。 */
    private int blockedCount;
    /** 降级原因到事实数量的分布，取值见 BehaviorAnalysisContextVO.blockedReason。 */
    private java.util.Map<String, Integer> blockedReasons;
    private List<BehaviorAnalysisContextVO> items;
}
