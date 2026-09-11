// API响应类型
export interface Result<T> {
  code: number
  message: string
  data: T
}

// 登录请求
export interface LoginRequest {
  email: string
  password: string
}

// 注册请求
export interface RegisterRequest {
  email: string
  password: string
  fullName: string
}

// 用户响应
export interface UserResponse {
  id: number
  email: string
  fullName: string
  avatarUrl: string | null
  createdAt: string
  updatedAt: string
}

// 登录响应
export interface LoginResponse {
  token: string
  tokenType: string
  user: UserResponse
}

// 企业相关类型
export interface Enterprise {
  id: number
  name: string
  creditCode: string
  type: string
  industry: string
  businessScope: string
  registeredCapital: number
  establishmentDate: string
  legalRepresentative: string
  registeredAddress: string
  businessStatus: string
  createdAt: string
  updatedAt: string
}

export interface EnterpriseCreateRequest {
  name: string
  creditCode: string
  type: string
  industry?: string
  businessScope?: string
  registeredCapital?: number
  establishmentDate?: string
  legalRepresentative?: string
  registeredAddress?: string
  businessStatus?: string
}

export interface EnterpriseUserResponse {
  role: string
  user: {
    id: number
    email: string
    fullName: string
    avatarUrl: string | null
    createdAt: string
    updatedAt: string
  }
}

export interface AddMemberRequest {
  userId: number
  role: string
}

// 项目相关类型
export interface Project {
  id: number
  enterpriseId: number
  name: string
  type: string
  status: string
  description: string
  startDate: string
  plannedCompletionDate: string
  actualCompletionDate: string | null
  industry: string
  region: string
  orientedUser: string
  createdAt: string
  updatedAt: string
}

export interface ProjectCreateRequest {
  enterpriseId: number
  name: string
  type: string
  description?: string
  startDate?: string
  plannedCompletionDate?: string
  industry?: string
  region?: string
  orientedUser?: string
}

export interface ProjectMemberResponse {
  role: string
  user: {
    id: number
    email: string
    fullName: string
    avatarUrl: string | null
    createdAt: string
    updatedAt: string
  }
}

// 评估记录
export interface Assessment {
  id: number
  projectId: number
  assessmentDate: string | null
  overallScore: number | null
  overallRiskLevel: string | null
  details: string | null
  recommendations: string | null
  status: string
  createdAt: string
}

// 枚举选项类型
export interface OptionItem {
  label: string
  value: string
}

// 项目类型选项
export const projectTypeOptions: OptionItem[] = [
  { label: '常规评估', value: 'REGULAR' },
  { label: '专项评估', value: 'SPECIAL' }
]

// 行业选项
export const industryOptions: OptionItem[] = [
  { label: '供应链管理', value: 'SUPPLY_CHAIN' },
  { label: '市场营销与广告', value: 'MARKETING' },
  { label: '人力资源与劳动关系', value: 'HR' },
  { label: '跨境交易与支付', value: 'CROSS_BORDER' },
  { label: '数据隐私与网络安全', value: 'DATA_PRIVACY' },
  { label: '反垄断与不正当竞争', value: 'ANTI_TRUST' },
  { label: '知识产权', value: 'IP' },
  { label: '财务与税务', value: 'FINANCE_TAX' }
]

// 区域选项
export const regionOptions: OptionItem[] = [
  { label: '中国', value: 'CN' },
  { label: '美国', value: 'US' },
  { label: '欧洲', value: 'EU' },
  { label: '多区域', value: 'MULTI' }
]

// 面向用户选项
export const orientedUserOptions: OptionItem[] = [
  { label: '政府机构与官员', value: 'GOVERNMENT' },
  { label: '国有企业', value: 'SOE' },
  { label: '关键供应商/承包商', value: 'SUPPLIER' },
  { label: '客户', value: 'CUSTOMER' },
  { label: '员工', value: 'EMPLOYEE' },
  { label: '个人用户', value: 'INDIVIDUAL' },
  { label: '公众', value: 'PUBLIC' }
]

// 项目状态选项
export const projectStatusOptions: OptionItem[] = [
  { label: '进行中', value: 'IN_PROGRESS' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '已归档', value: 'ARCHIVED' }
]

// 企业角色选项
export const enterpriseRoleOptions: OptionItem[] = [
  { label: '管理员', value: 'admin' },
  { label: '成员', value: 'member' }
]

// 项目角色选项
export const projectRoleOptions: OptionItem[] = [
  { label: '项目管理员', value: 'project_admin' },
  { label: '编辑者', value: 'editor' },
  { label: '查看者', value: 'viewer' }
]
