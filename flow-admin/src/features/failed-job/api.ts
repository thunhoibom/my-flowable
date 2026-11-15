import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'

/** A Flowable dead-letter job: it failed every retry and waits for an admin. */
export interface FailedJob {
  jobId: string
  jobType?: string | null
  processInstanceId?: string | null
  processDefinitionId?: string | null
  processDefinitionKey?: string | null
  executionId?: string | null
  activityId?: string | null
  activityName?: string | null
  exceptionMessage?: string | null
  createTime?: string | null
  dueDate?: string | null
}

export interface FailedJobQuery {
  pageNum: number
  pageSize: number
  processInstanceId?: string
}

/** Admin API of Flow Engine. */
const ROOT = '/api/admin/failed-jobs'
const job = (jobId: string) => `${ROOT}/${encodeURIComponent(jobId)}`

export const failedJobApi = {
  page: (params: FailedJobQuery) => apiClient.get<PageResult<FailedJob>>(ROOT, { params }),
  stacktrace: (jobId: string) => apiClient.get<string | null>(`${job(jobId)}/stacktrace`),
  retry: (jobId: string, retries = 3) => apiClient.post<void>(`${job(jobId)}/retry`, { retries }),
  delete: (jobId: string) => apiClient.delete<void>(job(jobId)),
}
