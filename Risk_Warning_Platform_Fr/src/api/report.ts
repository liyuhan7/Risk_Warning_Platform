import request from '@/utils/request'
import type { Result } from '@/types'
import type {
  IndicatorDistributionVO,
  RiskVO,
  AssessmentDetailVO
} from '@/types/report'

/**
 * 获取指标分布结果
 * @param assessmentId 评估ID
 */
export function getIndicatorDistribution(assessmentId: number): Promise<Result<IndicatorDistributionVO>> {
  return request.get(`report/indicatorResult/${assessmentId}`)
}

/**
 * 获取风险列表
 * @param assessmentId 评估ID
 * @param dimension 风险维度（可选）
 * @param riskLevel 风险等级（可选）
 */
export function getRiskList(
  assessmentId: number,
  dimension?: string,
  riskLevel?: string
): Promise<Result<RiskVO[]>> {
  return request.get('report/risk', {
    params: {
      assessmentId,
      dimension,
      riskLevel
    }
  })
}

/**
 * 获取评估总览报告
 * @param assessmentId 评估ID
 */
export function getAssessmentGeneral(assessmentId: number): Promise<Result<AssessmentDetailVO>> {
  return request.get(`report/general/${assessmentId}`)
}

