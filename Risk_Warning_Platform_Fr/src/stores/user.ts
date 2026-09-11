import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UserResponse } from '@/types'
import { login as loginApi, register as registerApi, getCurrentUser } from '@/api/user'
import type { LoginRequest, RegisterRequest } from '@/types'
import router from '@/router'
import websocketService from '@/utils/websocket'

export const useUserStore = defineStore('user', () => {
  const user = ref<UserResponse | null>(null)
  const token = ref<string | null>(sessionStorage.getItem('token'))

  // 初始化用户信息
  const initUser = () => {
    const userStr = sessionStorage.getItem('user')
    if (userStr) {
      user.value = JSON.parse(userStr)
      // 如果用户信息存在且有token，自动重连WebSocket
      if (user.value && token.value) {
        websocketService.connect(user.value.id)
      }
    }
  }

  // 登录
  const login = async (loginData: LoginRequest) => {
    const res = await loginApi(loginData)
    // 组合 tokenType 和 token，存储完整的 Bearer token
    const fullToken = `${res.data.tokenType} ${res.data.token}`
    token.value = fullToken
    user.value = res.data.user
    sessionStorage.setItem('token', fullToken)
    sessionStorage.setItem('user', JSON.stringify(res.data.user))

    // 登录成功后连接WebSocket
    if (user.value) {
      websocketService.connect(user.value.id)
    }

    return res
  }

  // 注册
  const register = async (registerData: RegisterRequest) => {
    const res = await registerApi(registerData)
    return res
  }

// 获取用户信息
  const fetchUser = async () => {
    const res = await getCurrentUser()
    user.value = res.data
    sessionStorage.setItem('user', JSON.stringify(res.data))
    return res
  }

  // 退出登录
  const logout = () => {
    // 断开WebSocket连接
    websocketService.disconnect()

    token.value = null
    user.value = null
    sessionStorage.removeItem('token')
    sessionStorage.removeItem('user')
    router.push('/login')
  }

  // 初始化
  initUser()

  return {
    user,
    token,
    login,
    register,
    fetchUser,
    logout
  }
})

