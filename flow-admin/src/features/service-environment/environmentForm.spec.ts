import { describe, expect, it } from 'vitest'
import type { ServiceEnvironment } from './api'
import { emptyForm, toForm, toSettings, validate } from './environmentForm'

const entry: ServiceEnvironment = {
  serviceKey: 'crm',
  settings: {
    baseUrl: 'https://crm.dev.local',
    sharedAuth: {
      type: 'API_KEY',
      apiKeyHeader: 'X-Api-Key',
      apiKeyLocation: 'HEADER',
      secret: null,
      details: { scopes: [] },
    },
    operations: {
      getCustomer: { mode: 'OVERRIDE', auth: { type: 'BEARER', secret: null } },
    },
  },
  storedSecrets: ['shared.secret', 'operations.getCustomer.secret'],
  updatedBy: 'alice',
  updatedAt: '2026-11-15T08:00:00Z',
}

describe('environment form', () => {
  it('keeps stored secrets and operation credentials when a field is left empty', () => {
    const form = toForm(entry)
    form.baseUrl = ' https://crm.prod.local '

    const settings = toSettings(form, entry)

    expect(settings.baseUrl).toBe('https://crm.prod.local')
    expect(settings.sharedAuth).toMatchObject({ type: 'API_KEY', apiKeyHeader: 'X-Api-Key' })
    expect(settings.sharedAuth.secret).toBeNull()
    expect(settings.sharedAuth.details?.accessKey).toBeNull()
    expect(settings.operations).toEqual(entry.settings.operations)
  })

  it('sends a new secret and splits scopes', () => {
    const form = { ...emptyForm(), serviceKey: 'erp', authType: 'OAUTH2_CLIENT' as const }
    form.tokenUrl = 'https://idp/token'
    form.clientId = 'flow'
    form.scopes = 'read, write'
    form.secret = 's3cret'

    const settings = toSettings(form)

    expect(settings.sharedAuth.secret).toBe('s3cret')
    expect(settings.sharedAuth.details?.scopes).toEqual(['read', 'write'])
    expect(settings.sharedAuth.apiKeyHeader).toBeNull()
    expect(settings.operations).toEqual({})
  })

  it('reports the first problem', () => {
    expect(validate({ ...emptyForm(), serviceKey: '1bad' })).toBe('Mã dịch vụ không hợp lệ')
    expect(validate({ ...emptyForm(), serviceKey: 'crm', baseUrl: 'ftp://x' })).toContain('http://')
    expect(validate({ ...emptyForm(), serviceKey: 'crm', authType: 'API_KEY' })).toContain('header')
    expect(validate(toForm(entry))).toBeNull()
  })
})
