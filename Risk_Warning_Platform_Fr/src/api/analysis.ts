import request from '@/utils/request';
import type { Result } from '@/types';
import type { AnalysisOverviewVO, DisplayStatus } from '@/types/analysis';
import { mockOverview } from '@/mock/analysisOverview';
export const analysisMockEnabled = import.meta.env.VITE_P210_MOCK === 'true';
export function getAnalysisOverview(assessmentId: number, projectId: number, mockStatus?: DisplayStatus): Promise<Result<AnalysisOverviewVO>> {
    if (analysisMockEnabled)
        return Promise.resolve({ code: 200, message: 'success', data: mockOverview(assessmentId, projectId, mockStatus) });
    return request.get(`processing/analysis/overview/${assessmentId}`, { params: { projectId } });
}
