import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { adminBeClient, adminBeHttp, http, TENANT_HEADER } from '@/api/http'
import { writeAuthSession } from '@/features/auth/storage'

const session = {
  userId: 'user-1',
  username: 'alice',
  displayName: 'Alice',
  tenantCode: 'rsa-hn',
  roles: ['WF_ADMIN'],
  tokenType: 'Bearer',
  expiresIn: 1800,
  expiresAt: '2099-01-01T00:00:00Z',
}

function reply(config: InternalAxiosRequestConfig, status: number, data: unknown): AxiosResponse {
  const response = { data, status, statusText: String(status), headers: {}, config }
  if (status >= 400) {
    throw Object.assign(new Error(`Request failed with status ${status}`), {
      isAxiosError: true,
      config,
      response,
    })
  }
  return response
}

describe('Flow Admin BE client', () => {
  const engineAdapter = http.defaults.adapter
  const adminAdapter = adminBeHttp.defaults.adapter
  let engineCalls: string[]
  let adminCalls: { url: string; tenant: unknown; csrf: unknown; base: unknown }[]

  beforeEach(() => {
    localStorage.clear()
    engineCalls = []
    adminCalls = []
    http.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      engineCalls.push(config.url ?? '')
      if (config.url === '/auth/csrf')
        return reply(config, 200, { code: 'SUCCESS', data: 'csrf-1' })
      return reply(config, 200, { code: 'SUCCESS', data: session })
    }) as AxiosAdapter
  })

  afterEach(() => {
    http.defaults.adapter = engineAdapter
    adminBeHttp.defaults.adapter = adminAdapter
  })

  function useAdminAdapter(handler: (config: InternalAxiosRequestConfig) => AxiosResponse) {
    adminBeHttp.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      adminCalls.push({
        url: config.url ?? '',
        tenant: config.headers.get(TENANT_HEADER),
        csrf: config.headers.get('X-XSRF-TOKEN'),
        base: config.baseURL,
      })
      return handler(config)
    }) as AxiosAdapter
  }

  it('talks to /api/admin-be with the tenant and a CSRF token issued by Flow Engine', async () => {
    writeAuthSession(session)
    useAdminAdapter((config) => reply(config, 200, { code: 'SUCCESS', data: { revision: 2 } }))

    await expect(
      adminBeClient.put('/workflow/management/process/drafts/order', {}),
    ).resolves.toEqual({
      revision: 2,
    })

    expect(adminCalls[0]).toEqual({
      url: '/workflow/management/process/drafts/order',
      tenant: 'rsa-hn',
      csrf: 'csrf-1',
      base: '/api/admin-be',
    })
    expect(engineCalls).toEqual(['/auth/csrf'])
  })

  it('refreshes the session through Flow Engine and replays the Admin BE request', async () => {
    writeAuthSession(session)
    let expired = true
    http.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      engineCalls.push(config.url ?? '')
      if (config.url === '/auth/refresh') expired = false
      return reply(config, 200, {
        code: 'SUCCESS',
        data: config.url === '/auth/csrf' ? 'c' : session,
      })
    }) as AxiosAdapter
    useAdminAdapter((config) =>
      expired
        ? reply(config, 401, { code: 'UNAUTHORIZED', message: 'expired' })
        : reply(config, 200, { code: 'SUCCESS', data: 'ok' }),
    )

    await expect(adminBeClient.get('/services')).resolves.toBe('ok')

    expect(adminCalls.map((call) => call.url)).toEqual(['/services', '/services'])
    expect(engineCalls).toContain('/auth/refresh')
  })
})
