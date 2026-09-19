import request from '@/utils/request';
import type { Result } from '@/types';
import type { AnalysisOverviewVO } from '@/types/analysis';
export function getAnalysisOverview(
    assessmentId: number,
    projectId: number,
    analysisRunId?: string
): Promise<Result<AnalysisOverviewVO>> {
    return request.get(`processing/analysis/overview/${assessmentId}`, {
        params: { projectId, ...(analysisRunId ? { analysisRunId } : {}) }
    });
}
