const STATUS_PATH = '/api/v1/system/status';
const API_MAJOR = '1';
const STATUS_STATES = new Set(['STARTING', 'READY', 'RECOVERY_REQUIRED']);
const SETUP_STATES = new Set(['PENDING', 'REVIEW_REQUIRED', 'COMPLETED']);
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const PROFILE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const CSRF_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const SESSION_PATH = '/api/v1/system/session';
let activeSession = null;
let sessionBootstrap = null;

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

function clearSession() {
  activeSession = null;
}

async function ensureSession({ fetchImpl, signal }) {
  if (activeSession) return activeSession;
  if (sessionBootstrap) return sessionBootstrap;
  const requestId = createRequestId();
  sessionBootstrap = (async () => {
    let response;
    try {
      response = await fetchImpl(SESSION_PATH, {
        method: 'GET',
        headers: { Accept: 'application/json', 'X-Request-Id': requestId },
        credentials: 'same-origin',
        signal,
      });
    } catch (error) {
      if (error && error.name === 'AbortError') throw error;
      throw new ApiClientError('无法连接本地服务，请重试。', { requestId });
    }
    const { payload } = await parseJsonResponse(response, requestId);
    const data = payload?.data;
    if (!isRecord(data) || data.authMode !== 'browser'
        || !CSRF_TOKEN_PATTERN.test(data.csrfToken || '')) {
      throw new ApiClientError('本地服务返回了无效的会话信息，请重新打开应用。', {
        status: response.status,
        code: 'INVALID_RESPONSE',
        requestId: responseRequestId(response, requestId),
      });
    }
    activeSession = { csrfToken: data.csrfToken };
    return activeSession;
  })();
  try {
    return await sessionBootstrap;
  } finally {
    sessionBootstrap = null;
  }
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
 * Browser requests use a same-origin HttpOnly session cookie and in-memory
 * CSRF token. Desktop-shell authentication is not part of the active web client.
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

  const session = await ensureSession({ fetchImpl, signal });

  const requestId = createRequestId();
  const headers = {
    Accept: 'application/json',
    'X-Request-Id': requestId,
  };
  const requestOptions = {
    method: normalizedMethod,
    headers,
    credentials: 'same-origin',
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
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(normalizedMethod)) {
    headers['X-LedgerX-CSRF'] = session.csrfToken;
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
  try {
    return await parseJsonResponse(response, requestId);
  } catch (error) {
    if (error instanceof ApiClientError && (error.status === 401 || error.code === 'INVALID_CSRF_TOKEN')) {
      clearSession();
    }
    throw error;
  }
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
      && SETUP_STATES.has(data.setupState)
      && (data.setupState === 'COMPLETED'
        ? typeof data.ledgerStartOn === 'string' && ISO_DATE_PATTERN.test(data.ledgerStartOn)
        : data.ledgerStartOn === null)
      && Number.isSafeInteger(dataRevision) && dataRevision >= 0;
  }

  return data.schemaVersion === null && data.activeProfileId === null && dataRevision === null
    && data.setupState === null && data.ledgerStartOn === null;
}

export async function getSystemStatus({ fetchImpl = globalThis.fetch, signal } = {}) {
  await ensureSession({ fetchImpl, signal });
  const requestId = createRequestId();
  let response;
  try {
    response = await fetchImpl(STATUS_PATH, {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        'X-Request-Id': requestId,
      },
      credentials: 'same-origin',
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
    if (response.status === 401 || apiError.code === 'INVALID_CSRF_TOKEN') clearSession();
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

function queryValue(value) {
  return encodeURIComponent(String(value));
}

export async function getMetrics({ includeArchived = false, fetchImpl = globalThis.fetch, signal } = {}) {
  const status = includeArchived ? 'ARCHIVED' : 'ACTIVE';
  return requestApiJson(`/api/v1/metrics?status=${status}&limit=200`, { fetchImpl, signal });
}

export async function getLedgerInitialization({ fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/ledger-initialization', { fetchImpl, signal });
}

export async function initializeLedger(body, { idempotencyKey = createRequestId(), fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/ledger-initialization', {
    method: 'POST', body, idempotencyKey, fetchImpl, signal,
  });
}

export async function getMetric(id, { includeArchived = false, fetchImpl = globalThis.fetch, signal } = {}) {
  const suffix = includeArchived ? '?status=ARCHIVED' : '';
  return requestApiJson(`/api/v1/metrics/${encodeURIComponent(id)}${suffix}`, { fetchImpl, signal });
}

export async function createMetric(body, { idempotencyKey = createRequestId(), fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/metrics', { method: 'POST', body, idempotencyKey, fetchImpl, signal });
}

export async function updateMetric(id, body, { ifMatch, idempotencyKey = createRequestId(), fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson(`/api/v1/metrics/${encodeURIComponent(id)}`, {
    method: 'PUT', body, ifMatch, idempotencyKey, fetchImpl, signal,
  });
}

export async function archiveMetric(id, { ifMatch, idempotencyKey = createRequestId(), fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson(`/api/v1/metrics/${encodeURIComponent(id)}`, {
    method: 'DELETE', ifMatch, idempotencyKey, fetchImpl, signal,
  });
}

export async function getDashboard({ granularity, anchor, fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson(`/api/v1/dashboard?granularity=${queryValue(granularity)}&anchor=${queryValue(anchor)}`, { fetchImpl, signal });
}

export async function getDashboardLayout({ fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/dashboard/layout', { fetchImpl, signal });
}

export async function replaceDashboardLayout(items, { ifMatch, idempotencyKey = createRequestId(), fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/dashboard/layout', {
    method: 'PUT', body: { items }, ifMatch, idempotencyKey, fetchImpl, signal,
  });
}

export async function resetDashboardLayout({ ifMatch, idempotencyKey = createRequestId(), fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/dashboard/layout/reset', {
    method: 'POST', body: {}, ifMatch, idempotencyKey, fetchImpl, signal,
  });
}

export async function validateFormula(body, { fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/formulas/validate', { method: 'POST', body, fetchImpl, signal });
}

export async function previewFormula(body, { fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/formulas/preview', { method: 'POST', body, fetchImpl, signal });
}

export async function listFormulaVersions(formulaId, { limit = 50, cursor, fetchImpl = globalThis.fetch, signal } = {}) {
  const cursorQuery = cursor ? `&cursor=${queryValue(cursor)}` : '';
  return requestApiJson(`/api/v1/formulas/${encodeURIComponent(formulaId)}/versions?limit=${limit}${cursorQuery}`, { fetchImpl, signal });
}

export async function createBackup({ idempotencyKey = createRequestId(), fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson('/api/v1/backups', {
    method: 'POST', body: {}, idempotencyKey, fetchImpl, signal,
  });
}

export async function listBackups({ limit = 25, cursor, fetchImpl = globalThis.fetch, signal } = {}) {
  const cursorQuery = cursor ? `&cursor=${queryValue(cursor)}` : '';
  return requestApiJson(`/api/v1/backups?limit=${encodeURIComponent(limit)}${cursorQuery}`, { fetchImpl, signal });
}

export async function verifyBackup(id, { fetchImpl = globalThis.fetch, signal } = {}) {
  return requestApiJson(`/api/v1/backups/${encodeURIComponent(id)}/verify`, { fetchImpl, signal });
}

export async function downloadBackup(id, { fetchImpl = globalThis.fetch, signal } = {}) {
  const requestPath = `/api/v1/backups/${encodeURIComponent(id)}/download`;
  await ensureSession({ fetchImpl, signal });
  const requestId = createRequestId();
  let response;
  try {
    response = await fetchImpl(requestPath, {
      method: 'GET',
      headers: {
        Accept: 'application/vnd.ledgerx.backup+zip',
        'X-Request-Id': requestId,
      },
      credentials: 'same-origin',
      signal,
    });
  } catch (error) {
    if (error && error.name === 'AbortError') throw error;
    throw new ApiClientError('无法连接本地服务，请重试。', { requestId });
  }

  if (!response.ok) {
    try {
      await parseJsonResponse(response, requestId);
    } catch (error) {
      if (error instanceof ApiClientError && (error.status === 401 || error.code === 'INVALID_CSRF_TOKEN')) {
        clearSession();
      }
      throw error;
    }
    throw new ApiClientError('本地服务暂时不可用，请重试。', {
      status: response.status,
      requestId: responseRequestId(response, requestId),
    });
  }

  const contentType = (response.headers.get('Content-Type') || '').split(';', 1)[0].trim().toLowerCase();
  if (contentType !== 'application/vnd.ledgerx.backup+zip') {
    throw new ApiClientError('本地服务返回了无法识别的备份文件，请重试。', {
      status: response.status,
      code: 'INVALID_RESPONSE',
      retryable: false,
      requestId: responseRequestId(response, requestId),
    });
  }

  try {
    return {
      blob: await response.blob(),
      fileName: `${id}.ledgerx-backup`,
      requestId: responseRequestId(response, requestId),
    };
  } catch (error) {
    throw new ApiClientError('备份文件下载不完整，请重试。', {
      status: response.status,
      code: 'INVALID_RESPONSE',
      requestId: responseRequestId(response, requestId),
    });
  }
}

export { isValidStatusPayload };
