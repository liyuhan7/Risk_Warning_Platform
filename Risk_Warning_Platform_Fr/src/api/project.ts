import request from '@/utils/request'
import type {
  Project,
  ProjectCreateRequest,
  ProjectMemberResponse,
  AddMemberRequest,
  Assessment,
  Result
} from '@/types'

// 创建项目
export function createProject(data: ProjectCreateRequest): Promise<Result<Project>> {
  return request.post('org/project', data)
}

// 获取所有项目
export function getAllProjects(): Promise<Result<Project[]>> {
  return request.get('org/project')
}

// 添加项目成员
export function addProjectMember(projectId: number, data: AddMemberRequest): Promise<Result<void>> {
  return request.post(`org/project/${projectId}/members`, data)
}

// 获取项目成员列表
export function getProjectMembers(projectId: number): Promise<Result<ProjectMemberResponse[]>> {
  return request.get(`org/project/${projectId}/members`)
}

// 根据项目ID获取评估记录
export function getAssessmentByProjectId(projectId: number): Promise<Result<Assessment>> {
  return request.get(`org/project/${projectId}/assessment`)
}

