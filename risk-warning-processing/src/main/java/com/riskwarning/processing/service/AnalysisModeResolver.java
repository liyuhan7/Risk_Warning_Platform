package com.riskwarning.processing.service;

import com.riskwarning.common.enums.analysis.AnalysisAuditStatus;
import com.riskwarning.common.po.analysis.AnalysisResult;
import com.riskwarning.common.po.analysis.RetrievalAudit;

import java.util.List;

/**
 * 由持久化产物推导运行级分析来源，供概览与后续分析上下文共用。
 * 结论优先级高于审计：同一运行既有结论又有审计时，以结论来源为准。
 */
final class AnalysisModeResolver {

    static final String LEGACY_MOCK_DEMO = "LEGACY_MOCK_DEMO";
    static final String DECISION_PRESENT = "DECISION_PRESENT";
    static final String P2_RETRIEVAL = "P2_RETRIEVAL";
    static final String P2_RETRIEVAL_DEGRADED = "P2_RETRIEVAL_DEGRADED";
    static final String NO_ARTIFACTS = "NO_ARTIFACTS";

    private AnalysisModeResolver() { }

    static String resolve(List<AnalysisResult> resultRows, List<RetrievalAudit> auditRows) {
        if (resultRows != null && !resultRows.isEmpty()) {
            boolean mock = resultRows.stream().anyMatch(r -> "mock-fixture-v1".equals(r.getModelVersion()));
            return mock ? LEGACY_MOCK_DEMO : DECISION_PRESENT;
        }
        if (auditRows != null && !auditRows.isEmpty()) {
            boolean waiting = auditRows.stream().anyMatch(a -> a.getAnalysisStatus() == AnalysisAuditStatus.WAITING_P3);
            return waiting ? P2_RETRIEVAL : P2_RETRIEVAL_DEGRADED;
        }
        return NO_ARTIFACTS;
    }
}
