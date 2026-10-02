import axios, { type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import type { Result } from '@/types'
import { ElMessage } from 'element-plus'
import router from '@/router'

const notifiedErrors = new WeakSet<object>()

function errorMessage(value: unknown, fallback: string): string {
  if (typeof value === 'object' && value !== null && 'message' in value
      && typeof value.message === 'string' && value.message.trim()) {
    return value.message
  }
  return fallback
}

/** 请求层与调用方共用，保留本地错误提示并避免同一异常重复弹错。 */
export function notifyRequestError(error: unknown, fallback = '请求失败'): void {
  if (axios.isCancel(error)) return
  if (typeof error === 'object' && error !== null) {
    if (notifiedErrors.has(error)) return
    notifiedErrors.add(error)
  }
  ElMessage.error(errorMessage(error, fallback))
}

const request = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' }
})

request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = sessionStorage.getItem('token')
    if (token) config.headers.Authorization = token
    return config
  },
  (error) => Promise.reject(error)
)

request.interceptors.response.use(
  (response: AxiosResponse<Result<unknown>>) => {
    const body = response.data
    if (typeof body === 'object' && body !== null && body.code === 200 && 'data' in body) {
      return body as unknown as AxiosResponse
    }
    const error = new Error(errorMessage(body, '服务响应格式错误或请求失败'))
    notifyRequestError(error)
    return Promise.reject(error)
  },
  (error: unknown) => {
    // 主动取消请求属于调用方控制流程，不作为网络故障提示。
    if (axios.isCancel(error)) return Promise.reject(error)
    if (axios.isAxiosError(error)) {
      if (error.response) {
        if (error.response.status === 401) {
          sessionStorage.removeItem('token')
          sessionStorage.removeItem('user')
          if (router.currentRoute.value.path !== '/login') void router.push('/login')
          error.message = errorMessage(error.response.data, '登录已过期，请重新登录')
        } else {
          error.message = errorMessage(error.response.data, '请求失败')
        }
      } else {
        error.message = '网络错误，请检查网络连接'
      }
    }
    notifyRequestError(error)
    return Promise.reject(error)
  }
)

export default request
