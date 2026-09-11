/**
 * Evidence 与 Structured Behavior 类型定义
 * 对应后端 risk-warning-processing 模块的只读查询契约（后端文档 documents/local/plan1/p1-08-execution.md）
 */

/** 行为状态；UNKNOWN 表示原文存在事实但状态证据不足，不是"无状态" */
export type BehaviorStatus = 'COMPLETED' | 'IN_PROGRESS' | 'PAUSED' | 'TERMINATED' | 'UNKNOWN'

/**
 * 证据回溯契约
 *
 * pageNumber 与 charStart/charEnd 允许为空：页码无法从材料中获得时页面须显示"页码未知"，
 * 不能用 "-" 与"解析失败"混为一谈。
 */
export interface EvidenceVO {
  evidenceId: string
  sourceDocumentId: number
  sourceFileName: string
  pageNumber: number | null
  segmentIndex: number
  charStart: number | null
  charEnd: number | null
  text: string
  textHash: string
  createdAt: string | null
}

/**
 * 结构化事实契约
 *
 * 抽取未命中的字段为 null，页面须按"证据未支持"呈现，禁止臆造默认值或显示为合规。
 */
export interface StructuredBehaviorVO {
  id: string
  subject: string | null
  action: string | null
  object: string | null
  status: BehaviorStatus | null
  behaviorDate: string | null
  quantitativeValue: number | null
  quantitativeUnit: string | null
  description: string
  confidence: number | null
  tags: string[] | null
  type: string | null
  dimension: string | null
  evidenceIds: string[]
  extractionModel: string | null
  extractionPromptVersion: string | null
  schemaVersion: string | null
  projectId: number
  assessmentId: number
  analysisRunId: string
  sourceDocumentId: number | null
  createdAt: string | null
}

/**
 * 行为查询结果
 *
 * hasSuccessfulRun 区分两种空态：该评估尚无成功运行（可能仍在评估中或已失败），
 * 与"运行成功但未抽取到事实"。二者对用户的下一步提示不同，不能合并成一个空列表。
 */
export interface BehaviorListVO {
  analysisRunId: string | null
  hasSuccessfulRun: boolean
  behaviors: StructuredBehaviorVO[]
}
