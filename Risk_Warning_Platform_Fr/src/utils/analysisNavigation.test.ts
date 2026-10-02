import { describe, expect, it } from 'vitest'
import {
  normalizeAnalysisView,
  resolveAnalysisRunId,
  showsDecisionViews,
  withAnalysisRunId
} from './analysisNavigation'

describe('分析结果导航规则', () => {
  it.each(['NOT_STARTED', null, undefined])('%s 仅显示分析入口', mode => {
    expect(showsDecisionViews(mode)).toBe(false)
    for (const view of ['analysis', 'overview', 'indicator', 'risk', 'evidence'] as const) {
      expect(normalizeAnalysisView(view, mode)).toBe('analysis')
    }
  })

  it.each(['REASONING_RULES_V1'])('%s 显示决策与报告入口', mode => {
    expect(showsDecisionViews(mode)).toBe(true)
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
