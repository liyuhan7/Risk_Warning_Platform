<template>
  <div class="overview-report">
    <el-row :gutter="20">
      <!-- 总体评估结果卡片 -->
      <el-col :span="24">
        <el-card class="result-card" shadow="hover">
          <template #header>
            <h3>总体评估结果</h3>
          </template>
          <div class="overall-result">
            <div class="risk-level-section">
              <div class="risk-label">风险等级</div>
              <el-tag
                :type="getRiskLevelTagType(data?.overallResult?.overallRiskLevel)"
                class="risk-tag"
                size="large"
                effect="dark"
              >
                {{ getRiskLevelLabel(data?.overallResult?.overallRiskLevel) }}
              </el-tag>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <!-- 风险汇总 -->
      <el-col :span="12">
        <el-card class="summary-card" shadow="hover">
          <template #header>
            <h3>风险汇总</h3>
          </template>
          <div class="summary-content">
            <div class="summary-item">
              <span class="label">风险总数</span>
              <span class="value">{{ data?.riskSummary?.totalRisks || 0 }}</span>
            </div>
            <div class="summary-item high-risk">
              <span class="label">高风险</span>
              <span class="value">{{ data?.riskSummary?.highRiskCount || 0 }}</span>
            </div>
            <div class="summary-item medium-risk">
              <span class="label">中风险</span>
              <span class="value">{{ data?.riskSummary?.mediumRiskCount || 0 }}</span>
            </div>
            <div class="summary-item low-risk">
              <span class="label">低风险</span>
              <span class="value">{{ data?.riskSummary?.lowRiskCount || 0 }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 指标概览 -->
      <el-col :span="12">
        <el-card class="summary-card" shadow="hover">
          <template #header>
            <h3>指标概览</h3>
          </template>
          <div class="summary-content">
            <div class="summary-item">
              <span class="label">行为指标</span>
              <span class="value">{{ data?.indicatorOverview?.behaviorIndicators || 0 }}</span>
            </div>
            <div class="summary-item">
              <span class="label">问卷指标</span>
              <span class="value">{{ data?.indicatorOverview?.questionnaireIndicators || 0 }}</span>
            </div>
            <div class="summary-item risk-triggered">
              <span class="label">触发风险</span>
              <span class="value">{{ data?.indicatorOverview?.riskTriggeredIndicators || 0 }}</span>
            </div>
            <div class="summary-item safe">
              <span class="label">安全指标</span>
              <span class="value">{{ data?.indicatorOverview?.safeIndicators || 0 }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 维度风险分布 -->
    <el-row style="margin-top: 20px;">
      <el-col :span="24">
        <el-card class="dimension-card" shadow="hover">
          <template #header>
            <h3>维度风险分布</h3>
          </template>
          <el-table
            :data="dimensionTableData"
            border
            style="width: 100%"
          >
            <el-table-column prop="dimension" label="风险维度" min-width="200" />
            <el-table-column prop="riskCount" label="风险总数" width="120" align="center" />
            <el-table-column prop="highRiskCount" label="高风险" width="120" align="center">
              <template #default="{ row }">
                <span class="high-risk-text">{{ row.highRiskCount }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="mediumRiskCount" label="中风险" width="120" align="center">
              <template #default="{ row }">
                <span class="medium-risk-text">{{ row.mediumRiskCount }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="lowRiskCount" label="低风险" width="120" align="center">
              <template #default="{ row }">
                <span class="low-risk-text">{{ row.lowRiskCount }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AssessmentDetailVO } from '@/types/report'

interface Props {
  data: AssessmentDetailVO | null
}

const props = defineProps<Props>()

// 维度表格数据
const dimensionTableData = computed(() => {
  if (!props.data?.dimensionRiskDistribution) return []

  return Object.entries(props.data.dimensionRiskDistribution).map(([key, value]) => ({
    dimension: value.dimension || key,
    riskCount: value.riskCount || 0,
    highRiskCount: value.highRiskCount || 0,
    mediumRiskCount: value.mediumRiskCount || 0,
    lowRiskCount: value.lowRiskCount || 0
  }))
})

// 风险等级标签类型
const getRiskLevelTagType = (level?: string) => {
  const typeMap: Record<string, any> = {
    'HIGH_RISK': 'danger',
    'MEDIUM_RISK': 'warning',
    'LOW_RISK': 'success'
  }
  return typeMap[level || ''] || 'info'
}

// 风险等级标签文本
const getRiskLevelLabel = (level?: string) => {
  const labelMap: Record<string, string> = {
    'HIGH_RISK': '高风险',
    'MEDIUM_RISK': '中风险',
    'LOW_RISK': '低风险'
  }
  return labelMap[level || ''] || '未评估'
}
</script>

<style scoped>
.overview-report {
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

.result-card,
.summary-card,
.dimension-card {
  border-radius: 8px;
  border: none;
}

.overall-result {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 20px 0;
}

.risk-level-section {
  text-align: center;
}

.risk-label {
  font-size: 18px;
  color: #909399;
  margin-bottom: 16px;
}

.risk-tag {
  font-size: 24px;
  padding: 15px 30px;
  font-weight: bold;
}

.summary-content {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.summary-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 6px;
  border-left: 4px solid #909399;
}

.summary-item.high-risk {
  border-left-color: #f56c6c;
}

.summary-item.medium-risk {
  border-left-color: #e6a23c;
}

.summary-item.low-risk {
  border-left-color: #67c23a;
}

.summary-item.risk-triggered {
  border-left-color: #f56c6c;
}

.summary-item.safe {
  border-left-color: #67c23a;
}

.summary-item .label {
  font-size: 14px;
  color: #606266;
}

.summary-item .value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}

.high-risk-text {
  color: #f56c6c;
  font-weight: bold;
}

.medium-risk-text {
  color: #e6a23c;
  font-weight: bold;
}

.low-risk-text {
  color: #67c23a;
  font-weight: bold;
}
</style>

