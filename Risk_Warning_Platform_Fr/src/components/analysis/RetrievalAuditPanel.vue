<template>
  <section class="audit"><h3>检索与审计</h3>
    <el-alert v-if="!audit" title="检索审计不可用" type="info" :closable="false" />
    <template v-else><p>查询：{{ audit.queryText || '未提供' }}</p>
<p>检索 {{ label(audit.retrievalStatus) }} · 分析 {{ label(audit.analysisStatus) }} · 候选 {{ audit.candidateTotal }}</p>
      <el-alert v-if="audit.retrievalStatus === 'FAILED'" :title="`检索失败：${audit.errorCode || ''} ${audit.errorType || ''} ${audit.errorMessage || '未提供错误详情'}`" type="error" :closable="false" />
      <el-collapse><el-collapse-item title="模板版本与过滤条件"><p>查询模板 {{ audit.queryTemplateVersion }} · 过滤版本 {{ audit.filterVersion }}</p>
<p>启用 {{ audit.filterEnabled }} · 应用 {{ audit.filterApplied }}</p>
<p>{{ audit.filterExpression }}</p>
</el-collapse-item>
</el-collapse>
      <el-alert v-if="!audit.snapshotAvailable" title="候选快照不可用" type="warning" :closable="false" />
      <el-empty v-else-if="audit.retrievalStatus === 'NO_CANDIDATES'" description="未检索到候选" :image-size="45" />
      <p v-else-if="audit.candidates.length === 0">当前审计没有候选记录</p>
      <p v-if="audit.candidateTruncated">候选超过 50 条，仅展示前 50 条（共 {{ audit.candidateTotal }} 条）。</p>
      <el-collapse><el-collapse-item v-for="(c, index) in audit.candidates" :key="`${c.candidateType}-${c.candidateId}-${index}`" :title="`${c.rank ?? '-'} · ${label(c.candidateType)} · ${c.name || c.candidateId || '快照缺失'} · ${c.score ?? '-'} (${c.scoreType || '未知评分类型'})`">
        <p v-if="!c.snapshotAvailable">候选快照不可用</p>
<p v-else class="original">{{ c.content }}</p>
<p>原文哈希 {{ c.contentHash || c.retrievalTextHash || '未提供' }}</p>
      </el-collapse-item>
</el-collapse>
    </template>
  </section>
</template>
<script setup lang="ts">
import { label } from '@/types/analysis';
import type { RetrievalAuditVO } from '@/types/analysis';
defineProps<{
    audit: RetrievalAuditVO | null;
}>();
</script>
<style scoped>.audit { margin-top:20px;
overflow-wrap:anywhere } .original { white-space:pre-wrap;
max-height:360px;
overflow:auto } h3 { font-size:15px }</style>
