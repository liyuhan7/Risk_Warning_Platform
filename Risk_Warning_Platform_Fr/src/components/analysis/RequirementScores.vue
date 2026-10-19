<template>
  <el-card class="requirement-scores" v-loading="loading">
    <template #header><h3>行为要求评分</h3></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-else-if="data">
      <div class="score-toolbar">
        <el-radio-group v-model="view" @change="selected = ''">
          <el-radio-button label="behavior">按行为</el-radio-button>
          <el-radio-button label="indicator">按指标</el-radio-button>
          <el-radio-button label="regulation">按法规</el-radio-button>
        </el-radio-group>
        <el-select v-model="selected" clearable filterable placeholder="查看全部" class="score-selector">
          <el-option v-for="option in options" :key="option.id" :value="option.id" :label="option.name" />
        </el-select>
      </div>
      <el-table :data="rows" stripe>
        <el-table-column label="行为" min-width="180"><template #default="{ row }">{{ data.behaviorDescriptions[row.behaviorId] || '行为描述不可用' }}</template></el-table-column>
        <el-table-column label="指标" min-width="160"><template #default="{ row }">{{ data.sourceBundles[row.indicatorId]?.indicator.name || '指标名称不可用' }}</template></el-table-column>
        <el-table-column label="要求与依据" min-width="260"><template #default="{ row }">
          <strong>{{ source(row)?.regulation.name || '法规名称不可用' }}</strong>
          <p>{{ source(row)?.relation.supportingText }}</p>
        </template></el-table-column>
        <el-table-column label="判断" width="130"><template #default="{ row }">{{ judgments[row.judgment] || row.judgment }}<span v-if="row.conflict">（待复核）</span></template></el-table-column>
        <el-table-column label="缺口与说明" min-width="220"><template #default="{ row }">
          <span v-if="row.gapType">{{ gaps[row.gapType] || row.gapType }}<template v-if="row.gapValue != null">：{{ row.gapValue }} {{ row.gapUnit }}</template></span>
          <p>{{ row.reason }}</p>
        </template></el-table-column>
        <el-table-column label="要求得分" width="120"><template #default="{ row }">{{ row.earned == null ? '待审，不计分' : `${format(row.earned)} / ${format(row.maximum)}` }}</template></el-table-column>
        <el-table-column label="指标最终分" width="120"><template #default="{ row }">{{ finalScore(row.indicatorId) }}</template></el-table-column>
        <el-table-column label="证据" width="100"><template #default="{ row }"><el-button link type="primary" :disabled="!row.evidenceIds.length" @click="showEvidence(row)">查看原文</el-button></template></el-table-column>
      </el-table>
      <el-empty v-if="!rows.length" description="当前没有要求评分记录" />
    </template>
    <EvidenceDrawer v-model:open="drawerOpen" :ids="evidenceIds" :assessment-id="assessmentId" :project-id="projectId" />
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getRequirementScores } from '@/api/scoring'
import type { RequirementEvaluation, ScoringResults } from '@/api/scoring'
import EvidenceDrawer from './EvidenceDrawer.vue'

const props = defineProps<{ assessmentId: number; projectId: number; analysisRunId: string }>()
const data = ref<ScoringResults | null>(null)
const loading = ref(false), error = ref(''), selected = ref('')
const view = ref<'behavior' | 'indicator' | 'regulation'>('behavior')
const drawerOpen = ref(false), evidenceIds = ref<string[]>([])
const judgments: Record<string, string> = {
  SATISFIED: '满足', VIOLATED: '违规', UNSATISFIED_OPTIONAL: '未满足倡导要求', NOT_APPLICABLE: '不适用',
  INSUFFICIENT_EVIDENCE: '证据不足', NEEDS_REVIEW: '待审', INVALID_INPUT: '事实待核实'
}
const gaps: Record<string, string> = { MISSING_ACTION: '未履行要求', PROHIBITED_ACTION: '发生禁止行为', QUANTITATIVE_SHORTFALL: '低于标准', QUANTITATIVE_EXCESS: '超出上限', DOCUMENTATION_GAP: '材料缺失' }
const key = computed(() => `${view.value}Id` as 'behaviorId' | 'indicatorId' | 'regulationId')
const rows = computed(() => (data.value?.evaluations || []).filter(row => !selected.value || row[key.value] === selected.value))
function source(row: RequirementEvaluation) { return data.value?.sourceBundles[row.indicatorId]?.relations.find(item => item.regulationId === row.regulationId) }
const options = computed(() => {
  const found = new Map<string, string>()
  for (const row of data.value?.evaluations || []) {
    const id = row[key.value]
    const name = view.value === 'behavior' ? data.value?.behaviorDescriptions[id]
      : view.value === 'indicator' ? data.value?.sourceBundles[id]?.indicator.name : source(row)?.regulation.name
    found.set(id, name || '名称不可用')
  }
  return [...found].map(([id, name]) => ({ id, name }))
})
function format(value: number | null) { return value == null ? '—' : Number(value).toFixed(2) }
function finalScore(indicatorId: string) { return format(data.value?.indicatorDecisions.find(row => row.indicatorId === indicatorId)?.calculatedScore ?? null) }
function showEvidence(row: RequirementEvaluation) { evidenceIds.value = row.evidenceIds; drawerOpen.value = true }
let sequence = 0
watch(() => [props.assessmentId, props.projectId, props.analysisRunId], async () => {
  const current = ++sequence
  loading.value = true; error.value = ''; data.value = null; selected.value = ''
  try {
    const response = await getRequirementScores(props.assessmentId, props.projectId, props.analysisRunId)
    if (current === sequence) data.value = response.data
  } catch (failure) {
    if (current === sequence) error.value = failure instanceof Error ? failure.message : '要求评分读取失败'
  } finally { if (current === sequence) loading.value = false }
}, { immediate: true })
</script>

<style scoped>
.requirement-scores { margin-top: 20px; }
.requirement-scores h3 { margin: 0; font-size: 16px; }
.score-toolbar { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.score-selector { width: 320px; max-width: 100%; }
p { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0; }
</style>
