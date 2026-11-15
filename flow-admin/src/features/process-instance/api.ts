import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  ProcessDiagramData,
  ProcessInstanceDetail,
  ProcessInstanceSummary,
  CompleteTaskRequest,
  CompleteTaskResult,
  ParticipantRequirement,
  ProcessInteraction,
  ProcessWait,
  RejectTaskRequest,
  StartProcessRequest,
  StartProcessResult,
  TriggerReceiveTaskRequest,
} from './types'

/** Admin API of Flow Engine. */
const ROOT = '/api/admin/process-instances'
const instance = (id: string) => `${ROOT}/${encodeURIComponent(id)}`

export interface InstancePageQuery {
  pageNum: number
  pageSize: number
  processDefinitionKey?: string
  processDefinitionName?: string
  processInstanceId?: string
  businessKey?: string
  status?: 'all' | 'running' | 'finished'
  /** Hide instances started by a call activity. */
  rootOnly?: boolean
}

export const processInstanceApi = {
  startInteraction: (payload: {
    processDefinitionKey: string
    processDefinitionId?: string
    variables: Record<string, unknown>
  }) => apiClient.post<ProcessInteraction>('/workflow/process/interaction', payload),
  start: (payload: StartProcessRequest) =>
    apiClient.post<StartProcessResult>('/workflow/process/start', payload),
  startParticipantRequirements: (payload: {
    processDefinitionKey: string
    processDefinitionId?: string
    variables: Record<string, unknown>
  }) =>
    apiClient.post<ParticipantRequirement[]>('/workflow/process/participant-requirements', payload),
  page: (params: InstancePageQuery) =>
    apiClient.get<PageResult<ProcessInstanceSummary>>(ROOT, { params }),
  detail: (processInstanceId: string) =>
    apiClient.get<ProcessInstanceDetail>(instance(processInstanceId)),
  diagram: (processInstanceId: string) =>
    apiClient.get<ProcessDiagramData>(`${instance(processInstanceId)}/diagram-data`),
  terminate: (processInstanceId: string, reason: string) =>
    apiClient.post<void>(`${instance(processInstanceId)}/terminate`, { reason }),
  waits: (processInstanceId: string) =>
    apiClient.get<ProcessWait[]>(`${instance(processInstanceId)}/waits`),
  triggerReceiveTask: ({ processInstanceId, activityId, ...body }: TriggerReceiveTaskRequest) =>
    apiClient.post<void>(
      `${instance(processInstanceId)}/receive-tasks/${encodeURIComponent(activityId)}/trigger`,
      body,
    ),
  approve: (payload: CompleteTaskRequest) =>
    apiClient.post<CompleteTaskResult>('/workflow/task/approve', payload),
  taskInteraction: (payload: { taskId: string; variables: Record<string, unknown> }) =>
    apiClient.post<ProcessInteraction>('/workflow/task/interaction', payload),
  taskParticipantRequirements: (payload: {
    taskId: string
    action: 'APPROVE' | 'REJECT'
    targetActivityId?: string
    variables: Record<string, unknown>
  }) =>
    apiClient.post<ParticipantRequirement[]>('/workflow/task/participant-requirements', payload),
  reject: (payload: RejectTaskRequest) =>
    apiClient.post<CompleteTaskResult>('/workflow/task/reject', payload),
  transfer: (payload: {
    taskId: string
    currentAssignee?: string
    currentCandidateGroups: string[]
    targetAssignee?: string
    targetCandidateUsers: string[]
    targetCandidateGroups: string[]
    comment?: string
  }) => apiClient.post<void>('/workflow/task/transfer', payload),
  /** Admin: hand a running task to someone else, whoever holds it now. */
  reassign: (
    taskId: string,
    payload: { targetAssignee?: string; targetCandidateUsers?: string[]; reason: string },
  ) => apiClient.post<void>(`/api/admin/tasks/${encodeURIComponent(taskId)}/reassign`, payload),
}
