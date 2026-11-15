import { apiClient } from '@/api/http'

export type AuthType =
  | 'NONE'
  | 'BEARER'
  | 'BASIC'
  | 'API_KEY'
  | 'OAUTH2_CLIENT'
  | 'MTLS'
  | 'BEARER_VARIABLE'
  | 'HMAC'
  | 'AWS_SIG_V4'

export interface AuthDetails {
  tokenUrl?: string | null
  clientId?: string | null
  scopes?: string[]
  /** Secret: null in responses, null in a save keeps the stored value. */
  certificatePem?: string | null
  bearerVariable?: string | null
  /** Secret: null in responses, null in a save keeps the stored value. */
  accessKey?: string | null
  region?: string | null
  awsService?: string | null
  signatureHeader?: string | null
}

export interface AuthConfig {
  type: AuthType
  apiKeyHeader?: string | null
  /** Secret: null in responses, null in a save keeps the stored value. */
  secret?: string | null
  apiKeyLocation?: 'HEADER' | 'QUERY'
  details?: AuthDetails
}

export interface OperationAuth {
  mode?: 'INHERIT' | 'OVERRIDE' | 'NONE' | null
  auth: AuthConfig
}

/** Base URL and credentials of a service on this Flow Engine. The design comes with each deployment. */
export interface ServiceSettings {
  baseUrl?: string | null
  sharedAuth: AuthConfig
  operations: Record<string, OperationAuth>
}

export interface ServiceEnvironment {
  serviceKey: string
  settings: ServiceSettings
  /** Which secrets are stored, e.g. `shared.secret`, `operations.getCustomer.secret`. */
  storedSecrets: string[]
  updatedBy?: string | null
  updatedAt?: string | null
}

/** Admin API of Flow Engine (`work_service_environment`). Responses never carry a secret. */
const ROOT = '/api/admin/service-environments'
const path = (serviceKey: string) => `${ROOT}/${encodeURIComponent(serviceKey)}`

export const serviceEnvironmentApi = {
  list: () => apiClient.get<ServiceEnvironment[]>(ROOT),
  save: (serviceKey: string, settings: ServiceSettings) =>
    apiClient.put<ServiceEnvironment>(path(serviceKey), settings),
  delete: (serviceKey: string) => apiClient.delete<void>(path(serviceKey)),
}
