/**
 * 证据回溯 API
 *
 * 只读接口位于 processing 服务：网关前缀 /api/processing（request 的 baseURL 已含 /api）。
 * 真实接口已验证（2026-09-11 T4 联调）；P1 收尾已移除本地 mock 数据与开关，页面只读真实结果。
 */
import request from '@/utils/request'
import type { Result } from '@/types'
import type { BehaviorListVO, EvidenceVO } from '@/types/evidence'

/**
 * 查询评估的结构化事实
 *
 * analysisRunId 省略时由后端解析该评估的当前成功运行；返回体回带实际使用的运行 ID，
 * 使页面能显示"看到的是哪一次运行"，而不是隐式取最近一次数据。
 */
export function getBehaviors(params: {
  projectId: number
  assessmentId: number
  analysisRunId?: string
}): Promise<Result<BehaviorListVO>> {
  return request.get('processing/behavior', { params })
}

/**
 * 按评估作用域列出证据，可选收窄到单个来源文件
 */
export function getEvidenceList(params: {
  projectId: number
  assessmentId: number
  sourceDocumentId?: number
}): Promise<Result<EvidenceVO[]>> {
  return request.get('processing/evidence', { params })
}

/**
 * 按行为引用的证据 ID 批量取回原文
 *
 * 后端会按项目与评估复核归属，越权 ID 整批拒绝；引用但已不存在的 ID 不会出现在返回值中，
 * 页面据此显示"证据引用缺失"。
 */
export function getEvidenceByIds(params: {
  ids: string[]
  projectId: number
  assessmentId: number
}): Promise<Result<EvidenceVO[]>> {
  return request.get('processing/evidence/by-ids', {
    params: {
      ids: params.ids.join(','),
      projectId: params.projectId,
      assessmentId: params.assessmentId
    }
  })
}