<template>
  <div class="assessment-result-container">
    <!-- 全屏等待遮罩 -->
    <div v-if="isWaiting" class="waiting-overlay">
      <div class="waiting-content">
        <el-icon class="loading-icon"><Loading /></el-icon>
        <h2>正在评估中...</h2>
        <p>系统正在分析您上传的文件，请稍候</p>
        <p class="waiting-hint">评估完成后将自动显示结果</p>
      </div>
    </div>

    <AppHeader />

    <div class="page-title">
      <h1>评估结果报告</h1>
    </div>

    <el-container class="content-wrapper">
      <!-- 左侧导航栏 -->
      <el-aside width="260px" class="sidebar">
        <el-menu
          :default-active="activeView"
          class="sidebar-menu"
          @select="handleMenuSelect"
        >
          <el-menu-item index="overview">
            <el-icon><DataAnalysis /></el-icon>
            <span>总览报告</span>
          </el-menu-item>
          <el-menu-item index="indicator">
            <el-icon><TrendCharts /></el-icon>
            <span>指标分布</span>
          </el-menu-item>
          <el-menu-item index="risk">
            <el-icon><Document /></el-icon>
            <span>风险清单</span>
          </el-menu-item>
          <el-menu-item index="evidence">
            <el-icon><Link /></el-icon>
            <span>证据回溯</span>
          </el-menu-item>
        </el-menu>

        <!-- 维度筛选（仅在风险清单视图显示） -->
        <div v-if="activeView === 'risk'" class="dimension-filter">
          <div class="filter-header">
            <h4>风险维度</h4>
          </div>
          <el-menu
            :default-active="selectedDimension"
            class="dimension-menu"
            @select="handleDimensionSelect"
          >
            <el-menu-item index="">
              <span>全部维度</span>
              <el-badge
                v-if="totalRisks > 0"
                :value="totalRisks"
                class="dimension-badge"
              />
            </el-menu-item>
            <el-menu-item
              v-for="dim in availableDimensions"
              :key="dim"
              :index="dim"
            >
              <span>{{ getDimensionLabel(dim) }}</span>
              <el-badge
                v-if="getDimensionRiskCount(dim) > 0"
                :value="getDimensionRiskCount(dim)"
                class="dimension-badge"
                :type="getDimensionBadgeType(dim)"
              />
            </el-menu-item>
          </el-menu>
        </div>
      </el-aside>

      <!-- 主内容区 -->
      <el-main class="main-content">
        <!-- 总览视图 -->
        <div v-if="activeView === 'overview'" v-loading="loadingOverview">
          <OverviewReport :data="overviewData" />
        </div>

        <!-- 指标分布视图 -->
        <div v-if="activeView === 'indicator'" v-loading="loadingIndicator">
          <IndicatorReport :data="indicatorData" />
        </div>

        <!-- 风险清单视图 -->
        <div v-if="activeView === 'risk'" v-loading="loadingRisks">
          <RiskReport
            :risks="filteredRisks"
            :selected-dimension="selectedDimension"
            :selected-risk-level="selectedRiskLevel"
            @update:risk-level="selectedRiskLevel = $event"
          />
        </div>
        <!-- 证据回溯视图 -->
        <div v-if="activeView === 'evidence'" class="evidence-view">
          <div class="evidence-toolbar">
            <div class="toolbar-line">
              <span class="toolbar-label">分析运行</span>
              <code>{{ behaviorList?.analysisRunId || '暂无成功运行' }}</code>
            </div>
            <el-alert
              v-if="behaviorList && !behaviorList.hasSuccessfulRun"
              type="warning"
              :closable="false"
              show-icon
              title="该评估暂无成功运行"
              description="评估可能仍在进行或已失败，暂无结构化事实可回溯。"
            />
          </div>

          <div v-if="projectId" class="evidence-panels">
            <BehaviorFactList
              class="behavior-panel"
              :behaviors="behaviors"
              :selected-id="selectedBehaviorId"
              :loading="loadingBehaviors"
              @select="handleBehaviorSelect"
            />
            <EvidenceViewer
              class="evidence-panel"
              :behavior="selectedBehavior"
              :evidences="selectedEvidences"
              :missing-ids="missingEvidenceIds"
              :loading="loadingEvidence"
            />
          </div>

          <el-alert
            v-else
            type="warning"
            :closable="false"
            show-icon
            title="缺少项目信息"
            description="证据查询需要项目与评估两个身份，请从项目页进入评估结果。"
          />
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataAnalysis, TrendCharts, Document, Link, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import OverviewReport from '@/components/report/OverviewReport.vue'
import IndicatorReport from '@/components/report/IndicatorReport.vue'
import RiskReport from '@/components/report/RiskReport.vue'
import BehaviorFactList from '@/components/evidence/BehaviorFactList.vue'
import EvidenceViewer from '@/components/evidence/EvidenceViewer.vue'
import type {
  IndicatorDistributionVO,
  RiskVO,
  AssessmentDetailVO
} from '@/types/report'
import type { BehaviorListVO, EvidenceVO, StructuredBehaviorVO } from '@/types/evidence'
import { getIndicatorDistribution, getRiskList, getAssessmentGeneral } from '@/api/report'
import { getBehaviors, getEvidenceByIds } from '@/api/evidence'
import websocketService from '@/utils/websocket'
import type { NotificationMessage } from '@/utils/websocket'
import {
  filterRisks,
  normalizeDimension,
  getDimensionLabel,
  getDimensionBadgeType as getDimensionBadgeTypeUtil
} from '@/utils/riskClassification'

const route = useRoute()
const router = useRouter()

// 等待状态 - 从URL参数获取
const isWaiting = ref(route.query.waiting === 'true')
const waitingProjectId = ref<number | null>(
  route.query.projectId ? Number(route.query.projectId) : null
)
const assessmentId = ref<number>(Number(route.query.assessmentId) || 0)

// 视图状态
const activeView = ref('overview')
const selectedDimension = ref('')
const selectedRiskLevel = ref('')

// 数据状态
const overviewData = ref<AssessmentDetailVO | null>(null)
const indicatorData = ref<IndicatorDistributionVO | null>(null)
const risksData = ref<RiskVO[]>([])

// 加载状态
const loadingOverview = ref(false)
const loadingIndicator = ref(false)
const loadingRisks = ref(false)

// 证据回溯状态
const behaviorList = ref<BehaviorListVO | null>(null)
const selectedBehaviorId = ref<string | null>(null)
const selectedEvidences = ref<EvidenceVO[]>([])
const loadingBehaviors = ref(false)
const loadingEvidence = ref(false)

// 证据查询需要项目与评估两个身份：优先取 URL，其次用总览返回的项目号补全
const projectId = computed(() => {
  const fromQuery = Number(route.query.projectId)
  if (Number.isFinite(fromQuery) && fromQuery > 0) {
    return fromQuery
  }
  return overviewData.value?.projectId ?? null
})

const behaviors = computed(() => behaviorList.value?.behaviors ?? [])

const selectedBehavior = computed(
  () => behaviors.value.find(item => item.id === selectedBehaviorId.value) ?? null
)

// 事实引用了但当前评估查不到的证据 ID；页面据此提示"引用缺失"，而不是静默少显示几条
const missingEvidenceIds = computed(() => {
  const behavior = selectedBehavior.value
  if (!behavior) {
    return []
  }
  const found = new Set(selectedEvidences.value.map(item => item.evidenceId))
  return behavior.evidenceIds.filter(id => !found.has(id))
})

// 可用维度列表
const availableDimensions = computed(() => {
  const dimensions = new Set<string>()
  risksData.value.forEach(risk => {
    const dim = normalizeDimension(risk.dimension)
    if (dim) dimensions.add(dim)
  })
  return Array.from(dimensions).sort()
})

// 总风险数
const totalRisks = computed(() => risksData.value.length)

// 筛选后的风险列表
const filteredRisks = computed(() => {
  return filterRisks(risksData.value, selectedDimension.value, selectedRiskLevel.value)
})

// 获取维度风险数量
const getDimensionRiskCount = (dimension: string): number => {
  const dim = normalizeDimension(dimension)
  return risksData.value.filter(risk => normalizeDimension(risk.dimension) === dim).length
}

// 获取维度徽章类型
const getDimensionBadgeType = (dimension: string): 'danger' | 'warning' | 'success' | 'info' => {
  return getDimensionBadgeTypeUtil(risksData.value, dimension)
}

// 菜单选择处理
const handleMenuSelect = (index: string) => {
  activeView.value = index

  // 切换视图时加载对应数据
  if (index === 'overview' && !overviewData.value) {
    loadOverviewData()
  } else if (index === 'indicator' && !indicatorData.value) {
    loadIndicatorData()
  } else if (index === 'risk' && risksData.value.length === 0) {
    loadRisksData()
  } else if (index === 'evidence' && !behaviorList.value) {
    loadBehaviors()
  }
}

// 维度选择处理
const handleDimensionSelect = (dimension: string) => {
  selectedDimension.value = dimension
}

// 加载总览数据
const loadOverviewData = async () => {
  try {
    loadingOverview.value = true
    const response = await getAssessmentGeneral(assessmentId.value)
    if (response.code === 200) {
      overviewData.value = response.data
    } else {
      ElMessage.error(response.message || '加载总览数据失败')
    }
  } catch (error: any) {
    console.error('Failed to load overview data:', error)
    ElMessage.error(error.message || '加载总览数据失败')
  } finally {
    loadingOverview.value = false
  }
}

// 加载指标分布数据
const loadIndicatorData = async () => {
  try {
    loadingIndicator.value = true
    const response = await getIndicatorDistribution(assessmentId.value)
    if (response.code === 200) {
      indicatorData.value = response.data
    } else {
      ElMessage.error(response.message || '加载指标数据失败')
    }
  } catch (error: any) {
    console.error('Failed to load indicator data:', error)
    ElMessage.error(error.message || '加载指标数据失败')
  } finally {
    loadingIndicator.value = false
  }
}

// 加载风险数据
const loadRisksData = async () => {
  try {
    loadingRisks.value = true
    const response = await getRiskList(assessmentId.value)
    if (response.code === 200) {
      risksData.value = response.data
    } else {
      ElMessage.error(response.message || '加载风险数据失败')
    }
  } catch (error: any) {
    console.error('Failed to load risks data:', error)
    ElMessage.error(error.message || '加载风险数据失败')
  } finally {
    loadingRisks.value = false
  }
}

// 加载结构化事实；默认选中第一条，使右侧原文区立即有内容
const loadBehaviors = async () => {
  const currentProjectId = projectId.value
  if (!currentProjectId) {
    return
  }
  try {
    loadingBehaviors.value = true
    const response = await getBehaviors({
      projectId: currentProjectId,
      assessmentId: assessmentId.value
    })
    if (response.code === 200) {
      behaviorList.value = response.data
      const first = response.data.behaviors[0]
      selectedBehaviorId.value = first ? first.id : null
      if (first) {
        await loadEvidenceFor(first)
      } else {
        selectedEvidences.value = []
      }
    } else {
      ElMessage.error(response.message || '加载结构化事实失败')
    }
  } catch (error: any) {
    console.error('Failed to load behaviors:', error)
    ElMessage.error(error.message || '加载结构化事实失败')
  } finally {
    loadingBehaviors.value = false
  }
}

// 按行为引用的证据 ID 取原文；后端越权校验失败会走 catch，不静默降级
const loadEvidenceFor = async (behavior: StructuredBehaviorVO) => {
  const currentProjectId = projectId.value
  if (!currentProjectId || behavior.evidenceIds.length === 0) {
    selectedEvidences.value = []
    return
  }
  try {
    loadingEvidence.value = true
    const response = await getEvidenceByIds({
      ids: behavior.evidenceIds,
      projectId: currentProjectId,
      assessmentId: assessmentId.value
    })
    selectedEvidences.value = response.code === 200 ? response.data : []
  } catch (error: any) {
    console.error('Failed to load evidence:', error)
    selectedEvidences.value = []
    ElMessage.error(error.message || '加载证据原文失败')
  } finally {
    loadingEvidence.value = false
  }
}

const handleBehaviorSelect = async (id: string) => {
  selectedBehaviorId.value = id
  selectedEvidences.value = []
  const behavior = behaviors.value.find(item => item.id === id)
  if (behavior) {
    await loadEvidenceFor(behavior)
  }
}

// WebSocket消息处理器
const handleNotificationMessage = (message: NotificationMessage) => {
  const { notificationType, assessmentId: msgAssessmentId, projectId: msgProjectId } = message

  // 检查是否是评估完成的消息
  if (
    (notificationType === 'ASSESSMENT_COMPLETED' ||
     notificationType === 'ASSESSMENT_COMPLETE' ||
     notificationType === 'PROCESSING_COMPLETE') &&
    msgAssessmentId
  ) {
    // 如果当前是等待状态，且projectId匹配（或没有projectId限制）
    if (isWaiting.value) {
      // 如果有projectId限制，检查是否匹配
      if (waitingProjectId.value && msgProjectId && msgProjectId !== waitingProjectId.value) {
        return // projectId不匹配，忽略此消息
      }

      // 更新assessmentId
      assessmentId.value = msgAssessmentId

      // 更新URL参数（不刷新页面）
      router.replace({
        path: '/assessment-result',
        query: { assessmentId: msgAssessmentId.toString() }
      })

      // 关闭等待状态
      isWaiting.value = false

      ElMessage.success('评估完成，正在加载结果...')

      // 加载数据
      loadOverviewData()
    }
  }
}

// 初始化
onMounted(() => {
  // 如果是等待模式，监听WebSocket消息
  if (isWaiting.value) {
    websocketService.on('notification', handleNotificationMessage)
  } else if (assessmentId.value) {
    // 非等待模式且有assessmentId，直接加载数据
    loadOverviewData()
  }
})

// 清理
onUnmounted(() => {
  // 移除WebSocket监听器
  websocketService.off('notification', handleNotificationMessage)
})
</script>

<style scoped>
.assessment-result-container {
  min-height: 100vh;
  background-color: #f0f2f5;
}

/* 全屏等待遮罩 */
.waiting-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.waiting-content {
  text-align: center;
  color: #fff;
}

.waiting-content .loading-icon {
  font-size: 64px;
  color: #409eff;
  animation: rotate 1.5s linear infinite;
  margin-bottom: 24px;
}

@keyframes rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.waiting-content h2 {
  font-size: 28px;
  font-weight: 600;
  margin: 0 0 16px 0;
}

.waiting-content p {
  font-size: 16px;
  margin: 8px 0;
  color: rgba(255, 255, 255, 0.85);
}

.waiting-content .waiting-hint {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.6);
  margin-top: 24px;
}

.page-title {
  max-width: 1600px;
  margin: 0 auto;
  padding: 20px 20px 0;
}

.page-title h1 {
  margin: 0;
  padding: 20px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
  font-size: 22px;
  font-weight: 600;
  color: #1f2f3d;
}

.content-wrapper {
  max-width: 1600px;
  margin: 0 auto;
  padding: 20px;
  gap: 20px;
}

/* 侧边栏样式 */
.sidebar {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.sidebar-menu {
  border-right: none;
}

.sidebar-menu .el-menu-item {
  height: 56px;
  line-height: 56px;
  font-size: 14px;
  border-left: 3px solid transparent;
  transition: all 0.3s;
}

.sidebar-menu .el-menu-item:hover {
  background-color: #ecf5ff;
}

.sidebar-menu .el-menu-item.is-active {
  background-color: #ecf5ff;
  border-left-color: #409eff;
  color: #409eff;
}

.sidebar-menu .el-menu-item .el-icon {
  margin-right: 8px;
  font-size: 18px;
}

/* 维度筛选 */
.dimension-filter {
  margin-top: 20px;
  border-top: 1px solid #ebeef5;
}

.filter-header {
  padding: 16px 20px;
  background: linear-gradient(135deg, #409eff 0%, #337ecc 100%);
}

.filter-header h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
}

.dimension-menu {
  border-right: none;
}

.dimension-menu .el-menu-item {
  height: 48px;
  line-height: 48px;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 15px;
}

.dimension-badge {
  margin-left: auto;
}

:deep(.dimension-badge .el-badge__content) {
  font-size: 11px;
  padding: 0 6px;
  height: 18px;
  line-height: 18px;
}

/* 主内容区 */
.main-content {
  background: transparent;
  padding: 0;
}

/* 证据回溯 */
.evidence-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.evidence-toolbar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 20px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
}

.toolbar-line {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.toolbar-label {
  color: #909399;
}

.toolbar-line code {
  color: #303133;
  word-break: break-all;
}

.evidence-panels {
  display: grid;
  grid-template-columns: minmax(340px, 1fr) minmax(420px, 1.5fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1200px) {
  .evidence-panels {
    grid-template-columns: 1fr;
  }
}
</style>
