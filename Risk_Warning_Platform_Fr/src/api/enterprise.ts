import request from '@/utils/request'
import type {
  Enterprise,
  EnterpriseCreateRequest,
  EnterpriseUserResponse,
  AddMemberRequest,
  Result
} from '@/types'

// 创建企业
export function createEnterprise(data: EnterpriseCreateRequest): Promise<Result<Enterprise>> {
  return request.post('org/enterprise', data)
}

// 获取所有企业
export function getAllEnterprises(): Promise<Result<Enterprise[]>> {
  return request.get('org/enterprise')
}

// 添加企业成员
export function addEnterpriseMember(enterpriseId: number, data: AddMemberRequest): Promise<Result<void>> {
  return request.post(`org/enterprise/${enterpriseId}/members`, data)
}

// 获取企业成员列表
export function getEnterpriseMembers(enterpriseId: number): Promise<Result<EnterpriseUserResponse[]>> {
  return request.get(`org/enterprise/${enterpriseId}/members`)
}
