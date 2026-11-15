import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type { DirectoryUser, TenantMember } from './types'

/** Admin API of Flow Engine. */
const ROOT = '/api/admin'

/** Tenant membership only: roles are assigned in EIM. */
export const accessApi = {
  directoryUsers: (params: { keyword?: string; pageNum: number; pageSize: number }) =>
    apiClient.get<PageResult<DirectoryUser>>(`${ROOT}/directory/users`, { params }),
  members: (keyword?: string) =>
    apiClient.get<TenantMember[]>(`${ROOT}/members`, { params: { keyword } }),
  addMember: (username: string) => apiClient.post<TenantMember>(`${ROOT}/members`, { username }),
  updateMemberEnabled: (userId: string, enabled: boolean) =>
    apiClient.put<void>(`${ROOT}/members/${encodeURIComponent(userId)}/enabled`, undefined, {
      params: { enabled },
    }),
}
