<template>
  <el-card class="behavior-fact-list" shadow="never">
    <template #header>
      <div class="card-header">
        <h3>结构化事实</h3>
        <span class="fact-count">共 {{ behaviors.length }} 条</span>
      </div>
    </template>

    <div v-loading="loading" class="list-body">
      <el-empty
        v-if="!loading && behaviors.length === 0"
        description="本次评估未抽取到结构化事实"
        :image-size="90"
      />

      <div
        v-for="behavior in behaviors"
        :key="behavior.id"
        class="fact-card"
        :class="{ 'is-active': behavior.id === selectedId }"
        @click="$emit('select', behavior.id)"
      >
        <p class="fact-description">{{ behavior.description }}</p>

        <div class="fact-triple">
          <span v-for="item in tripleOf(behavior)" :key="item.label" class="triple-item">
            <span class="triple-label">{{ item.label }}</span>
            <span class="triple-value" :class="{ 'is-missing': item.value === null }">
              {{ item.value === null ? '证据未支持' : item.value }}
            </span>
          </span>
        </div>

        <div class="fact-meta">
          <el-tag :type="statusType(behavior.status)" size="small">
            {{ statusLabel(behavior.status) }}
          </el-tag>
          <span v-if="hasQuantity(behavior)" class="meta-item">
            {{ behavior.quantitativeValue }} {{ behavior.quantitativeUnit }}
          </span>
          <span v-if="behavior.dimension" class="meta-item">{{ behavior.dimension }}</span>
          <span class="meta-item evidence-count">引用证据 {{ behavior.evidenceIds.length }} 条</span>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import type { BehaviorStatus, StructuredBehaviorVO } from '@/types/evidence'

interface Props {
  behaviors: StructuredBehaviorVO[]
  selectedId: string | null
  loading?: boolean
}

defineProps<Props>()

defineEmits<{
  select: [id: string]
}>()

type TagType = 'success' | 'warning' | 'info' | 'danger'

const STATUS_LABELS: Record<BehaviorStatus, string> = {
  COMPLETED: '已完成',
  IN_PROGRESS: '进行中',
  PAUSED: '已暂停',
  TERMINATED: '已终止',
  UNKNOWN: '状态证据不足'
}

const STATUS_TYPES: Record<BehaviorStatus, TagType> = {
  COMPLETED: 'success',
  IN_PROGRESS: 'warning',
  PAUSED: 'info',
  TERMINATED: 'danger',
  UNKNOWN: 'info'
}

/** status 为 null 与 UNKNOWN 都表示状态证据不足，不推断为已完成 */
const statusLabel = (status: BehaviorStatus | null): string =>
  status ? STATUS_LABELS[status] ?? status : '状态证据不足'

const statusType = (status: BehaviorStatus | null): TagType =>
  status ? STATUS_TYPES[status] ?? 'info' : 'info'

const tripleOf = (behavior: StructuredBehaviorVO) => [
  { label: '主体', value: behavior.subject },
  { label: '行为', value: behavior.action },
  { label: '对象', value: behavior.object }
]

const hasQuantity = (behavior: StructuredBehaviorVO): boolean =>
  behavior.quantitativeValue !== null && behavior.quantitativeValue !== undefined
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

.fact-count {
  font-size: 13px;
  color: #909399;
}

.list-body {
  max-height: calc(100vh - 320px);
  overflow-y: auto;
}

.fact-card {
  padding: 14px 16px;
  margin-bottom: 12px;
  border: 1px solid #ebeef5;
  border-left: 3px solid transparent;
  border-radius: 6px;
  background: #fafafa;
  cursor: pointer;
  transition: all 0.2s;
}

.fact-card:last-child {
  margin-bottom: 0;
}

.fact-card:hover {
  background: #ecf5ff;
}

.fact-card.is-active {
  background: #ecf5ff;
  border-left-color: #409eff;
}

.fact-description {
  margin: 0 0 10px 0;
  font-size: 14px;
  line-height: 1.6;
  color: #303133;
}

.fact-triple {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 10px;
}

.triple-item {
  font-size: 12px;
}

.triple-label {
  color: #909399;
  margin-right: 4px;
}

.triple-value {
  color: #606266;
}

.triple-value.is-missing {
  color: #c0c4cc;
  font-style: italic;
}

.fact-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.meta-item {
  font-size: 12px;
  color: #909399;
}

.evidence-count {
  margin-left: auto;
}
</style>
