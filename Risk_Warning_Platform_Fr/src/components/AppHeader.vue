<template>
  <el-header class="app-header">
    <div class="logo">
      <h1>企业合规风险预警软件</h1>
    </div>
    <div class="nav-menu">
      <el-menu mode="horizontal" :default-active="activeMenu" router>
        <el-menu-item index="/">首页</el-menu-item>
        <el-menu-item index="/enterprise">企业管理</el-menu-item>
        <el-menu-item index="/project">项目管理</el-menu-item>
      </el-menu>
    </div>
    <div class="user-info">
      <el-dropdown @command="handleCommand">
        <span class="user-dropdown">
          <el-avatar :size="32" :src="userStore.user?.avatarUrl || undefined">
            {{ userStore.user?.fullName?.charAt(0) }}
          </el-avatar>
          <span class="user-name">{{ userStore.user?.fullName }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">个人中心</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </el-header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ArrowDown } from '@element-plus/icons-vue'

const route = useRoute()
const userStore = useUserStore()

// 根据当前路由自动计算激活的菜单项
const activeMenu = computed(() => route.path)

const handleCommand = (command: string) => {
  if (command === 'logout') {
    userStore.logout()
  } else if (command === 'profile') {
    // 个人中心功能
  }
}
</script>

<style lang="scss" scoped>
.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  padding: 0 24px;
  width: 100%;
  height: 60px;

  .logo h1 {
    font-size: 20px;
    color: #333;
    margin: 0;
    white-space: nowrap;
  }

  .nav-menu {
    flex: 1;
    margin-left: 40px;
    overflow: visible;

    :deep(.el-menu) {
      border-bottom: none;

      .el-menu-item {
        white-space: nowrap;
      }
    }
  }

  .user-dropdown {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    white-space: nowrap;

    .user-name {
      color: #333;
      font-size: 14px;
    }
  }
}
</style>

