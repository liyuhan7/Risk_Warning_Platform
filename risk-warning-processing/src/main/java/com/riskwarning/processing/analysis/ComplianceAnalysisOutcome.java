package com.riskwarning.processing.analysis;

import com.riskwarning.common.enums.analysis.AnalysisAuditStatus;
import com.riskwarning.common.po.analysis.AnalysisResult;
import lombok.*;

/** 单 Behavior 分析结果；未选择指标或修复失败时不伪造 AnalysisResult。 */
@Value
public class ComplianceAnalysisOutcome {
    AnalysisAuditStatus status;
    AnalysisResult result;
}
