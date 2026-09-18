<template>
  <el-drawer :model-value="open" title="证据原文" size="55%" @update:model-value="$emit('update:open', $event)">
    <div v-loading="loading"><el-alert v-if="error" title="证据详情不可用" :description="error" type="error" :closable="false" />
      <el-empty v-else-if="!loading && ids.length === 0" description="该结论未引用证据" />
      <el-alert v-if="!loading && !error && missing.length" :title="`证据引用缺失：${missing.join('、')}`" type="warning" :closable="false" />
      <el-card v-for="e in evidences" :key="e.evidenceId" class="evidence"><h3>{{ e.sourceFileName }}</h3>
<p>{{ e.pageNumber === null ? '页码未知' : `第 ${e.pageNumber} 页` }}</p>
<p class="original">{{ e.text }}</p>
<p>哈希 {{ e.textHash }}</p>
</el-card>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { getEvidenceByIds } from '@/api/evidence';
import type { EvidenceVO } from '@/types/evidence';
const props = defineProps<{
    open: boolean;
    ids: string[];
    assessmentId: number;
    projectId: number;
}>();
defineEmits<{
    'update:open': [
        value: boolean
    ];
}>();
const evidences = ref<EvidenceVO[]>([]), loading = ref(false), error = ref('');
const missing = computed(() => props.ids.filter(id => !evidences.value.some(e => e.evidenceId === id)));
let sequence = 0;
watch(() => [props.open, props.ids, props.assessmentId, props.projectId], async () => {
    const current = ++sequence;
    evidences.value = [];
    error.value = '';
    loading.value = false;
    if (!props.open || !props.ids.length)
        return;
    loading.value = true;
    try {
        const response = await getEvidenceByIds({ ids: props.ids, assessmentId: props.assessmentId, projectId: props.projectId });
        if (current === sequence)
            evidences.value = response.data;
    }
    catch (e) {
        if (current === sequence)
            error.value = e instanceof Error ? e.message : '证据请求失败';
    }
    finally {
        if (current === sequence)
            loading.value = false;
    }
});
</script>
<style scoped>.evidence { margin-top:14px } p { overflow-wrap:anywhere } .original { white-space:pre-wrap }</style>
