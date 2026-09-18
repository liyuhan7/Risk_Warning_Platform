export type DisplayStatus = 'NOT_STARTED' | 'RUNNING' | 'COMPLETED_WITH_DECISION' | 'COMPLETED_WITHOUT_DECISION' | 'NO_CANDIDATES' | 'FAILED';
export interface AnalysisOverviewVO {
    assessmentId: number | null;
    projectId: number | null;
    projectName: string | null;
    assessmentDate: string | null;
    displayStatus: DisplayStatus;
    truncated: boolean;
    run: AnalysisRunSummaryVO | null;
    summary: AnalysisSummaryVO;
    behaviorGroups: BehaviorAnalysisGroupVO[];
}
export interface AnalysisRunSummaryVO {
    analysisRunId: string | null;
    status: string | null;
    startedAt: string | null;
    finishedAt: string | null;
    errorCode: string | null;
    errorMessage: string | null;
}
export interface AnalysisSummaryVO {
    behaviorCount: number;
    conclusionCount: number;
    decisionCount: number;
    retrievalSuccessCount: number;
    noCandidateCount: number;
    retrievalFailedCount: number;
    notAttemptedCount: number;
    notDemoInputCount: number;
    recallGapCount: number;
    insufficientEvidenceCount: number;
}
export interface BehaviorAnalysisGroupVO {
    behaviorId: string;
    behaviorDescription: string | null;
    behaviorType: string | null;
    behaviorDimension: string | null;
    retrievalAudit: RetrievalAuditVO | null;
    conclusions: AnalysisConclusionVO[];
}
export interface AnalysisConclusionVO {
    resultId: string;
    behaviorId: string;
    indicatorId: string | null;
    indicatorName: string | null;
    applicable: string | null;
    requirement: string | null;
    enterpriseFact: string | null;
    complianceStatus: string | null;
    gapType: string | null;
    gapValue: number | null;
    gapUnit: string | null;
    confidence: number | null;
    reasoning: string | null;
    modelVersion: string | null;
    promptVersion: string | null;
    regulationIds: string[] | null;
    regulationNames: (string | null)[] | null;
    evidenceIds: string[] | null;
    analyzedAt: string | null;
    mock: boolean;
}
export interface RetrievalAuditVO {
    queryText: string | null;
    queryTemplateVersion: string | null;
    filterVersion: string | null;
    filterEnabled: boolean | null;
    filterApplied: boolean | null;
    filterExpression: string | null;
    embeddingModel: string | null;
    embeddingVersion: string | null;
    retrievalStatus: string | null;
    analysisStatus: string | null;
    errorCode: string | null;
    errorType: string | null;
    errorMessage: string | null;
    candidateTotal: number;
    candidateTruncated: boolean;
    snapshotAvailable: boolean;
    candidates: RetrievalCandidateVO[];
}
export interface RetrievalCandidateVO {
    candidateType: string | null;
    candidateId: string | null;
    name: string | null;
    content: string | null;
    score: number | null;
    scoreType: string | null;
    rank: number | null;
    retrievalTextHash: string | null;
    contentHash: string | null;
    indicatorLevel: number | null;
    dimension: string | null;
    type: string | null;
    maxScore: number | null;
    snapshotAvailable: boolean;
}
export const labels: Record<string, string> = {
    SUCCESS: '成功', NO_CANDIDATES: '未检索到候选', FAILED: '失败', NOT_ATTEMPTED: '尚未尝试',
    RECALL_GAP: '召回缺口', INSUFFICIENT_EVIDENCE: '证据不足', NOT_DEMO_INPUT: '非演示输入',
    APPLICABLE: '适用', NOT_APPLICABLE: '不适用', UNKNOWN: '待确认', COMPLIANT: '合规',
    NON_COMPLIANT: '不合规', NEEDS_REVIEW: '需要复核', MISSING_ACTION: '缺少必要行为',
    PROHIBITED_ACTION: '存在禁止行为', QUANTITATIVE_SHORTFALL: '数量不足', QUANTITATIVE_EXCESS: '数量超限',
    DOCUMENTATION_GAP: '材料缺口', INDICATOR: '指标', REGULATION: '法规'
};
export const label = (value: string | null | undefined) => value ? (labels[value] ?? value) : '未提供';
