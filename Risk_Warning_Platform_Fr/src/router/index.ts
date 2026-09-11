import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', requiresAuth: false }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册', requiresAuth: false }
  },
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/Home.vue'),
    meta: { title: '首页', requiresAuth: true }
  },
  {
    path: '/enterprise',
    name: 'Enterprise',
    component: () => import('@/views/Enterprise.vue'),
    meta: { title: '企业管理', requiresAuth: true }
  },
  {
    path: '/project',
    name: 'Project',
    component: () => import('@/views/Project.vue'),
    meta: { title: '项目管理', requiresAuth: true }
  },
  {
    path: '/questionnaire',
    name: 'Questionnaire',
    component: () => import('@/views/Questionnaire.vue'),
    meta: { title: '风险评估问卷', requiresAuth: true }
  },
  {
    path: '/assessment-result',
    name: 'AssessmentResult',
    component: () => import('@/views/AssessmentResult.vue'),
    meta: { title: '评估结果', requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, _from, next) => {
  // 设置页面标题
  document.title = `${to.meta.title || '风险预警平台'} - 风险合规预警系统`

  const token = sessionStorage.getItem('token')

  if (to.meta.requiresAuth && !token) {
    // 需要登录但未登录，跳转到登录页
    next({ path: '/login', query: { redirect: to.fullPath } })
  } else if ((to.path === '/login' || to.path === '/register') && token) {
    // 已登录用户访问登录/注册页，跳转到首页
    next({ path: '/' })
  } else {
    next()
  }
})

export default router
