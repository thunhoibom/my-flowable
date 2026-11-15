import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type { TenantCommand, WorkflowTenant } from './types'

/** Admin API of Flow Engine. */
const ROOT = '/api/admin/tenants'

export const tenantApi = {
  page: (params: { pageNum: number; pageSize: number; keyword?: string; enabled?: boolean }) =>
    apiClient.get<PageResult<WorkflowTenant>>(ROOT, { params }),
  create: (payload: TenantCommand) => apiClient.post<WorkflowTenant>(ROOT, payload),
  update: (id: number, payload: TenantCommand) => apiClient.put<void>(`${ROOT}/${id}`, payload),
  updateEnabled: (id: number, enabled: boolean) =>
    apiClient.put<void>(`${ROOT}/${id}/enabled`, undefined, { params: { enabled } }),
}
