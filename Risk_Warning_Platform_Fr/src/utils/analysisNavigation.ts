import type { LocationQuery, LocationQueryRaw, LocationQueryValueRaw } from 'vue-router'

export type AnalysisView = 'analysis' | 'overview' | 'indicator' | 'risk' | 'evidence'

type SupportedQuery = LocationQuery | LocationQueryRaw

function firstQueryValue(value: LocationQueryValueRaw | LocationQueryValueRaw[]): string | undefined {
  const candidate = Array.isArray(value) ? value[0] : value
  return candidate === null || candidate === undefined ? undefined : String(candidate).trim() || undefined
}

// URL 规范字段为 runId；读取时兼容早期使用的 analysisRunId。
export function resolveAnalysisRunId(query: SupportedQuery): string | undefined {
  return firstQueryValue(query.runId) ?? firstQueryValue(query.analysisRunId)
}

export function withAnalysisRunId(query: SupportedQuery, analysisRunId: string): LocationQueryRaw {
  const nextQuery: LocationQueryRaw = { ...query, runId: analysisRunId }
  delete nextQuery.analysisRunId
  return nextQuery
}

export function showsDecisionViews(analysisMode: string | null | undefined): boolean {
  return analysisMode === 'REASONING_RULES_V1'
}

export function normalizeAnalysisView(
  activeView: AnalysisView,
  analysisMode: string | null | undefined
): AnalysisView {
  return showsDecisionViews(analysisMode) || activeView === 'analysis' ? activeView : 'analysis'
}
