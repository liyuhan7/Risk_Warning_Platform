<template>
  <div v-loading="loading">
    <template v-if="data && !error">
      <AnalysisStatusCard :status="data.displayStatus" :run="data.run"
        :decision-count="data.summary.decisionCount" @refresh="$emit('refresh')" />
      <template v-if="data.displayStatus !== 'NOT_STARTED'">
        <p>
          行为 {{ data.summary.behaviorCount }} · 结论 {{ data.summary.conclusionCount }} · 决策 {{ data.summary.decisionCount }}
          · 检索成功 {{ data.summary.retrievalSuccessCount }} · 无候选 {{ data.summary.noCandidateCount }}
          · 检索失败 {{ data.summary.retrievalFailedCount }}
        </p>
        <p>
          尚未尝试 {{ data.summary.notAttemptedCount }} · 非演示输入 {{ data.summary.notDemoInputCount }}
          · 召回缺口 {{ data.summary.recallGapCount }} · 证据不足 {{ data.summary.insufficientEvidenceCount }}
        </p>
        <BehaviorAnalysisList :groups="data.behaviorGroups" @evidence="showEvidence" />
      </template>
      <el-alert v-if="pollLimitReached" title="自动刷新已达 20 次，请手动刷新。" type="info" :closable="false" />
    </template>
    <EvidenceDrawer v-model:open="drawerOpen" :ids="evidenceIds"
      :assessment-id="assessmentId" :project-id="projectId" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import AnalysisStatusCard from './AnalysisStatusCard.vue';
import BehaviorAnalysisList from './BehaviorAnalysisList.vue';
import EvidenceDrawer from './EvidenceDrawer.vue';
import type { AnalysisOverviewVO } from '@/types/analysis';

/**
 * P2 分析链的展示区，只消费 overview 聚合结果。
 * 取数与轮询由页面持有，本组件不读写旧报告接口，避免两种链路互相解释数据。
 */
defineProps<{
  data: AnalysisOverviewVO | null;
  loading: boolean;
  error: string;
  pollLimitReached: boolean;
  assessmentId: number;
  projectId: number;
}>();
defineEmits<{
  refresh: [];
}>();

const drawerOpen = ref(false), evidenceIds = ref<string[]>([]);
function showEvidence(ids: string[]) {
  evidenceIds.value = ids;
  drawerOpen.value = true;
}
</script>

<style scoped>p { color:#606266; overflow-wrap:anywhere }</style>
