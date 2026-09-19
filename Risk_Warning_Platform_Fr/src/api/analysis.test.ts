import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/utils/request', () => ({
  default: { get }
}))

import { getAnalysisOverview } from './analysis'

describe('getAnalysisOverview', () => {
  beforeEach(() => {
    get.mockReset()
  })

  it('传递指定的分析运行 ID', () => {
    getAnalysisOverview(12, 34, 'run-2026')

    expect(get).toHaveBeenCalledWith('processing/analysis/overview/12', {
      params: { projectId: 34, analysisRunId: 'run-2026' }
    })
  })

  it('未指定运行时保持原调用兼容', () => {
    getAnalysisOverview(12, 34)

    expect(get).toHaveBeenCalledWith('processing/analysis/overview/12', {
      params: { projectId: 34 }
    })
  })
})
