<template>
  <el-card class="evidence-viewer" shadow="never">
    <template #header>
      <div class="card-header">
        <h3>原文证据</h3>
        <span v-if="behavior" class="evidence-count">引用 {{ evidences.length }} 条</span>
      </div>
    </template>

    <div v-loading="loading" class="viewer-body">
      <el-empty
        v-if="!behavior"
        description="请从左侧选择一条结构化事实，查看其引用的原文"
        :image-size="90"
      />

      <template v-else>
        <div class="selected-fact">
          <span class="fact-label">当前事实</span>
          <span class="fact-text">{{ behavior.description }}</span>
        </div>

        <el-alert
          v-if="missingIds.length > 0"
          type="warning"
          :closable="false"
          show-icon
          title="证据引用缺失"
          :description="`本条事实引用了 ${missingIds.length} 条在当前评估中不存在的证据：${missingIds.join('、')}`"
        />

        <el-empty
          v-if="!loading && evidences.length === 0 && missingIds.length === 0"
          description="本条事实未引用任何证据"
          :image-size="90"
        />

        <div
          v-for="evidence in evidences"
          :key="evidence.evidenceId"
          class="evidence-card"
        >
          <div class="evidence-header">
            <span class="file-name">{{ evidence.sourceFileName }}</span>
            <span class="page-label">{{ pageLabel(evidence.pageNumber) }}</span>
            <span class="segment-label">第 {{ evidence.segmentIndex + 1 }} 段</span>
          </div>

          <div class="evidence-id">
            <span class="id-label">Evidence ID</span>
            <code>{{ evidence.evidenceId }}</code>
          </div>

          <p class="evidence-text">
            <template v-for="(part, index) in textParts(evidence)" :key="index">
              <mark v-if="part.matched">{{ part.text }}</mark>
              <span v-else>{{ part.text }}</span>
            </template>
          </p>

          <div class="evidence-footer">
            <span v-if="evidence.charStart !== null && evidence.charEnd !== null">
              原文位置 {{ evidence.charStart }}-{{ evidence.charEnd }}
            </span>
            <span v-if="evidence.createdAt" class="created-at">{{ evidence.createdAt }}</span>
          </div>
        </div>
      </template>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import type { EvidenceVO, StructuredBehaviorVO } from '@/types/evidence'

interface Props {
  behavior: StructuredBehaviorVO | null
  evidences: EvidenceVO[]
  missingIds: string[]
  loading?: boolean
}

const props = defineProps<Props>()

interface TextPart {
  text: string
  matched: boolean
}

/** 短于该长度的重合视为偶然，不做高亮，避免满屏碎片 */
const MIN_FRAGMENT_LENGTH = 4

/** 页码缺失时必须说明"未知"，不能与解析失败或第 0 页混淆 */
const pageLabel = (pageNumber: number | null): string =>
  pageNumber === null ? '页码未知' : `第 ${pageNumber} 页`

const escapeRegExp = (value: string): string =>
  value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** 可在原文中直接定位的三元组片段 */
const directTerms = (behavior: StructuredBehaviorVO): string[] =>
  [behavior.subject, behavior.action, behavior.object]
    .filter((item): item is string => typeof item === 'string' && item.trim().length >= 2)
    .map(item => item.trim())

/** 两个字符串的最长公共子串，用于三元组无法直接命中时找"重合片段" */
const longestCommonFragment = (left: string, right: string): string => {
  if (!left || !right) {
    return ''
  }
  let best = ''
  let previous = new Array<number>(right.length + 1).fill(0)
  for (let i = 1; i <= left.length; i++) {
    const current = new Array<number>(right.length + 1).fill(0)
    for (let j = 1; j <= right.length; j++) {
      if (left[i - 1] === right[j - 1]) {
        current[j] = previous[j - 1] + 1
        if (current[j] > best.length) {
          best = left.slice(i - current[j], i)
        }
      }
    }
    previous = current
  }
  return best
}

/**
 * 把证据原文切分为高亮与非高亮片段
 *
 * 注意：证据的 charStart/charEnd 是原文所在文档的位置，不是本段 text 内部的偏移，
 * 因此不能用它切分 text，否则会切错位置；定位一律基于"描述与原文的重合片段"。
 */
const textParts = (evidence: EvidenceVO): TextPart[] => {
  const text = evidence.text ?? ''
  if (!text) {
    return []
  }
  const behavior = props.behavior
  if (!behavior) {
    return [{ text, matched: false }]
  }

  let matched = directTerms(behavior).filter(term => text.includes(term))
  if (matched.length === 0) {
    const fragment = longestCommonFragment(behavior.description ?? '', text)
    if (fragment.length >= MIN_FRAGMENT_LENGTH) {
      matched = [fragment]
    }
  }
  if (matched.length === 0) {
    return [{ text, matched: false }]
  }

  const pattern = new RegExp(`(${matched.map(escapeRegExp).join('|')})`, 'g')
  return text
    .split(pattern)
    .filter(part => part !== '')
    .map(part => ({ text: part, matched: matched.includes(part) }))
}
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.evidence-count {
  font-size: 13px;
  color: #909399;
}

.viewer-body {
  max-height: calc(100vh - 320px);
  overflow-y: auto;
}

.selected-fact {
  padding: 10px 12px;
  margin-bottom: 12px;
  border-radius: 6px;
  background: #f4f4f5;
  font-size: 13px;
  line-height: 1.6;
}

.fact-label {
  color: #909399;
  margin-right: 8px;
}

.fact-text {
  color: #303133;
}

.evidence-card {
  padding: 14px 16px;
  margin-bottom: 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}

.evidence-card:last-child {
  margin-bottom: 0;
}

.evidence-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.file-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.page-label,
.segment-label {
  font-size: 12px;
  color: #606266;
  padding: 1px 8px;
  border-radius: 10px;
  background: #f0f2f5;
}

.evidence-id {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.id-label {
  font-size: 12px;
  color: #909399;
}

.evidence-id code {
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}

.evidence-text {
  margin: 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: #fafafa;
  font-size: 14px;
  line-height: 1.8;
  color: #303133;
  word-break: break-word;
}

.evidence-text mark {
  background: #fff3cd;
  color: #303133;
  padding: 0 2px;
  border-radius: 2px;
}

.evidence-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}

.created-at {
  margin-left: auto;
}
</style>
