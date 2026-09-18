<template>
  <div v-loading="loading">
    <template v-if="data && !error">
      <!-- 面向客户的状态提示：不展示运行 ID 与时间戳等内部信息 -->
      <el-alert v-if="data.displayStatus === 'RUNNING'" class="status-alert"
        title="正在分析材料，请稍候" description="系统正在检索相关指标与法规，完成后将自动刷新。" type="info" :closable="false" show-icon />
      <el-alert v-else-if="data.displayStatus === 'FAILED'" class="status-alert"
        title="本次评估未能完成" description="请稍后重新发起分析；已生成的结果不受影响。" type="error" :closable="false" show-icon />
      <el-alert v-else-if="data.displayStatus === 'COMPLETED_WITHOUT_DECISION'" class="status-alert"
        title="分析完成，部分行为尚未形成结论" description="已完成的行为可正常查看；未形成结论的行为请查看列表中的状态说明。" type="warning" :closable="false" show-icon />
      <el-alert v-else-if="data.displayStatus === 'COMPLETED_WITH_DECISION' || data.displayStatus === 'NO_CANDIDATES'" class="status-alert"
        title="分析已完成" description="可按事实逐条查看关联的指标与法规依据。" type="success" :closable="false" show-icon />

      <template v-if="data.displayStatus !== 'NOT_STARTED'">
        <el-row :gutter="20" class="summary-row">
          <el-col :xs="24" :sm="12">
            <el-card shadow="hover" class="summary-card">
              <template #header><h3>事实与检索</h3></template>
              <div class="summary-content">
                <div class="summary-item"><span class="label">结构化行为</span><span class="value">{{ data.summary.behaviorCount }}</span></div>
                <div class="summary-item blue"><span class="label">检索成功</span><span class="value">{{ data.summary.retrievalSuccessCount }}</span></div>
                <div class="summary-item warning"><span class="label">召回缺口</span><span class="value">{{ data.summary.recallGapCount }}</span></div>
                <div class="summary-item warning"><span class="label">证据不足</span><span class="value">{{ data.summary.insufficientEvidenceCount }}</span></div>
                <div class="summary-item warning"><span class="label">无候选</span><span class="value">{{ data.summary.noCandidateCount }}</span></div>
                <div class="summary-item danger"><span class="label">检索失败</span><span class="value">{{ data.summary.retrievalFailedCount }}</span></div>
              </div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-card shadow="hover" class="summary-card">
              <template #header><h3>决策与待定</h3></template>
              <div class="summary-content">
                <div class="summary-item blue"><span class="label">已形成决策</span><span class="value">{{ data.summary.decisionCount }}</span></div>
                <div class="summary-item"><span class="label">结论记录</span><span class="value">{{ data.summary.conclusionCount }}</span></div>
                <div class="summary-item"><span class="label">尚未尝试</span><span class="value">{{ data.summary.notAttemptedCount }}</span></div>
                <div class="summary-item warning"><span class="label">非演示输入</span><span class="value">{{ data.summary.notDemoInputCount }}</span></div>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <el-alert v-if="data.truncated" class="stage-alert" title="行为数量超出展示上限，仅显示部分行为" type="info" :closable="false" />

        <!-- 关系聚焦工作台：左侧事实导航，右侧事实详情 + 指标/法规 + 证据 -->
        <div class="workbench">
          <aside class="fact-rail">
            <div class="fact-rail-card">
              <div class="rail-header"><h3>事实清单</h3><span class="rail-count">共 {{ filteredGroups.length }} 条</span></div>              <div class="rail-filter">
                <button v-for="filter in filterList" :key="filter.key" class="filter-item"
                  :class="{ active: activeFilter === filter.key }" @click="setFilter(filter.key)">
                  <span>{{ filter.label }}</span><b>{{ filter.count }}</b>
                </button>
              </div>
              <el-empty v-if="filteredGroups.length === 0" description="当前筛选没有行为记录" :image-size="60" />
              <el-menu v-else :default-active="selectedId" class="fact-menu" @select="selectBehavior">
                <el-menu-item v-for="group in filteredGroups" :key="group.behaviorId" :index="group.behaviorId">
                  <span class="rail-status" :class="groupState(group)"></span>
                  <span class="fact-menu-text"><strong>{{ group.behaviorDescription || '行为描述不可用' }}</strong><small>{{ behaviorSubline(group) }}</small></span>
                </el-menu-item>
              </el-menu>
            </div>
          </aside>

          <section class="relationship-stage">
            <el-row :gutter="20">
              <el-col :span="24">
                <el-card shadow="hover" class="stage-card">
                  <template #header>
                    <div class="stage-header"><h3>事实详情</h3>
                      <el-tag :type="stateTagType(selectedState)" effect="dark">{{ stateText(selectedState) }}</el-tag>
                    </div>
                  </template>
                  <el-alert v-if="selectedState === 'failed'" class="stage-alert"
                    :title="selectedGroup?.retrievalAudit?.errorCode ? `检索/分析失败：${selectedGroup.retrievalAudit.errorCode}` : '检索或分析失败'"
                    :description="selectedGroup?.retrievalAudit?.errorMessage || '本次行为检索未成功完成，不生成后续结论。'"
                    type="error" :closable="false" show-icon />
                  <el-alert v-else-if="selectedState === 'recall_gap'" class="stage-alert" title="召回缺口"
                    description="已抽取事实，但当前检索没有找到足够可信的指标/法规候选，暂不生成后续结论。"
                    type="warning" :closable="false" show-icon />
                  <el-alert v-else-if="selectedState === 'no_candidates'" class="stage-alert" title="未检索到候选"
                    description="不能据此判断无风险，请检查知识库覆盖或补充材料。"
                    type="warning" :closable="false" show-icon />
                  <el-alert v-else-if="selectedState === 'insufficient'" class="stage-alert" title="证据不足"
                    description="当前行为缺少足够的证据支撑，请补充材料后再进行关联判断。"
                    type="warning" :closable="false" show-icon />
                  <el-alert v-else-if="selectedState === 'pending'" class="stage-alert" title="等待后续分析"
                    :description="`检索状态：${label(selectedGroup?.retrievalAudit?.retrievalStatus)} · 分析状态：${label(selectedGroup?.retrievalAudit?.analysisStatus)}`"
                    type="info" :closable="false" show-icon />
                  <div class="summary-content">
                    <div class="summary-item full"><span class="label">行为描述</span><span class="value-text">{{ selectedGroup?.behaviorDescription || '行为描述不可用' }}</span></div>
                    <div class="summary-item"><span class="label">行为类型</span><span class="value-text">{{ selectedGroup?.behaviorType || '未提供' }}</span></div>
                    <div class="summary-item"><span class="label">风险维度</span><span class="value-text">{{ selectedGroup?.behaviorDimension || '未提供' }}</span></div>
                    <div class="summary-item"><span class="label">关联结论</span><span class="value">{{ conclusions.length }}</span></div>
                    <div class="summary-item" :class="evidenceIds.length === 0 ? 'danger' : 'success'"><span class="label">引用证据</span><span class="value">{{ evidenceIds.length }}</span></div>
                  </div>
                </el-card>
              </el-col>
            </el-row>

            <el-row :gutter="20" class="stage-row">
              <el-col :xs="24" :sm="12">
                <el-card shadow="hover" class="stage-card">
                  <template #header><div class="stage-header"><h3>关联指标 Indicator</h3><el-tag size="small" type="info">{{ relationIndicatorCount }} 条</el-tag></div></template>
                  <template v-if="conclusions.length > 0">
                    <div v-for="c in conclusions" :key="c.resultId" class="summary-item candidate indicator">
                      <div class="candidate-main">
                        <strong>{{ c.indicatorName || c.indicatorId || '指标未提供' }} <el-tag v-if="c.mock" type="warning" size="small">模拟结论</el-tag></strong>
                        <small>合规状态：{{ label(c.complianceStatus) }} · 适用性：{{ label(c.applicable) }}</small>
                        <small v-if="c.requirement">要求：{{ c.requirement }}</small>
                      </div>
                      <span class="candidate-score">置信度 {{ c.confidence === null ? '未提供' : `${(c.confidence * 100).toFixed(0)}%` }}</span>
                    </div>
                  </template>
                  <template v-else-if="indicatorCandidates.length > 0">
                    <div v-for="(c, index) in indicatorCandidates" :key="`${c.candidateId}-${index}`" class="summary-item candidate indicator">
                      <div class="candidate-main">
                        <strong>{{ c.name || '候选快照缺失' }}</strong>
                        <small v-if="!c.snapshotAvailable">候选快照不可用</small>
                      </div>
                      <span class="candidate-score">匹配度 {{ c.score ?? '未提供' }}</span>
                    </div>
                    <p class="candidate-note">以上为检索候选，尚未形成结论。</p>
                  </template>
                  <div v-else class="empty-hint">{{ indicatorEmptyText }}</div>
                </el-card>
              </el-col>
              <el-col :xs="24" :sm="12">
                <el-card shadow="hover" class="stage-card">
                  <template #header><div class="stage-header"><h3>关联法规 Regulation</h3><el-tag size="small" type="info">{{ regulationCount }} 条</el-tag></div></template>
                  <template v-if="regulationRefs.length > 0">
                    <div v-for="ref in regulationRefs" :key="ref.id" class="summary-item candidate regulation">
                      <div class="candidate-main"><strong>{{ ref.name || ref.id }}</strong></div>
                    </div>
                  </template>
                  <template v-else-if="regulationCandidates.length > 0">
                    <div v-for="(c, index) in regulationCandidates" :key="`${c.candidateId}-${index}`" class="summary-item candidate regulation">
                      <div class="candidate-main">
                        <strong>{{ c.name || '候选快照缺失' }}</strong>
                        <small v-if="!c.snapshotAvailable">候选快照不可用</small>
                      </div>
                      <span class="candidate-score">匹配度 {{ c.score ?? '未提供' }}</span>
                    </div>
                    <p class="candidate-note">以上为检索候选，尚未形成结论。</p>
                  </template>
                  <div v-else class="empty-hint">{{ regulationEmptyText }}</div>
                </el-card>
              </el-col>
            </el-row>

            <el-row class="stage-row">
              <el-col :span="24">
                <el-card shadow="hover" class="stage-card">
                  <template #header><div class="stage-header"><h3>证据与后续动作</h3>
                    <el-button type="primary" size="small" :disabled="evidenceIds.length === 0" @click="showEvidence(evidenceIds)">打开证据回溯（{{ evidenceIds.length }}）</el-button>
                  </div></template>
                  <el-empty v-if="evidenceIds.length === 0" description="该行为暂无可定位的证据引用" :image-size="60" />
                  <el-alert v-else class="stage-alert" :title="`该行为引用了 ${evidenceIds.length} 条证据，点击右上角打开原文定位。`" type="success" :closable="false" show-icon />
                  <el-alert v-if="selectedGroup?.retrievalAudit && !selectedGroup.retrievalAudit.snapshotAvailable" class="stage-alert" title="候选快照不可用" type="warning" :closable="false" show-icon />
                  <!-- 检索依据按需展开：排名、分数、版本与查询文本不作为主视图 -->
                  <el-collapse class="audit-collapse">
                    <el-collapse-item title="检索依据（排名、分数、版本）">
                      <RetrievalAuditPanel :audit="selectedGroup?.retrievalAudit ?? null" />
                    </el-collapse-item>
                  </el-collapse>
                </el-card>
              </el-col>
            </el-row>
          </section>
        </div>
      </template>
      <el-alert v-if="pollLimitReached" title="自动刷新已达 20 次，请手动刷新。" type="info" :closable="false" />
    </template>
    <EvidenceDrawer v-model:open="drawerOpen" :ids="evidenceIds"
      :assessment-id="assessmentId" :project-id="projectId" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import RetrievalAuditPanel from './RetrievalAuditPanel.vue';
import EvidenceDrawer from './EvidenceDrawer.vue';
import { label } from '@/types/analysis';
import type { AnalysisOverviewVO, BehaviorAnalysisGroupVO } from '@/types/analysis';

/**
 * P2 分析链的展示区，只消费 overview 聚合结果。
 * 关系聚焦工作台：事实导航 + 事实详情 + 指标/法规并排 + 证据回溯；检索审计默认折叠。
 * 组件不计算分数、风险等级或候选排序，状态仅按后端返回的检索/分析状态归类。
 */
const props = defineProps<{
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

const route = useRoute();
const router = useRouter();

// ===== 行为状态归类：只翻译后端状态，不重新计算业务结论 =====
type WorkbenchState = 'linked' | 'recall_gap' | 'no_candidates' | 'insufficient' | 'failed' | 'pending';
function groupState(group: BehaviorAnalysisGroupVO): WorkbenchState {
    if (group.conclusions.length > 0) return 'linked';
    const audit = group.retrievalAudit;
    const retrievalStatus = audit?.retrievalStatus ?? null;
    const analysisStatus = audit?.analysisStatus ?? null;
    if (retrievalStatus === 'FAILED' || analysisStatus === 'FAILED') return 'failed';
    if (analysisStatus === 'RECALL_GAP') return 'recall_gap';
    if (analysisStatus === 'INSUFFICIENT_EVIDENCE') return 'insufficient';
    if (retrievalStatus === 'NO_CANDIDATES') return 'no_candidates';
    return 'pending';
}
const stateTextMap: Record<WorkbenchState, string> = {
    linked: '已关联', recall_gap: '召回缺口', no_candidates: '无候选',
    insufficient: '证据不足', failed: '失败', pending: '等待后续分析'
};
const stateTagTypeMap: Record<WorkbenchState, 'success' | 'warning' | 'danger' | 'info'> = {
    linked: 'success', recall_gap: 'warning', no_candidates: 'warning',
    insufficient: 'warning', failed: 'danger', pending: 'info'
};
const stateText = (state: WorkbenchState) => stateTextMap[state];
const stateTagType = (state: WorkbenchState) => stateTagTypeMap[state];

// ===== 筛选与选中：URL 保留 behaviorId 与筛选状态，刷新后恢复 =====
const filters = [
    { key: 'all', label: '全部' },
    { key: 'linked', label: '已关联' },
    { key: 'recall_gap', label: '召回缺口' },
    { key: 'no_candidates', label: '无候选' },
    { key: 'insufficient', label: '证据不足' },
    { key: 'failed', label: '失败' },
    { key: 'pending', label: '等待后续分析' }
] as const;
const activeFilter = ref<string>(String(route.query.behaviorFilter ?? 'all'));
const selectedId = ref('');

const groups = computed(() => props.data?.behaviorGroups ?? []);
const filteredGroups = computed(() => {
    if (activeFilter.value === 'all') return groups.value;
    return groups.value.filter(group => groupState(group) === activeFilter.value);
});
const filterCount = (key: string) => key === 'all'
    ? groups.value.length
    : groups.value.filter(group => groupState(group) === key).length;
const filterList = computed(() => filters.map(filter => ({ ...filter, count: filterCount(filter.key) })));

const selectedGroup = computed(() =>
    groups.value.find(group => group.behaviorId === selectedId.value) ?? filteredGroups.value[0] ?? null);
const selectedState = computed(() => selectedGroup.value ? groupState(selectedGroup.value) : 'pending');

function behaviorSubline(group: BehaviorAnalysisGroupVO) {
    return `${group.behaviorType || '类型未提供'} · ${group.behaviorDimension || '维度未提供'}`;
}

function syncQuery() {
    router.replace({
        query: {
            ...route.query,
            behaviorId: selectedId.value,
            behaviorFilter: activeFilter.value
        }
    });
}

function selectBehavior(id: string) {
    selectedId.value = id;
    syncQuery();
}

function setFilter(key: string) {
    activeFilter.value = key;
    syncQuery();
}

// 数据刷新后校准选中项：优先保留 URL 中的 behaviorId，失效则回落到当前筛选的第一条
watch(() => props.data, () => {
    const urlId = String(route.query.behaviorId ?? '');
    if (groups.value.some(group => group.behaviorId === urlId)) {
        selectedId.value = urlId;
        return;
    }
    selectedId.value = filteredGroups.value[0]?.behaviorId ?? '';
    if (selectedId.value) syncQuery();
}, { immediate: true });

// ===== 关联结果：结论优先，结论缺失时展示真实检索候选，不做前端排序与评分 =====
const conclusions = computed(() => selectedGroup.value?.conclusions ?? []);
const indicatorCandidates = computed(() =>
    selectedGroup.value?.retrievalAudit?.candidates.filter(c => c.candidateType === 'INDICATOR') ?? []);
const regulationCandidates = computed(() =>
    selectedGroup.value?.retrievalAudit?.candidates.filter(c => c.candidateType === 'REGULATION') ?? []);
const relationIndicatorCount = computed(() =>
    conclusions.value.length > 0 ? conclusions.value.length : indicatorCandidates.value.length);
const regulationCount = computed(() =>
    regulationRefs.value.length > 0 ? regulationRefs.value.length : regulationCandidates.value.length);
const regulationRefs = computed(() => {
    const refs: { id: string; name: string | null }[] = [];
    conclusions.value.forEach(conclusion => {
        (conclusion.regulationIds ?? []).forEach((id, index) => {
            if (refs.some(ref => ref.id === id)) return;
            refs.push({ id, name: conclusion.regulationNames?.[index] ?? null });
        });
    });
    return refs;
});

const indicatorEmptyText = computed(() => {
    const audit = selectedGroup.value?.retrievalAudit;
    if (!audit) return '检索审计不可用，未产生候选记录。';
    if (audit.retrievalStatus === 'FAILED') return '检索失败，未产生候选。';
    if (audit.retrievalStatus === 'NO_CANDIDATES') return '未检索到候选，不能据此判断无风险。';
    return '尚未召回候选，当前没有足够可信的关联依据。';
});
const regulationEmptyText = computed(() => {
    if (indicatorCandidates.value.length > 0) return '指标已找到，但法规依据缺失，不生成后续结论。';
    return '尚未召回候选，当前没有足够可信的关联依据。';
});

// ===== 证据：取该行为全部结论引用的证据 ID 去重后交给抽屉回读 =====
const evidenceIds = computed(() =>
    Array.from(new Set(conclusions.value.flatMap(conclusion => conclusion.evidenceIds ?? []))));
const drawerOpen = ref(false);
function showEvidence(ids: string[]) {
    drawerOpen.value = true;
    void ids;
}
</script>

<style scoped>
.summary-row { margin-top: 16px }
.summary-card { border-radius: 8px; border: none }
.summary-card :deep(h3) { margin: 0; font-size: 16px; font-weight: 600; color: #303133 }
.summary-row { margin-bottom: 20px }

/* 工作台布局：左侧事实导航 + 右侧关联结果，窄屏退化为单列 */
.workbench { display: grid; grid-template-columns: 260px minmax(0, 1fr); gap: 20px; align-items: start }
.fact-rail-card { background: #fff; border-radius: 8px; box-shadow: 0 2px 12px 0 rgba(0, 0, 0, .05); overflow: hidden }
.rail-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; background: linear-gradient(135deg, #409eff 0%, #337ecc 100%) }
.rail-header h3 { margin: 0; color: #fff; font-size: 16px; font-weight: 600 }
.rail-count { color: rgba(255, 255, 255, .85); font-size: 12px }
.rail-filter { padding: 16px 16px 8px }
.filter-item { display: flex; justify-content: space-between; align-items: center; width: 100%; padding: 10px 12px; margin-bottom: 8px; border: 0; border-radius: 6px; background: #f5f7fa; color: #606266; font-size: 13px; text-align: left; cursor: pointer; transition: all .3s }
.filter-item:hover { background: #ecf5ff }
.filter-item.active { background: #ecf5ff; color: #409eff; font-weight: 600 }
.filter-item b { color: #909399; font-size: 12px }
.filter-item.active b { color: #409eff }
.fact-menu { border-right: none; max-height: 520px; overflow-y: auto }
.fact-menu :deep(.el-menu-item) { height: 56px; line-height: normal; padding: 0 18px; border-left: 3px solid transparent; transition: all .3s }
.fact-menu :deep(.el-menu-item.is-active) { background: #ecf5ff; border-left-color: #409eff; color: #409eff }
.fact-menu :deep(.el-menu-item:hover) { background: #ecf5ff }
.fact-menu-text { display: flex; flex-direction: column; gap: 4px; margin-left: 10px; overflow: hidden }
.fact-menu-text strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #303133; font-size: 14px; font-weight: 500 }
.fact-menu-text small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #909399; font-size: 12px }
.rail-status { display: inline-block; width: 8px; height: 8px; margin-top: 5px; flex: none; border-radius: 50%; background: #909399 }
.rail-status.linked { background: #67c23a }
.rail-status.recall_gap, .rail-status.no_candidates, .rail-status.insufficient { background: #e6a23c }
.rail-status.failed { background: #f56c6c }

.relationship-stage { min-width: 0 }
.stage-card { border-radius: 8px; border: none }
.stage-header { display: flex; justify-content: space-between; align-items: center }
.stage-header h3 { margin: 0; font-size: 16px; font-weight: 600; color: #303133 }
.stage-alert { margin-bottom: 16px }
.summary-content { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px }
.summary-item { display: flex; justify-content: space-between; align-items: center; padding: 16px; background: #f5f7fa; border-radius: 6px; border-left: 4px solid #909399 }
.summary-item.full { grid-column: 1 / -1 }
.summary-item .label { font-size: 14px; color: #606266 }
.summary-item .value { font-size: 24px; font-weight: bold; color: #303133 }
.summary-item .value-text { font-size: 14px; font-weight: 600; color: #303133; text-align: right; overflow-wrap: anywhere }
.summary-item.blue { border-left-color: #409eff }
.summary-item.success { border-left-color: #67c23a }
.summary-item.warning { border-left-color: #e6a23c }
.summary-item.danger { border-left-color: #f56c6c }
.summary-item.candidate { align-items: flex-start }
.candidate.indicator { border-left-color: #409eff }
.candidate.regulation { border-left-color: #67c23a }
.candidate-main { display: flex; flex-direction: column; gap: 5px; min-width: 0 }
.candidate-main strong { color: #303133; font-size: 14px }
.candidate-main small { color: #909399; font-size: 12px; line-height: 1.5; overflow-wrap: anywhere }
.candidate-score { flex: none; color: #909399; font-size: 12px }
.candidate-note { margin: 10px 0 0; color: #909399; font-size: 12px }
.empty-hint { padding: 32px 0; text-align: center; color: #909399; font-size: 13px }
.stage-row { margin-top: 20px }
.audit-collapse { margin-top: 4px }

@media (max-width: 1200px) {
    .workbench { grid-template-columns: 1fr }
}
@media (max-width: 700px) {
    .summary-content { grid-template-columns: 1fr }
}
</style>
