import { adminBeClient, apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  DefinitionPageQuery,
  DeployProcessResult,
  ProcessDefinition,
  ProcessDefinitionSummary,
  ProcessDraft,
  ProcessReference,
  ProcessRelease,
  PublishProcessResult,
} from './types'

const ROOT = '/workflow/management/process'
/** Admin API of Flow Engine: what is deployed. */
const DEPLOYED = '/api/admin/process-definitions'
const key = (processDefinitionKey: string) => encodeURIComponent(processDefinitionKey)

export const definitionApi = {
  page: (params: DefinitionPageQuery) =>
    adminBeClient.get<PageResult<ProcessDefinitionSummary>>(`${ROOT}/definitions/page`, { params }),
  listProcesses: () => apiClient.get<ProcessDefinition[]>(DEPLOYED),
  listVersions: (processDefinitionKey: string) =>
    apiClient.get<ProcessDefinition[]>(DEPLOYED, { params: { processDefinitionKey } }),
  detail: (processDefinitionKey: string, version?: number) =>
    apiClient.get<ProcessDefinition>(`${DEPLOYED}/${key(processDefinitionKey)}`, {
      params: { version },
    }),
  exists: (processDefinitionKey: string) =>
    apiClient.get<boolean>(`${DEPLOYED}/${key(processDefinitionKey)}/exists`),
  /** CI path: overwrite the draft and publish at once. The designer uses saveDraft + publish. */
  deploy: (payload: {
    processDefinitionKey: string
    processDefinitionName: string
    bpmnXml: string
  }) => adminBeClient.post<DeployProcessResult>(`${ROOT}/deploy`, payload),

  draft: (processDefinitionKey: string) =>
    adminBeClient.get<ProcessDraft>(`${ROOT}/drafts/${key(processDefinitionKey)}`),
  createDraft: (payload: { processDefinitionKey: string; name: string; bpmnXml: string }) =>
    adminBeClient.post<ProcessDraft>(`${ROOT}/drafts`, payload),
  saveDraft: (
    processDefinitionKey: string,
    payload: { name: string; bpmnXml: string; revision: number },
  ) => adminBeClient.put<ProcessDraft>(`${ROOT}/drafts/${key(processDefinitionKey)}`, payload),
  loadVersionIntoDraft: (
    processDefinitionKey: string,
    payload: { version: number; revision: number },
  ) =>
    adminBeClient.post<ProcessDraft>(
      `${ROOT}/drafts/${key(processDefinitionKey)}/load-version`,
      payload,
    ),
  discardDraft: (processDefinitionKey: string, revision: number) =>
    adminBeClient.post<ProcessDraft>(`${ROOT}/drafts/${key(processDefinitionKey)}/discard`, {
      revision,
    }),

  publish: (processDefinitionKey: string, revision: number) =>
    adminBeClient.post<PublishProcessResult>(`${ROOT}/${key(processDefinitionKey)}/publish`, {
      revision,
    }),
  rollback: (processDefinitionKey: string, version: number) =>
    adminBeClient.post<PublishProcessResult>(`${ROOT}/${key(processDefinitionKey)}/rollback`, {
      version,
    }),
  suspend: (processDefinitionKey: string) =>
    adminBeClient.post<ProcessRelease>(`${ROOT}/${key(processDefinitionKey)}/suspend`),
  resume: (processDefinitionKey: string) =>
    adminBeClient.post<ProcessRelease>(`${ROOT}/${key(processDefinitionKey)}/resume`),
  /** Published processes that call this one ("where used"). */
  callers: (processDefinitionKey: string) =>
    apiClient.get<ProcessReference[]>(`${DEPLOYED}/${key(processDefinitionKey)}/callers`),
  /** Processes that the latest version of this one calls. */
  callees: (processDefinitionKey: string) =>
    apiClient.get<ProcessReference[]>(`${DEPLOYED}/${key(processDefinitionKey)}/callees`),
  releases: (processDefinitionKey: string) =>
    adminBeClient.get<ProcessRelease[]>(`${ROOT}/${key(processDefinitionKey)}/releases`),

  /** Start-time Agent inputs of a published process, used to suggest call activity in-mappings. */
  startInputs: (processDefinitionKey: string) =>
    apiClient.post<{ fields: Array<{ variablePath: string; label: string; required: boolean }> }>(
      '/workflow/process/interaction',
      { processDefinitionKey, variables: {} },
    ),

  deleteAll: (processDefinitionKey: string) =>
    adminBeClient.delete<void>(`${ROOT}/definition`, { params: { processDefinitionKey } }),
  deleteVersion: (processDefinitionKey: string, version: number) =>
    apiClient.delete<void>(`${DEPLOYED}/${key(processDefinitionKey)}/versions/${version}`),
}
