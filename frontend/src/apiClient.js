const STATUS_PATH = '/api/v1/system/status';
const API_MAJOR = '1';
const STATUS_STATES = new Set(['STARTING', 'READY', 'RECOVERY_REQUIRED']);
const PROFILE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export class ApiClientError extends Error {
  constructor(message, {
    status = 0,
    code = 'SERVICE_UNAVAILABLE',
    retryable = true,
    requestId = null,
    fieldErrors = {},
    details = {},
  } = {}) {
    super(message);
    this.name = 'ApiClientError';
    this.status = status;
    this.code = code;
    this.retryable = retryable;
    this.requestId = requestId;
    this.fieldErrors = isRecord(fieldErrors) ? fieldErrors : {};
    this.details = isRecord(details) ? details : {};
  }
}

export function createRequestId() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function apiErrorFromPayload(payload, response, requestId, fallbackMessage) {
  const apiError = isRecord(payload) && isRecord(payload.error) ? payload.error : {};
  return new ApiClientError(apiError.message || fallbackMessage, {
    status: response.status,
    code: apiError.code || (response.status === 401 ? 'AUTHENTICATION_REQUIRED' : 'SERVICE_UNAVAILABLE'),
    retryable: apiError.retryable !== false,
    requestId: apiError.requestId || requestId,
    fieldErrors: apiError.fieldErrors,
    details: apiError.details,
  });
}

function requireRelativeApiPath(path) {
  if (typeof path !== 'string' || !path.startsWith('/api/v1/')) {
    throw new TypeError('API path must be a relative /api/v1/ path');
  }
  return path;
}

function responseRequestId(response, requestId) {
  return response.headers.get('X-Request-Id') || requestId;
}

async function parseJsonResponse(response, requestId) {
  const actualRequestId = responseRequestId(response, requestId);
  let text;
  try {
    text = await response.text();
  } catch (error) {
    throw new ApiClientError('本地服务返回了无法读取的响应，请重试。', {
      status: response.status,
      code: 'INVALID_RESPONSE',
      requestId: actualRequestId,
    });
  }

  let payload;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch (error) {
    throw new ApiClientError('本地服务返回了无法读取的响应，请重试。', {
      status: response.status,
      code: 'INVALID_RESPONSE',
      requestId: actualRequestId,
    });
  }

  if (!response.ok) {
    throw apiErrorFromPayload(payload, response, actualRequestId, '本地服务暂时不可用，请重试。');
  }
  if (!isRecord(payload)) {
    throw new ApiClientError('本地服务返回了无法读取的响应，请重试。', {
      status: response.status,
      code: 'INVALID_RESPONSE',
      requestId: actualRequestId,
    });
  }
  return {
    payload,
    etag: response.headers.get('ETag'),
    location: response.headers.get('Location'),
    requestId: actualRequestId,
  };
}

/**
 * The only generic JSON request entry point for business API pages.
 * Authentication is deliberately supplied by the Electron session or the
 * Node-only Vite proxy, never by renderer code.
 */
export async function requestApiJson(path, {
  method = 'GET',
  body,
  ifMatch,
  idempotencyKey,
  signal,
  fetchImpl = globalThis.fetch,
} = {}) {
  const requestPath = requireRelativeApiPath(path);
  const normalizedMethod = typeof method === 'string' ? method.toUpperCase() : '';
  if (!['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(normalizedMethod)) {
    throw new TypeError('API method is not supported');
  }
  if (typeof fetchImpl !== 'function') {
    throw new ApiClientError('无法连接本地服务，请重试。');
  }

  const requestId = createRequestId();
  const headers = {
    Accept: 'application/json',
    'X-Request-Id': requestId,
  };
  const requestOptions = {
    method: normalizedMethod,
    headers,
    credentials: 'omit',
    signal,
  };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    requestOptions.body = JSON.stringify(body);
  }
  if (ifMatch !== undefined && ifMatch !== null) {
    headers['If-Match'] = String(ifMatch);
  }
  if (idempotencyKey !== undefined && idempotencyKey !== null) {
    headers['Idempotency-Key'] = String(idempotencyKey);
  }

  let response;
  try {
    response = await fetchImpl(requestPath, requestOptions);
  } catch (error) {
    if (error && error.name === 'AbortError') {
      throw new ApiClientError('请求已取消。', {
        code: 'REQUEST_ABORTED',
        retryable: true,
        requestId,
      });
    }
    throw new ApiClientError('无法连接本地服务，请重试。', { requestId });
  }
  return parseJsonResponse(response, requestId);
}

function isValidStatusPayload(payload) {
  if (!isRecord(payload) || !isRecord(payload.data) || !isRecord(payload.meta)) {
    return false;
  }

  const { data } = payload;
  if (typeof data.apiVersion !== 'string' || data.apiVersion.split('.', 1)[0] !== API_MAJOR
      || typeof data.applicationVersion !== 'string' || data.applicationVersion.trim() === ''
      || !Number.isInteger(data.backupFormatVersion) || data.backupFormatVersion < 1
      || !STATUS_STATES.has(data.state)
      || !Array.isArray(data.capabilities) || !data.capabilities.includes('system.status')) {
    return false;
  }

  const dataRevision = payload.meta.dataRevision;
  if (data.state === 'READY') {
    return Number.isInteger(data.schemaVersion) && data.schemaVersion > 0
      && typeof data.activeProfileId === 'string'
      && PROFILE_ID_PATTERN.test(data.activeProfileId)
      && Number.isSafeInteger(dataRevision) && dataRevision >= 0;
  }

  return data.schemaVersion === null && data.activeProfileId === null && dataRevision === null;
}

export async function getSystemStatus({ fetchImpl = globalThis.fetch, signal } = {}) {
  const requestId = createRequestId();
  let response;
  try {
    response = await fetchImpl(STATUS_PATH, {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        'X-Request-Id': requestId,
      },
      credentials: 'omit',
      signal,
    });
  } catch (error) {
    if (error && error.name === 'AbortError') {
      throw error;
    }
    throw new ApiClientError('无法连接本地服务，请重试。', { requestId });
  }

  const responseRequestId = response.headers.get('X-Request-Id') || requestId;
  const text = await response.text();
  let payload;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch (error) {
    throw new ApiClientError('本地服务返回了无法读取的响应，请重试。', {
      status: response.status,
      code: 'INVALID_RESPONSE',
      requestId: responseRequestId,
    });
  }

  if (!response.ok) {
    const apiError = payload && payload.error ? payload.error : {};
    throw new ApiClientError(apiError.message || '本地服务暂时不可用，请重试。', {
      status: response.status,
      code: apiError.code || (response.status === 401 ? 'AUTHENTICATION_REQUIRED' : 'SERVICE_UNAVAILABLE'),
      retryable: apiError.retryable !== false,
      requestId: apiError.requestId || responseRequestId,
    });
  }

  if (!isValidStatusPayload(payload)) {
    throw new ApiClientError('本地服务返回了不兼容的状态，请重试。', {
      status: response.status,
      code: 'INVALID_RESPONSE',
      requestId: responseRequestId,
    });
  }
  return payload;
}

export { isValidStatusPayload };
