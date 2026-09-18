<template>
  <el-card><div class="status"><el-icon :color="config.color" :size="24"><component :is="config.icon" /></el-icon>
<el-tag :type="config.type">{{ config.title }}</el-tag>
</div>
    <p>{{ config.description }}</p>
<p v-if="run">运行 {{ run.analysisRunId }} · 开始 {{ run.startedAt }} · 结束 {{ run.finishedAt || '尚未结束' }}</p>
    <el-button @click="$emit('refresh')">刷新分析</el-button>
  </el-card>
</template>
<script setup lang="ts">
import { computed } from 'vue';
import { InfoFilled, Loading, CircleCheckFilled, WarningFilled, Search, CircleCloseFilled } from '@element-plus/icons-vue';
import type { DisplayStatus, AnalysisRunSummaryVO } from '@/types/analysis';
const props = defineProps<{
    status: DisplayStatus;
    run: AnalysisRunSummaryVO | null;
    decisionCount?: number;
}>();
defineEmits<{
    refresh: [
    ];
}>();
const states = {
    NOT_STARTED: { title: '尚未运行分析', description: '请从项目页上传材料并发起评估。', type: 'info', color: '#909399', icon: InfoFilled },
    RUNNING: { title: '分析进行中', description: '正在分析材料，每 3 秒刷新运行状态。', type: 'primary', color: '#409eff', icon: Loading },
    COMPLETED_WITH_DECISION: { title: '分析完成，已形成决策', description: '请按行为查看结论与证据引用。', type: 'success', color: '#67c23a', icon: CircleCheckFilled },
    COMPLETED_WITHOUT_DECISION: { title: '分析完成，暂无决策结果', description: '部分行为未形成决策，请检查证据不足、召回缺口或非演示输入记录。', type: 'warning', color: '#e6a23c', icon: WarningFilled },
    NO_CANDIDATES: { title: '未检索到候选', description: '当前检索未获得候选，不能据此判断无风险。', type: 'warning', color: '#a68a64', icon: Search },
    FAILED: { title: '分析失败', description: '本次运行失败，请查看审计错误并从项目页重新发起分析。', type: 'danger', color: '#f56c6c', icon: CircleCloseFilled }
} as const;
/**
 * COMPLETED_WITHOUT_DECISION 的语义是「至少一个行为未形成决策」，未形成决策的行为之外仍可正常产出结论。
 * 已决策数量大于零时改用部分决策文案，避免状态卡与下方结论列表互相矛盾。
 */
const config = computed(() => {
    const base = states[props.status];
    if (props.status === 'COMPLETED_WITHOUT_DECISION' && (props.decisionCount ?? 0) > 0) {
        return { ...base, title: '分析完成，部分行为未形成决策',
            description: '已形成决策的行为可查看结论与证据引用；其余行为未形成决策，请检查证据不足、召回缺口或非演示输入记录。' };
    }
    return base;
});
</script>
<style scoped>.status { display:flex; align-items:center; gap:12px } p { color:#606266; overflow-wrap:anywhere }</style>
