<template>
  <div class="questionnaire-container">
    <AppHeader />
    <div class="page-title">
      <h1>风险评估问卷</h1>
      <p class="subtitle">请根据企业实际情况填写以下问卷，作为行为信息文件上传的补充</p>
    </div>

    <div class="content-wrapper">
      <!-- 左侧进度信息 -->
      <div class="progress-sidebar">
        <div class="sidebar-header">
          <h3>填写进度</h3>
        </div>
        <div class="progress-info">
          <el-progress
            type="circle"
            :percentage="completionPercentage"
            :color="progressColor"
            :width="120"
          />
          <div class="progress-detail">
            <span class="progress-text">已完成 {{ answeredCount }}/{{ totalQuestions }} 题</span>
            <el-tag :type="completionPercentage === 100 ? 'success' : 'info'" size="small">
              {{ completionPercentage === 100 ? '已完成' : '填写中' }}
            </el-tag>
          </div>
        </div>
        <div class="quick-nav">
          <h4>快速导航</h4>
          <el-scrollbar height="300px">
            <div
              v-for="(question, index) in mockQuestionnaire"
              :key="index"
              class="nav-item"
              :class="{ answered: isQuestionAnswered(index) }"
              @click="scrollToQuestion(index)"
            >
              <span class="nav-number">{{ index + 1 }}</span>
              <span class="nav-text">{{ question.question_text.slice(0, 15) }}...</span>
              <el-icon v-if="isQuestionAnswered(index)" class="nav-icon" color="#67c23a">
                <CircleCheck />
              </el-icon>
            </div>
          </el-scrollbar>
        </div>
      </div>

      <!-- 右侧问卷区域 -->
      <div class="main-content">
        <el-card class="questionnaire-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span class="card-title">企业风险评估问卷</span>
              <span class="question-count">共 {{ totalQuestions }} 题</span>
            </div>
          </template>

          <el-form
            ref="formRef"
            :model="formData"
            label-position="top"
            class="questionnaire-form"
          >
            <div
              v-for="(question, index) in mockQuestionnaire"
              :key="index"
              :ref="el => setQuestionRef(el, index)"
              class="question-item"
            >
              <div class="question-header">
                <span class="question-number">{{ index + 1 }}</span>
                <span class="question-text">{{ question.question_text }}</span>
              </div>

              <div v-if="question.help_text" class="help-text">
                <el-icon><InfoFilled /></el-icon>
                {{ question.help_text }}
              </div>

              <!-- 单选题 - radio -->
              <el-radio-group
                v-if="question.input_type === 'radio'"
                v-model="formData[index]"
                class="radio-group"
              >
                <el-radio
                  v-for="option in question.options"
                  :key="option.value"
                  :value="option.value"
                  class="radio-option"
                >
                  {{ option.label }}
                </el-radio>
              </el-radio-group>

              <!-- 单选题 - select -->
              <el-select
                v-else-if="question.input_type === 'select'"
                v-model="formData[index]"
                :placeholder="question.placeholder || '请选择'"
                class="select-input"
              >
                <el-option
                  v-for="option in question.options"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>

              <!-- 多选题 -->
              <el-checkbox-group
                v-else-if="question.input_type === 'checkbox'"
                v-model="formData[index]"
                class="checkbox-group"
              >
                <el-checkbox
                  v-for="option in question.options"
                  :key="option.value"
                  :value="option.value"
                  class="checkbox-option"
                >
                  {{ option.label }}
                </el-checkbox>
              </el-checkbox-group>

              <!-- 数字输入 -->
              <el-input-number
                v-else-if="question.input_type === 'number'"
                v-model="formData[index]"
                :min="question.validation_rules?.min_value ?? 0"
                :max="question.validation_rules?.max_value ?? 9999"
                :placeholder="question.placeholder"
                class="number-input"
                controls-position="right"
              />

              <!-- 文本输入 -->
              <el-input
                v-else-if="question.input_type === 'input'"
                v-model="formData[index]"
                :placeholder="question.placeholder || '请输入'"
                :maxlength="question.validation_rules?.max_length"
                show-word-limit
                class="text-input"
              />

              <!-- 多行文本 -->
              <el-input
                v-else-if="question.input_type === 'textarea'"
                v-model="formData[index]"
                type="textarea"
                :rows="4"
                :placeholder="question.placeholder || '请输入'"
                :maxlength="question.validation_rules?.max_length"
                show-word-limit
                class="textarea-input"
              />
            </div>
          </el-form>
        </el-card>

        <!-- 底部操作按钮 -->
        <div class="action-bar">
          <el-button @click="handleSaveDraft">
            <el-icon><DocumentCopy /></el-icon>
            保存草稿
          </el-button>
          <el-button type="primary" @click="handleSubmit" :loading="submitting">
            <el-icon><Upload /></el-icon>
            提交问卷
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  InfoFilled,
  CircleCheck,
  DocumentCopy,
  Upload
} from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import { mockQuestionnaire } from '@/mock/questionnaireData'

const router = useRouter()

// 表单数据 - 使用索引作为key
const formData = ref<Record<number, any>>({})
const submitting = ref(false)

// 问题元素引用
const questionRefs = ref<Record<number, HTMLElement | null>>({})

// 总问题数
const totalQuestions = computed(() => mockQuestionnaire.length)

// 判断问题是否已回答
const isQuestionAnswered = (index: number): boolean => {
  const value = formData.value[index]
  if (Array.isArray(value)) return value.length > 0
  return value !== undefined && value !== null && value !== ''
}

// 已回答问题数
const answeredCount = computed(() => {
  let count = 0
  for (let i = 0; i < mockQuestionnaire.length; i++) {
    if (isQuestionAnswered(i)) count++
  }
  return count
})

// 完成百分比
const completionPercentage = computed(() => {
  return Math.round((answeredCount.value / totalQuestions.value) * 100)
})

// 进度条颜色
const progressColor = computed(() => {
  if (completionPercentage.value < 30) return '#f56c6c'
  if (completionPercentage.value < 70) return '#e6a23c'
  return '#67c23a'
})

// 设置问题元素引用
const setQuestionRef = (el: any, index: number) => {
  if (el) {
    questionRefs.value[index] = el
  }
}

// 滚动到指定问题
const scrollToQuestion = (index: number) => {
  const el = questionRefs.value[index]
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

/**
 * 保存草稿
 */
const handleSaveDraft = () => {
  localStorage.setItem('questionnaire_draft', JSON.stringify(formData.value))
  ElMessage.success('草稿已保存')
}

/**
 * 提交问卷
 */
const handleSubmit = async () => {
  try {
    await ElMessageBox.confirm(
      '确定要提交问卷吗？提交后将进行风险评估并生成报告。',
      '确认提交',
      {
        confirmButtonText: '确认提交',
        cancelButtonText: '取消',
        type: 'info'
      }
    )

    submitting.value = true

    // 模拟提交延迟
    await new Promise(resolve => setTimeout(resolve, 1500))

    // 清除草稿
    localStorage.removeItem('questionnaire_draft')

    ElMessage.success('问卷提交成功，正在跳转到评估结果页面...')

    // 跳转到评估结果页面
    setTimeout(() => {
      router.push('/assessment-result')
    }, 1000)
  } catch {
    // 用户取消
  } finally {
    submitting.value = false
  }
}

/**
 * 初始化表单数据
 */
const initFormData = () => {
  // 初始化所有问题的默认值
  mockQuestionnaire.forEach((q, index) => {
    if (q.input_type === 'checkbox') {
      formData.value[index] = []
    } else if (q.input_type === 'number') {
      formData.value[index] = undefined
    } else {
      // 查找默认选项
      const defaultOption = q.options?.find(opt => opt.is_default)
      formData.value[index] = defaultOption?.value ?? ''
    }
  })

  // 尝试恢复草稿
  const draft = localStorage.getItem('questionnaire_draft')
  if (draft) {
    try {
      const draftData = JSON.parse(draft)
      Object.assign(formData.value, draftData)
      ElMessage.info('已恢复上次保存的草稿')
    } catch {
      // 忽略解析错误
    }
  }
}

onMounted(() => {
  // 初始化表单数据
  initFormData()
})
</script>

<style scoped>
.questionnaire-container {
  min-height: 100vh;
  background-color: #f0f2f5;
}

.page-title {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px 20px 0;
}

.page-title h1 {
  margin: 0;
  padding: 20px;
  padding-bottom: 10px;
  background: #fff;
  border-radius: 8px 8px 0 0;
  font-size: 22px;
  font-weight: 600;
  color: #1f2f3d;
}

.page-title .subtitle {
  margin: 0;
  padding: 0 20px 20px;
  background: #fff;
  border-radius: 0 0 8px 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
  color: #909399;
  font-size: 14px;
}

.content-wrapper {
  display: flex;
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px;
  gap: 20px;
}

/* 左侧进度侧边栏 */
.progress-sidebar {
  width: 280px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
  overflow: hidden;
  position: sticky;
  top: 20px;
  height: fit-content;
}

.sidebar-header {
  padding: 16px 20px;
  border-bottom: 1px solid #ebeef5;
  background: linear-gradient(135deg, #409eff 0%, #337ecc 100%);
}

.sidebar-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
}

.progress-info {
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  border-bottom: 1px solid #ebeef5;
}

.progress-detail {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.progress-text {
  font-size: 14px;
  color: #606266;
}

.quick-nav {
  padding: 16px;
}

.quick-nav h4 {
  margin: 0 0 12px;
  font-size: 14px;
  color: #606266;
}

.nav-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #f5f7fa;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.3s;
}

.nav-item:hover {
  background: #ecf5ff;
}

.nav-item.answered {
  background: #f0f9eb;
}

.nav-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  background: #dcdfe6;
  color: #606266;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
  margin-right: 10px;
  flex-shrink: 0;
}

.nav-item.answered .nav-number {
  background: #67c23a;
  color: #fff;
}

.nav-text {
  flex: 1;
  font-size: 13px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-icon {
  margin-left: 8px;
}

/* 右侧问卷区域 */
.main-content {
  flex: 1;
  min-width: 0;
}

.questionnaire-card {
  border-radius: 8px;
  border: none;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  color: #1f2f3d;
}

.question-count {
  font-size: 14px;
  color: #909399;
}

.questionnaire-form {
  padding: 10px 0;
}

.question-item {
  padding: 20px;
  margin-bottom: 16px;
  background: #fafbfc;
  border-radius: 8px;
  border: 1px solid #ebeef5;
  transition: all 0.3s;
}

.question-item:hover {
  border-color: #409eff;
  box-shadow: 0 2px 12px 0 rgba(64, 158, 255, 0.1);
}

.question-item:last-child {
  margin-bottom: 0;
}

.question-header {
  display: flex;
  align-items: flex-start;
  margin-bottom: 12px;
}

.question-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: linear-gradient(135deg, #409eff 0%, #337ecc 100%);
  color: #fff;
  border-radius: 50%;
  font-size: 14px;
  font-weight: 600;
  margin-right: 12px;
  flex-shrink: 0;
}

.question-text {
  flex: 1;
  font-size: 15px;
  font-weight: 500;
  color: #303133;
  line-height: 1.6;
}

.help-text {
  display: flex;
  align-items: flex-start;
  padding: 10px 12px;
  margin-bottom: 16px;
  background: #f4f4f5;
  border-radius: 4px;
  font-size: 13px;
  color: #909399;
  line-height: 1.5;
}

.help-text :deep(.el-icon) {
  margin-right: 6px;
  margin-top: 2px;
  color: #909399;
}

/* 各类输入样式 */
.radio-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.radio-option {
  margin-right: 0 !important;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  transition: all 0.3s;
}

.radio-option:hover {
  border-color: #409eff;
}

:deep(.el-radio.is-checked .radio-option),
:deep(.radio-option.is-checked) {
  border-color: #409eff;
  background: #ecf5ff;
}

.checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.checkbox-option {
  margin-right: 0 !important;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  transition: all 0.3s;
}

.checkbox-option:hover {
  border-color: #409eff;
}

:deep(.el-checkbox.is-checked .checkbox-option),
:deep(.checkbox-option.is-checked) {
  border-color: #409eff;
  background: #ecf5ff;
}

.select-input {
  width: 100%;
}

.number-input {
  width: 200px;
}

.text-input,
.textarea-input {
  width: 100%;
}

/* 底部操作栏 */
.action-bar {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 20px;
  padding: 20px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
}

/* 响应式适配 */
@media (max-width: 1200px) {
  .content-wrapper {
    flex-direction: column;
  }

  .progress-sidebar {
    width: 100%;
    position: static;
  }

  .progress-info {
    flex-direction: row;
    gap: 24px;
  }

  .progress-detail {
    margin-top: 0;
    align-items: flex-start;
  }

  .quick-nav {
    display: none;
  }
}
</style>
