import request from '@/utils/request'
import type { LoginRequest, RegisterRequest, LoginResponse, UserResponse, Result } from '@/types'

// 用户登录
export function login(data: LoginRequest): Promise<Result<LoginResponse>> {
  return request.post('org/user/login', data)
}

// 用户注册
export function register(data: RegisterRequest): Promise<Result<UserResponse>> {
  return request.post('org/user/register', data)
}

// 获取当前用户信息
export function getCurrentUser(): Promise<Result<UserResponse>> {
  return request.get('org/user/profile')
}

// 更新用户信息
export function updateProfile(data: Partial<UserResponse>): Promise<Result<UserResponse>> {
  return request.put('/org/user/profile', data)
}

// 根据用户ID获取用户信息
export function getUserById(userId: number): Promise<Result<UserResponse>> {
  return request.get(`org/user/${userId}`)
}

// 根据邮箱获取用户信息
export function getUserByEmail(email: string): Promise<Result<UserResponse>> {
  return request.get('org/user/email', { params: { email } })
}

