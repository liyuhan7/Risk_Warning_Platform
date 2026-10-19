import request from '@/utils/request'
import type { Result } from '@/types'

export interface RequirementEvaluation {
  id: string
  behaviorId: string
  indicatorId: string
  requirementId: string
  regulationId: string
  judgment: string
  earned: number | null
  maximum: number | null
  evidenceIds: string[]
  reason: string | null
  conflict: boolean
  gapType: string | null
  gapValue: number | null
  gapUnit: string | null
}

export interface ScoringSourceBundle {
  indicator: { id: string; name: string }
  relations: Array<{
    regulationId: string
    regulation: { name: string; fullText: string }
    relation: { supportingText: string; applicability: string }
  }>
}

export interface ScoringResults {
  analysisRunId: string | null
  evaluations: RequirementEvaluation[]
  sourceBundles: Record<string, ScoringSourceBundle>
  behaviorDescriptions: Record<string, string>
  indicatorDecisions: Array<{
    indicatorId: string
    status: string
    calculatedScore: number | null
    riskLevel: string | null
  }>
}

export function getRequirementScores(assessmentId: number, projectId: number, analysisRunId: string): Promise<Result<ScoringResults>> {
  return request.get(`processing/analysis/scoring/${assessmentId}`, { params: { projectId, analysisRunId } })
}
