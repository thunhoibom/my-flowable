import type { AuthType, ServiceEnvironment, ServiceSettings } from './api'

export const authTypes: ReadonlyArray<{ value: AuthType; label: string }> = [
  { value: 'NONE', label: 'Không' },
  { value: 'BEARER', label: 'Bearer' },
  { value: 'BASIC', label: 'Basic' },
  { value: 'API_KEY', label: 'API Key' },
  { value: 'OAUTH2_CLIENT', label: 'OAuth2 client' },
  { value: 'MTLS', label: 'mTLS' },
  { value: 'BEARER_VARIABLE', label: 'Bearer từ biến' },
  { value: 'HMAC', label: 'HMAC' },
  { value: 'AWS_SIG_V4', label: 'AWS SigV4' },
]

export const SERVICE_KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/

/** The editable part: base URL and the credentials shared by the service's operations. */
export interface EnvironmentForm {
  serviceKey: string
  baseUrl: string
  authType: AuthType
  apiKeyHeader: string
  apiKeyLocation: 'HEADER' | 'QUERY'
  tokenUrl: string
  clientId: string
  scopes: string
  bearerVariable: string
  region: string
  awsService: string
  signatureHeader: string
  /** Secrets: empty keeps the stored value. */
  secret: string
  certificatePem: string
  accessKey: string
}

export function emptyForm(): EnvironmentForm {
  return {
    serviceKey: '',
    baseUrl: '',
    authType: 'NONE',
    apiKeyHeader: '',
    apiKeyLocation: 'HEADER',
    tokenUrl: '',
    clientId: '',
    scopes: '',
    bearerVariable: '',
    region: '',
    awsService: '',
    signatureHeader: '',
    secret: '',
    certificatePem: '',
    accessKey: '',
  }
}

export function toForm(entry: ServiceEnvironment): EnvironmentForm {
  const auth = entry.settings.sharedAuth
  const details = auth.details ?? {}
  return {
    ...emptyForm(),
    serviceKey: entry.serviceKey,
    baseUrl: entry.settings.baseUrl ?? '',
    authType: auth.type,
    apiKeyHeader: auth.apiKeyHeader ?? '',
    apiKeyLocation: auth.apiKeyLocation ?? 'HEADER',
    tokenUrl: details.tokenUrl ?? '',
    clientId: details.clientId ?? '',
    scopes: (details.scopes ?? []).join(' '),
    bearerVariable: details.bearerVariable ?? '',
    region: details.region ?? '',
    awsService: details.awsService ?? '',
    signatureHeader: details.signatureHeader ?? '',
  }
}

const text = (value: string) => value.trim() || null

/**
 * Settings to save. Operation credentials are sent back unchanged (their secrets are null, so Flow Engine
 * keeps them); empty secret fields keep the stored shared secrets.
 */
export function toSettings(form: EnvironmentForm, current?: ServiceEnvironment): ServiceSettings {
  return {
    baseUrl: text(form.baseUrl),
    sharedAuth: {
      type: form.authType,
      apiKeyHeader: form.authType === 'API_KEY' ? text(form.apiKeyHeader) : null,
      apiKeyLocation: form.apiKeyLocation,
      secret: text(form.secret),
      details: {
        tokenUrl: text(form.tokenUrl),
        clientId: text(form.clientId),
        scopes: form.scopes.split(/[\s,]+/).filter(Boolean),
        certificatePem: text(form.certificatePem),
        bearerVariable: text(form.bearerVariable),
        accessKey: text(form.accessKey),
        region: text(form.region),
        awsService: text(form.awsService),
        signatureHeader: text(form.signatureHeader),
      },
    },
    operations: current?.settings.operations ?? {},
  }
}

/** First problem with the form, or null. */
export function validate(form: EnvironmentForm): string | null {
  if (!SERVICE_KEY_PATTERN.test(form.serviceKey.trim())) return 'Mã dịch vụ không hợp lệ'
  const baseUrl = form.baseUrl.trim()
  if (baseUrl && !/^https?:\/\//.test(baseUrl)) {
    return 'Base URL phải bắt đầu bằng http:// hoặc https://'
  }
  if (form.authType === 'API_KEY' && !form.apiKeyHeader.trim()) return 'Hãy nhập tên header API key'
  if (form.authType === 'BEARER_VARIABLE' && !form.bearerVariable.trim()) {
    return 'Hãy nhập biến chứa Bearer'
  }
  if (form.authType === 'OAUTH2_CLIENT' && (!form.tokenUrl.trim() || !form.clientId.trim())) {
    return 'Hãy nhập URL token và client id'
  }
  if (form.authType === 'HMAC' && !form.signatureHeader.trim()) return 'Hãy nhập header chữ ký'
  if (form.authType === 'AWS_SIG_V4' && !form.region.trim()) return 'Hãy nhập region của AWS'
  return null
}

export function secretLabel(authType: AuthType) {
  switch (authType) {
    case 'BASIC':
      return 'Username:password'
    case 'OAUTH2_CLIENT':
      return 'Client secret'
    case 'MTLS':
      return 'Khóa riêng PEM'
    case 'HMAC':
      return 'Khóa ký'
    case 'AWS_SIG_V4':
      return 'Secret key'
    default:
      return 'Khóa'
  }
}
