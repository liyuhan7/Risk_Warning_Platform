package com.riskwarning.common.dto.analysis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 一次分析的不可变身份，禁止仅凭项目 ID 代替评估与运行归属。
 */
@Getter
@EqualsAndHashCode
public final class AnalysisScope {

    private final Long projectId;
    private final Long assessmentId;
    private final String analysisRunId;

    @JsonCreator
    public AnalysisScope(@JsonProperty("projectId") Long projectId,
                         @JsonProperty("assessmentId") Long assessmentId,
                         @JsonProperty("analysisRunId") String analysisRunId) {
        if (projectId == null || projectId <= 0 || assessmentId == null || assessmentId <= 0) {
            throw new IllegalArgumentException("项目和评估 ID 必须为正数");
        }
        if (analysisRunId == null || analysisRunId.trim().isEmpty()
                || !analysisRunId.equals(analysisRunId.trim()) || analysisRunId.length() > 64) {
            throw new IllegalArgumentException("运行 ID 必须为不含首尾空白的 1 至 64 位字符串");
        }
        this.projectId = projectId;
        this.assessmentId = assessmentId;
        this.analysisRunId = analysisRunId;
    }
}
