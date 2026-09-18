<template>
  <el-empty v-if="groups.length === 0" description="暂无行为分析记录" />
  <el-card v-for="group in groups" :key="group.behaviorId" class="group">
    <h2>{{ group.behaviorDescription || '行为描述不可用' }}</h2>
<p>{{ group.behaviorId }} · {{ group.behaviorType || '类型未提供' }} · {{ group.behaviorDimension || '维度未提供' }}</p>
    <p v-if="group.conclusions.length === 0">暂无决策结果</p>
    <article v-for="c in group.conclusions" :key="c.resultId" class="conclusion"><h3>{{ c.indicatorName || c.indicatorId }} <el-tag v-if="c.mock" type="warning">模拟结论</el-tag>
</h3>
      <p>适用性：{{ label(c.applicable) }} · 合规状态：{{ label(c.complianceStatus) }}</p>
      <p>要求：{{ c.requirement || '未提供' }}</p>
<p>企业事实：{{ c.enterpriseFact || '未提供' }}</p>
      <p>差距：{{ label(c.gapType) }} {{ c.gapValue ?? '' }} {{ c.gapUnit || '' }}</p>
<p>置信度：{{ c.confidence === null ? '未提供' : `${(c.confidence * 100).toFixed(0)}%` }}</p>
      <p>法规：{{ c.regulationIds?.map((id, i) => c.regulationNames?.[i] || id).join('、') || '未引用法规' }}</p>
      <el-button @click="$emit('evidence', c.evidenceIds || [])">查看证据（{{ c.evidenceIds?.length || 0 }}）</el-button>
      <el-collapse><el-collapse-item title="推理与版本"><p>{{ c.reasoning }}</p>
<p>模型 {{ c.modelVersion }} · 提示词 {{ c.promptVersion }} · 分析时间 {{ c.analyzedAt }}</p>
</el-collapse-item>
</el-collapse>
    </article>
<RetrievalAuditPanel :audit="group.retrievalAudit" />
  </el-card>
</template>
<script setup lang="ts">
import RetrievalAuditPanel from './RetrievalAuditPanel.vue';
import { label } from '@/types/analysis';
import type { BehaviorAnalysisGroupVO } from '@/types/analysis';
defineProps<{
    groups: BehaviorAnalysisGroupVO[];
}>();
defineEmits<{
    evidence: [
        ids: string[]
    ];
}>();
</script>
<style scoped>.group { margin-top:18px } h2 { font-size:18px } .conclusion { border:1px solid #ebeef5;
border-radius:6px;
padding:16px;
margin:12px 0 } p { white-space:pre-wrap;
overflow-wrap:anywhere;
color:#606266 }</style>
