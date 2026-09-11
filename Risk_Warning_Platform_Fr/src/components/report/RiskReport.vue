<template>
  <div class="risk-report">
    <el-card class="report-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <h3>
            {{ selectedDimension ? getDimensionLabel(selectedDimension) : '全部维度' }} - 风险清单
          </h3>
          <div class="header-actions">
            <el-select
              :model-value="selectedRiskLevel"
              @update:model-value="$emit('update:riskLevel', $event)"
              placeholder="风险等级筛选"
              style="width: 150px; margin-right: 10px;"
              clearable
            >
              <el-option label="全部等级" value="" />
              <el-option label="高风险" value="HIGH_RISK" />
              <el-option label="中风险" value="MEDIUM_RISK" />
              <el-option label="低风险" value="LOW_RISK" />
            </el-select>
            <el-button type="primary" @click="handleExport">导出报告</el-button>
          </div>
        </div>
      </template>

      <!-- 风险统计卡片 -->
      <div class="risk-stats">
        <div class="stat-card total">
          <div class="stat-label">风险总数</div>
          <div class="stat-value">{{ risks.length }}</div>
        </div>
        <div class="stat-card high">
          <div class="stat-label">高风险</div>
          <div class="stat-value">{{ highRiskCount }}</div>
        </div>
        <div class="stat-card medium">
          <div class="stat-label">中风险</div>
          <div class="stat-value">{{ mediumRiskCount }}</div>
        </div>
        <div class="stat-card low">
          <div class="stat-label">低风险</div>
          <div class="stat-value">{{ lowRiskCount }}</div>
        </div>
      </div>

      <!-- 风险列表 -->
      <div class="risk-list" v-if="risks.length > 0">
        <div class="risk-table-header">
          <span>风险等级</span>
          <span>风险点及触发依据</span>
          <span>风险类别</span>
          <span>处理状态</span>
          <span>预警时间</span>
        </div>
        <el-collapse accordion>
          <el-collapse-item
            v-for="(risk, index) in risks"
            :key="risk.id"
            :name="index"
          >
            <template #title>
              <div class="risk-title">
                <el-tag
                  :type="getRiskLevelType(risk.riskLevel)"
                  effect="dark"
                  class="risk-tag"
                >
                  {{ getRiskLevelLabel(risk.riskLevel) }}
                </el-tag>
                <span class="risk-name">{{ risk.name || '-' }}</span>
                <span class="risk-dimension">{{ getDimensionLabel(risk.dimension) }}</span>
                <span class="risk-status">
                  <el-tag size="small" :type="getProcessingStatusType(risk.processingStatus)">
                    {{ risk.processingStatus || '-' }}
                  </el-tag>
                </span>
                <span class="risk-time">{{ formatDate(risk.createAt) }}</span>
              </div>
            </template>

            <div class="risk-detail">
              <!-- 基本信息 -->
              <el-descriptions :column="2" border>
                <el-descriptions-item label="风险描述" :span="2">
                  {{ risk.description || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="风险维度">
                  {{ getDimensionLabel(risk.dimension) }}
                </el-descriptions-item>
                <el-descriptions-item label="风险等级">
                  <el-tag :type="getRiskLevelType(risk.riskLevel)" effect="dark">
                    {{ getRiskLevelLabel(risk.riskLevel) }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="发生概率">
                  <el-progress
                    :percentage="risk.probability * 100"
                    :color="getProgressColor(risk.probability)"
                  />
                </el-descriptions-item>
                <el-descriptions-item label="影响程度">
                  <el-progress
                    :percentage="risk.impact * 100"
                    :color="getProgressColor(risk.impact)"
                  />
                </el-descriptions-item>
                <el-descriptions-item label="可检测性">
                  <el-progress
                    :percentage="risk.detectability * 100"
                    :color="getProgressColor(risk.detectability)"
                  />
                </el-descriptions-item>
                <el-descriptions-item label="当前状态">
                  <el-tag :type="getStatusType(risk.status)">
                    {{ getStatusLabel(risk.status) }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="责任方">
                  {{ risk.responsibleParty || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="影响范围" :span="2">
                  {{ risk.impactScope || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="受影响对象" :span="2">
                  <el-tag
                    v-for="obj in risk.affectedObjects"
                    :key="obj"
                    size="small"
                    style="margin-right: 8px;"
                  >
                    {{ obj }}
                  </el-tag>
                  <span v-if="!risk.affectedObjects || risk.affectedObjects.length === 0">-</span>
                </el-descriptions-item>
                <el-descriptions-item label="应对措施" :span="2">
                  {{ risk.countermeasures || '-' }}
                </el-descriptions-item>
              </el-descriptions>

              <!-- 相关指标 -->
              <div class="related-section" v-if="risk.relatedIndicators && risk.relatedIndicators.length > 0">
                <h4>相关指标</h4>
                <el-table :data="risk.relatedIndicators" border size="small">
                  <el-table-column prop="indicatorName" label="指标名称" min-width="200" />
                  <el-table-column prop="score" label="得分" width="100" align="center">
                    <template #default="{ row }">
                      <span :class="getScoreClass(row.score, row.threshold)">
                        {{ row.score }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="threshold" label="阈值" width="100" align="center" />
                  <el-table-column label="主要触发" width="100" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.isPrimaryTrigger" type="danger" size="small">是</el-tag>
                      <el-tag v-else type="info" size="small">否</el-tag>
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <!-- 相关法规 -->
              <div class="related-section" v-if="risk.relatedRegulations && risk.relatedRegulations.length > 0">
                <h4>相关法规</h4>
                <el-table :data="risk.relatedRegulations" border size="small">
                  <el-table-column prop="regulationName" label="法规名称" min-width="200" />
                  <el-table-column prop="violationType" label="违规类型" width="120" />
                  <el-table-column prop="complianceRequirement" label="合规要求" min-width="250" />
                </el-table>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 空状态 -->
      <el-empty
        v-else
        description="暂无风险数据"
        :image-size="120"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { RiskVO } from '@/types/report'
import {
  normalizeRiskLevel,
  getDimensionLabel,
  getRiskLevelLabel as getRiskLevelLabelUtil,
  getRiskLevelTagType as getRiskLevelTagTypeUtil
} from '@/utils/riskClassification'

interface Props {
  risks: RiskVO[]
  selectedDimension?: string
  selectedRiskLevel?: string
}

const props = defineProps<Props>()
defineEmits<{
  'update:riskLevel': [value: string]
}>()

// 风险统计
const highRiskCount = computed(() =>
  props.risks.filter(r => normalizeRiskLevel(r.riskLevel) === 'HIGH_RISK').length
)

const mediumRiskCount = computed(() =>
  props.risks.filter(r => normalizeRiskLevel(r.riskLevel) === 'MEDIUM_RISK').length
)

const lowRiskCount = computed(() =>
  props.risks.filter(r => normalizeRiskLevel(r.riskLevel) === 'LOW_RISK').length
)

// 风险等级标签类型
const getRiskLevelType = (level: unknown) => {
  return getRiskLevelTagTypeUtil(level)
}

// 风险等级标签文本
const getRiskLevelLabel = (level: unknown) => {
  return getRiskLevelLabelUtil(level)
}

// 状态标签类型
const getStatusType = (status: string) => {
  const typeMap: Record<string, any> = {
    'IDENTIFIED': 'info',
    'IN_PROGRESS': 'warning',
    'RESOLVED': 'success',
    'MONITORING': 'primary'
  }
  return typeMap[status] || 'info'
}

// 状态标签文本
const getStatusLabel = (status: string) => {
  const labelMap: Record<string, string> = {
    'IDENTIFIED': '已识别',
    'IN_PROGRESS': '处理中',
    'RESOLVED': '已解决',
    'MONITORING': '监控中'
  }
  return labelMap[status] || status
}

// 处理状态标签类型
const getProcessingStatusType = (status?: string) => {
  const typeMap: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
    '成功': 'success',
    '处理中': 'warning',
    '失败': 'danger'
  }
  return status ? (typeMap[status] || 'info') : 'info'
}

// 时间格式化
const formatDate = (dateStr?: string) => {
  if (!dateStr) return '-'

  const date = new Date(dateStr)
  if (Number.isNaN(date.getTime())) return dateStr

  return date.toLocaleString('zh-CN')
}

// 进度条颜色
const getProgressColor = (value: number) => {
  if (value >= 0.8) return '#f56c6c'
  if (value >= 0.5) return '#e6a23c'
  return '#67c23a'
}

// 分数样式
const getScoreClass = (score: number, threshold: number) => {
  if (score < threshold) return 'score-low'
  if (score < threshold * 1.2) return 'score-medium'
  return 'score-high'
}

// 导出报告
const handleExport = () => {
  ElMessage.info('导出功能开发中...')
}
</script>

<style scoped>
.risk-report {
  padding: 0;
}

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

.header-actions {
  display: flex;
  align-items: center;
}

.report-card {
  border-radius: 8px;
  border: none;
}

.risk-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  text-align: center;
  padding: 20px;
  border-radius: 8px;
  border-left: 4px solid;
}

.stat-card.total {
  background: linear-gradient(135deg, #e3f2fd 0%, #bbdefb 100%);
  border-left-color: #2196f3;
}

.stat-card.high {
  background: linear-gradient(135deg, #ffebee 0%, #ffcdd2 100%);
  border-left-color: #f44336;
}

.stat-card.medium {
  background: linear-gradient(135deg, #fff3e0 0%, #ffe0b2 100%);
  border-left-color: #ff9800;
}

.stat-card.low {
  background: linear-gradient(135deg, #e8f5e9 0%, #c8e6c9 100%);
  border-left-color: #4caf50;
}

.stat-label {
  font-size: 14px;
  color: #606266;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #303133;
  font-family: 'Arial', sans-serif;
}

.risk-list {
  margin-top: 20px;
}

.risk-table-header {
  display: grid;
  grid-template-columns: 120px minmax(0, 2.5fr) minmax(140px, 1fr) 120px 160px;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 12px;
  border-radius: 8px;
  background: #f5f7fa;
  color: #606266;
  font-size: 13px;
  font-weight: 600;
}

.risk-title {
  display: grid;
  grid-template-columns: 120px minmax(0, 2.5fr) minmax(140px, 1fr) 120px 160px;
  gap: 12px;
  align-items: center;
  width: 100%;
}

.risk-tag {
  min-width: 70px;
  text-align: center;
  font-weight: bold;
}

.risk-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.risk-dimension,
.risk-time {
  font-size: 13px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.risk-status {
  display: flex;
  align-items: center;
}

.risk-detail {
  padding: 16px;
  background: #fafafa;
}

.related-section {
  margin-top: 20px;
}

.related-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.score-low {
  color: #f56c6c;
  font-weight: bold;
}

.score-medium {
  color: #e6a23c;
  font-weight: bold;
}

.score-high {
  color: #67c23a;
  font-weight: bold;
}
</style>
