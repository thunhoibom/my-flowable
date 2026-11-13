import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { ApiResponse } from '@/types/api'
import type { AuthSession } from '@/features/auth/types'
import {
  clearAuthSession,
  getAccessToken,
  readAuthSession,
  writeAuthSession,
} from '@/features/auth/storage'

/** Tenant the request works in; the API validates it against the user's memberships. */
export const TENANT_HEADER = 'X-Tenant-Code'
export const SESSION_REFRESHED_EVENT = 'workflow-auth:session-refreshed'

// Endpoints whose 401 must not trigger a token refresh (they establish or end the session).
const SESSION_ENDPOINTS = ['/auth/login', '/auth/refresh', '/auth/logout', '/auth/csrf']

type SessionAwareConfig = InternalAxiosRequestConfig & {
  _sessionRetried?: boolean
  _skipAuthHandling?: boolean
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code = 'REQUEST_FAILED',
    public readonly status?: number,
    public readonly traceId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export const invalidResponseMessage = 'Máy chủ trả về dữ liệu không đọc được'
export const operationFailedMessage = 'Không thực hiện được'
export const missingCsrfMessage = 'Không tạo được phiên an toàn'

export function fallbackHttpMessage(status: number | undefined, transportMessage: string) {
  if (status === 401) return 'Phiên đăng nhập đã hết hạn'
  if (status === 403) return 'Bạn không có quyền làm việc này'
  return transportMessage || 'Không kết nối được. Hãy thử lại.'
}

function createHttp(baseURL: string) {
  return axios.create({
    baseURL,
    timeout: 15_000,
    withCredentials: true,
    withXSRFToken: true,
    xsrfCookieName: 'XSRF-TOKEN',
    xsrfHeaderName: 'X-XSRF-TOKEN',
    headers: {
      'Content-Type': 'application/json',
    },
  })
}

/** Flow Engine: runtime, sign-in (/auth/**), and everything not yet moved to Flow Admin BE. */
export const http = createHttp(import.meta.env.VITE_API_BASE_URL || '/api/engine')

/** Flow Admin BE: Design (drafts, publishing, service catalog, event library). */
export const adminBeHttp = createHttp(import.meta.env.VITE_ADMIN_BE_BASE_URL || '/api/admin-be')

function addAuth(config: InternalAxiosRequestConfig) {
  const accessToken = getAccessToken()
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`
  if (!config.headers.has(TENANT_HEADER)) {
    const tenantCode = readAuthSession()?.tenantCode
    if (tenantCode) config.headers.set(TENANT_HEADER, tenantCode)
  }
  return config
}

http.interceptors.request.use(addAuth)
adminBeHttp.interceptors.request.use(addAuth)

function isSessionEndpoint(url: string | undefined) {
  if (!url) return false
  const path = url.split('?')[0] ?? ''
  return SESSION_ENDPOINTS.some((endpoint) => path.endsWith(endpoint))
}

let refreshInFlight: Promise<boolean> | null = null

/**
 * Renews the SSO access token from the HttpOnly refresh cookie. Concurrent 401s share one
 * refresh. Resolves false when the API has no refreshable session (local login, expired SSO).
 */
export function refreshSession(): Promise<boolean> {
  refreshInFlight ??= (async () => {
    try {
      const csrf = await http.get<ApiResponse<string>>('/auth/csrf', {
        _skipAuthHandling: true,
      } as AxiosRequestConfig)
      const csrfToken = csrf.data?.data
      const response = await http.post<ApiResponse<AuthSession>>('/auth/refresh', undefined, {
        headers: csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : undefined,
        _sessionRetried: true,
        _skipAuthHandling: true,
      } as AxiosRequestConfig)
      const refreshed = response.data?.code === 'SUCCESS' ? response.data.data : null
      if (!refreshed) return false
      const nextSession: AuthSession = { ...readAuthSession(), ...refreshed }
      writeAuthSession(nextSession)
      window.dispatchEvent(new CustomEvent(SESSION_REFRESHED_EVENT, { detail: nextSession }))
      return true
    } catch {
      return false
    } finally {
      refreshInFlight = null
    }
  })()
  return refreshInFlight
}

/** Same error handling for both backends; the session is always refreshed through Flow Engine. */
function handleErrors(instance: AxiosInstance) {
  instance.interceptors.response.use(undefined, async (error: AxiosError<ApiResponse<unknown>>) => {
    const config = error.config as SessionAwareConfig | undefined
    if (
      error.response?.status === 401 &&
      config &&
      !config._sessionRetried &&
      !isSessionEndpoint(config.url) &&
      readAuthSession()
    ) {
      // The filter rejects before any handler runs, so replaying the request is safe.
      config._sessionRetried = true
      if (await refreshSession()) return instance.request(config)
    }
    const body = error.response?.data
    const traceId = error.response?.headers['x-trace-id'] as string | undefined
    if (error.response?.status === 401 && !config?._skipAuthHandling) {
      clearAuthSession()
      window.dispatchEvent(new CustomEvent('workflow-auth:unauthorized'))
    }
    const message =
      body?.message || fallbackHttpMessage(error.response?.status, error.message || '')
    return Promise.reject(
      new ApiError(message, body?.code || 'HTTP_ERROR', error.response?.status, traceId),
    )
  })
}

handleErrors(http)
handleErrors(adminBeHttp)

async function unwrap<T>(request: Promise<AxiosResponse<ApiResponse<T>>>) {
  const response = await request
  const body = response.data
  if (!body || typeof body !== 'object' || typeof body.code !== 'string') {
    throw new ApiError(invalidResponseMessage, 'INVALID_RESPONSE', response.status)
  }
  if (body.code !== 'SUCCESS') {
    throw new ApiError(body.message || operationFailedMessage, body.code, response.status)
  }
  return body.data
}

async function withCsrf(config?: AxiosRequestConfig): Promise<AxiosRequestConfig> {
  const response = await http.get<ApiResponse<string>>('/auth/csrf')
  const token = response.data?.data
  if (!token) throw new ApiError(missingCsrfMessage, 'CSRF_TOKEN_MISSING', response.status)
  return {
    ...config,
    headers: {
      ...config?.headers,
      'X-XSRF-TOKEN': token,
    },
  }
}

/** Unwraps {@link ApiResponse}; unsafe methods carry the CSRF token issued by Flow Engine. */
function createClient(instance: AxiosInstance) {
  return {
    get<T>(url: string, config?: AxiosRequestConfig) {
      return unwrap(instance.get<ApiResponse<T>>(url, config))
    },
    async post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
      return unwrap(instance.post<ApiResponse<T>>(url, data, await withCsrf(config)))
    },
    async put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
      return unwrap(instance.put<ApiResponse<T>>(url, data, await withCsrf(config)))
    },
    async patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
      return unwrap(instance.patch<ApiResponse<T>>(url, data, await withCsrf(config)))
    },
    async delete<T>(url: string, config?: AxiosRequestConfig) {
      return unwrap(instance.delete<ApiResponse<T>>(url, await withCsrf(config)))
    },
  }
}

/** Flow Engine. */
export const apiClient = createClient(http)

/** Flow Admin BE: Design. */
export const adminBeClient = createClient(adminBeHttp)

export function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : operationFailedMessage
}
