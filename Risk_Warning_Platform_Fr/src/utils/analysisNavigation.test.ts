import { describe, expect, it } from 'vitest'
import {
  normalizeAnalysisView,
  resolveAnalysisRunId,
  showsLegacyAnalysisViews,
  withAnalysisRunId
} from './analysisNavigation'

describe('分析结果导航规则', () => {
  it.each(['P2_RETRIEVAL', 'P2_RETRIEVAL_DEGRADED'])('%s 仅显示分析入口', mode => {
    expect(showsLegacyAnalysisViews(mode)).toBe(false)
    expect(normalizeAnalysisView('risk', mode)).toBe('analysis')
  })

  it.each(['LEGACY', 'DECISION_PRESENT', undefined])('%s 保留历史入口', mode => {
    expect(showsLegacyAnalysisViews(mode)).toBe(true)
    expect(normalizeAnalysisView('overview', mode)).toBe('overview')
  })

  it('从 URL 恢复运行 ID，并将实际运行写回 runId', () => {
    expect(resolveAnalysisRunId({ runId: 'exact-run' })).toBe('exact-run')
    expect(resolveAnalysisRunId({ analysisRunId: 'legacy-run' })).toBe('legacy-run')
    expect(resolveAnalysisRunId({ runId: 'exact-run', analysisRunId: 'legacy-run' })).toBe('exact-run')
    expect(withAnalysisRunId({ assessmentId: '8', analysisRunId: 'legacy-run' }, 'actual-run')).toEqual({
      assessmentId: '8',
      runId: 'actual-run'
    })
  })
})
