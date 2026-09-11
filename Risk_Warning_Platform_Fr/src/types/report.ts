/**
 * 评估报告相关类型定义
 * 对应后端 risk-warning-report 模块的 VO 类型
 */

/**
 * 风险维度枚举
 */
export enum RiskDimensionEnum {
  ENTERPRISE_RELATED_RISK = '企业关联方风险',
  PRODUCT_LEGITIMACY_RISK = '产品合规风险',
  LABOR_LEGITIMACY_RISK = '劳务合规风险',
  ENTERPRISE_CREDIT_RISK = '企业信用风险',
  ENTERPRISE_INTERNATIONAL_COOPERATION_RISK = '企业国际合作风险',
  SUPPLY_CHAIN_RISK = '供应链风险'
}

/**
 * 风险等级枚举
 */
export enum RiskLevelEnum {
  HIGH_RISK = 'HIGH_RISK',
  MEDIUM_RISK = 'MEDIUM_RISK',
  LOW_RISK = 'LOW_RISK'
}

/**
 * 评估状态枚举
 */
export enum AssessmentStatusEnum {
  PENDING = 'PENDING',
  IN_PROGRESS = 'IN_PROGRESS',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED'
}

/**
 * 分数区间分布项
 * 对应后端 ScoreRatioDistributionItemVO
 */
export interface ScoreRatioDistributionItemVO {
  startScoreRatio: number
  endScoreRatio: number
  ratio: number
  totalScore: number
  totalCount: number
  riskTriggeredCount: number
  safeCount: number
}

/**
 * 指标分布统计
 * 对应后端 IndicatorDistributionVO
 */
export interface IndicatorDistributionVO {
  riskDimensionEnum: RiskDimensionEnum | null
  assessmentId: number
  totalScore: number
  totalCount: number
  riskTriggeredCount: number
  safeCount: number
  assessmentTime: string
  scoreDistributions: ScoreRatioDistributionItemVO[]
  dimensionDistributions: Record<string, IndicatorDistributionVO>
}

/**
 * 相关指标
 */
export interface RelatedIndicator {
  indicatorId: string
  indicatorName: string
  score: number
  threshold: number
  isPrimaryTrigger: boolean
}

/**
 * 相关法规
 */
export interface RelatedRegulation {
  regulationId: string
  regulationName: string
  violationType: string
  complianceRequirement: string
}

/**
 * 风险详情
 * 对应后端 RiskVO
 */
export interface RiskVO {
  id: string
  projectId: number
  assessmentId: number
  name: string
  dimension: string
  description: string
  riskLevel: string
  probability: number
  impact: number
  detectability: number
  status: string
  responsibleParty: string
  affectedObjects: string[]
  impactScope: string
  countermeasures: string
  relatedIndicators: RelatedIndicator[]
  relatedRegulations: RelatedRegulation[]
  createAt: string
  processingStatus?: string
}

/**
 * 总体评估结果
 * 对应后端 OverallResult
 */
export interface OverallResult {
  overallScore: number
  overallRiskLevel: RiskLevelEnum
  status: AssessmentStatusEnum
}

/**
 * 风险汇总
 * 对应后端 RiskSummary
 */
export interface RiskSummary {
  totalRisks: number
  highRiskCount: number
  mediumRiskCount: number
  lowRiskCount: number
}

/**
 * 指标概览
 * 对应后端 IndicatorOverview
 */
export interface IndicatorOverview {
  behaviorIndicators: number
  questionnaireIndicators: number
  riskTriggeredIndicators: number
  safeIndicators: number
}

/**
 * 维度风险分布
 * 对应后端 DimensionRiskDistribution
 */
export interface DimensionRiskDistribution {
  dimension: string
  riskCount: number
  lowRiskCount: number
  mediumRiskCount: number
  highRiskCount: number
}

/**
 * 评估详情
 * 对应后端 AssessmentDetailVO
 */
export interface AssessmentDetailVO {
  projectId: number
  assessmentId: number
  assessmentDate: string
  overallResult: OverallResult
  riskSummary: RiskSummary
  indicatorOverview: IndicatorOverview
  dimensionRiskDistribution: Record<string, DimensionRiskDistribution>
}

