import type { RiskVO } from '@/types/report'

export type RiskLevelKey = 'HIGH_RISK' | 'MEDIUM_RISK' | 'LOW_RISK'

export type RiskLevelTagType = 'danger' | 'warning' | 'success' | 'info'

export function normalizeRiskLevel(level?: unknown): RiskLevelKey | '' {
  if (!level) return ''
  const v = String(level).trim().toUpperCase()
  if (v === 'HIGH_RISK' || v === 'MEDIUM_RISK' || v === 'LOW_RISK') return v
  return ''
}

export function getRiskLevelLabel(level?: unknown): string {
  switch (normalizeRiskLevel(level)) {
    case 'HIGH_RISK':
      return '高风险'
    case 'MEDIUM_RISK':
      return '中风险'
    case 'LOW_RISK':
      return '低风险'
    default:
      return level ? String(level) : '-'
  }
}

export function getRiskLevelTagType(level?: unknown): RiskLevelTagType {
  switch (normalizeRiskLevel(level)) {
    case 'HIGH_RISK':
      return 'danger'
    case 'MEDIUM_RISK':
      return 'warning'
    case 'LOW_RISK':
      return 'success'
    default:
      return 'info'
  }
}

export function normalizeDimension(dimension?: unknown): string {
  if (!dimension) return ''
  return String(dimension).trim()
}

const dimensionLabelMap: Record<string, string> = {
  '企业国际合作风险': '国际化经营风险',
  '产品合规风险': '产品法律风险',
  '劳务合规风险': '劳动法律风险'
}

export function getDimensionLabel(dimension?: unknown): string {
  const normalizedDimension = normalizeDimension(dimension)
  if (!normalizedDimension) return '-'
  return dimensionLabelMap[normalizedDimension] || normalizedDimension
}

export function filterRisks(risks: RiskVO[], dimension?: string, riskLevel?: string): RiskVO[] {
  const dim = normalizeDimension(dimension)
  const lvl = normalizeRiskLevel(riskLevel)

  return risks.filter(r => {
    const okDim = !dim || normalizeDimension(r.dimension) === dim
    const okLvl = !lvl || normalizeRiskLevel(r.riskLevel) === lvl
    return okDim && okLvl
  })
}

export function getDimensionBadgeType(
  risks: RiskVO[],
  dimension: string
): 'danger' | 'warning' | 'success' | 'info' {
  const dim = normalizeDimension(dimension)
  const dimensionRisks = risks.filter(r => normalizeDimension(r.dimension) === dim)
  if (dimensionRisks.some(r => normalizeRiskLevel(r.riskLevel) === 'HIGH_RISK')) return 'danger'
  if (dimensionRisks.some(r => normalizeRiskLevel(r.riskLevel) === 'MEDIUM_RISK')) return 'warning'
  if (dimensionRisks.some(r => normalizeRiskLevel(r.riskLevel) === 'LOW_RISK')) return 'success'
  return 'info'
}

