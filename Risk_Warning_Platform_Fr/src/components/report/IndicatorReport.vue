<template>
  <div class="indicator-report">
    <el-card class="report-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <h3>指标分布统计</h3>
          <div class="header-stats">
            <el-tag type="info">总计: {{ data?.totalCount || 0 }}</el-tag>
            <el-tag type="danger" style="margin-left: 10px;">触发风险: {{ data?.riskTriggeredCount || 0 }}</el-tag>
            <el-tag type="success" style="margin-left: 10px;">安全: {{ data?.safeCount || 0 }}</el-tag>
          </div>
        </div>
      </template>

      <!-- 总体统计 -->
      <div class="overall-stats">
        <div class="stat-item">
          <div class="stat-label">总分</div>
          <div class="stat-value">{{ data?.totalScore?.toFixed(2) || 0 }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">指标总数</div>
          <div class="stat-value">{{ data?.totalCount || 0 }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">触发风险数</div>
          <div class="stat-value risk-triggered">{{ data?.riskTriggeredCount || 0 }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">安全指标数</div>
          <div class="stat-value safe">{{ data?.safeCount || 0 }}</div>
        </div>
      </div>

      <!-- 分数分布 -->
      <div class="distribution-section">
        <h4>分数区间分布</h4>
        <el-table
          :data="data?.scoreDistributions || []"
          border
          style="width: 100%; margin-top: 16px;"
        >
          <el-table-column label="分数区间" width="150" align="center">
            <template #default="{ row }">
              {{ (row.startScoreRatio * 100).toFixed(0) }}% - {{ (row.endScoreRatio * 100).toFixed(0) }}%
            </template>
          </el-table-column>
          <el-table-column prop="totalCount" label="指标数量" width="120" align="center" />
          <el-table-column prop="totalScore" label="总分" width="120" align="center">
            <template #default="{ row }">
              {{ row.totalScore.toFixed(2) }}
            </template>
          </el-table-column>
          <el-table-column prop="riskTriggeredCount" label="触发风险" width="120" align="center">
            <template #default="{ row }">
              <span class="risk-count">{{ row.riskTriggeredCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="safeCount" label="安全指标" width="120" align="center">
            <template #default="{ row }">
              <span class="safe-count">{{ row.safeCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="占比" align="center">
            <template #default="{ row }">
              {{ calculateRatio(row.totalCount) }}%
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 维度分布 -->
      <div class="dimension-section" v-if="dimensionData.length > 0">
        <h4>各维度指标分布</h4>
        <el-collapse accordion style="margin-top: 16px;">
          <el-collapse-item
            v-for="(dim, index) in dimensionData"
            :key="index"
            :name="index"
          >
            <template #title>
              <div class="dimension-title">
                <span class="dimension-name">{{ dim.name }}</span>
                <div class="dimension-badges">
                  <el-badge :value="dim.data.totalCount" type="info" />
                  <el-badge
                    v-if="dim.data.riskTriggeredCount > 0"
                    :value="dim.data.riskTriggeredCount"
                    type="danger"
                    style="margin-left: 10px;"
                  />
                </div>
              </div>
            </template>
            <div class="dimension-content">
              <div class="dimension-stats">
                <div class="stat-box">
                  <div class="stat-label">总分</div>
                  <div class="stat-value">{{ dim.data.totalScore.toFixed(2) }}</div>
                </div>
                <div class="stat-box">
                  <div class="stat-label">指标数</div>
                  <div class="stat-value">{{ dim.data.totalCount }}</div>
                </div>
                <div class="stat-box">
                  <div class="stat-label">触发风险</div>
                  <div class="stat-value risk-triggered">{{ dim.data.riskTriggeredCount }}</div>
                </div>
                <div class="stat-box">
                  <div class="stat-label">安全</div>
                  <div class="stat-value safe">{{ dim.data.safeCount }}</div>
                </div>
              </div>
              <el-table
                :data="dim.data.scoreDistributions"
                border
                size="small"
                style="margin-top: 16px;"
              >
                <el-table-column label="分数区间" width="120" align="center">
                  <template #default="{ row }">
                    {{ (row.startScoreRatio * 100).toFixed(0) }}%-{{ (row.endScoreRatio * 100).toFixed(0) }}%
                  </template>
                </el-table-column>
                <el-table-column prop="totalCount" label="数量" width="80" align="center" />
                <el-table-column prop="riskTriggeredCount" label="风险" width="80" align="center">
                  <template #default="{ row }">
                    <span class="risk-count">{{ row.riskTriggeredCount }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="safeCount" label="安全" width="80" align="center">
                  <template #default="{ row }">
                    <span class="safe-count">{{ row.safeCount }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { IndicatorDistributionVO } from '@/types/report'

interface Props {
  data: IndicatorDistributionVO | null
}

const props = defineProps<Props>()

// 维度数据
const dimensionData = computed(() => {
  if (!props.data?.dimensionDistributions) return []

  return Object.entries(props.data.dimensionDistributions).map(([key, value]) => ({
    name: key,
    data: value
  }))
})

// 计算占比
const calculateRatio = (count: number): string => {
  if (!props.data?.totalCount) return '0.00'
  return ((count / props.data.totalCount) * 100).toFixed(2)
}
</script>

<style scoped>
.indicator-report {
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

.header-stats {
  display: flex;
  align-items: center;
}

.report-card {
  border-radius: 8px;
  border: none;
}

.overall-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-item {
  text-align: center;
  padding: 20px;
  background: linear-gradient(135deg, #f5f7fa 0%, #ecf0f3 100%);
  border-radius: 8px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #409eff;
  font-family: 'Arial', sans-serif;
}

.stat-value.risk-triggered {
  color: #f56c6c;
}

.stat-value.safe {
  color: #67c23a;
}

.distribution-section,
.dimension-section {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #ebeef5;
}

.distribution-section h4,
.dimension-section h4 {
  margin: 0 0 16px 0;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.risk-count {
  color: #f56c6c;
  font-weight: bold;
}

.safe-count {
  color: #67c23a;
  font-weight: bold;
}

.dimension-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding-right: 20px;
}

.dimension-name {
  font-weight: 500;
  color: #303133;
}

.dimension-badges {
  display: flex;
  align-items: center;
}

.dimension-content {
  padding: 16px;
  background: #fafafa;
}

.dimension-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.stat-box {
  text-align: center;
  padding: 12px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}

.stat-box .stat-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 6px;
}

.stat-box .stat-value {
  font-size: 20px;
  font-weight: bold;
  color: #409eff;
}

.stat-box .stat-value.risk-triggered {
  color: #f56c6c;
}

.stat-box .stat-value.safe {
  color: #67c23a;
}
</style>

