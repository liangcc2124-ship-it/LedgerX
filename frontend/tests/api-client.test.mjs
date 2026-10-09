import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiClientError, requestApiJson } from '../src/apiClient.js';

function fakeResponse(status, body, headers = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    text: async () => body,
  };
}

test('requestApiJson sends relative JSON requests with same-origin browser CSRF', async () => {
  let call;
  const result = await requestApiJson('/api/v1/example', {
    method: 'POST',
    body: { value: 'x' },
    ifMatch: '"2"',
    idempotencyKey: 'b225f659-299d-4391-86d6-d21465b931da',
    fetchImpl: async (path, options) => {
      if (path === '/api/v1/system/session') {
        return fakeResponse(200, JSON.stringify({ data: { authMode: 'browser', csrfToken: 'B'.repeat(43) } }));
      }
      call = { path, options };
      return fakeResponse(201, JSON.stringify({ data: { id: 'x' }, meta: { dataRevision: 1 } }), {
        ETag: '"0"',
        Location: '/api/v1/example/x',
        'X-Request-Id': 'b4d2f8b2-9a2b-4a4e-8d6c-7d5b6c4e3f21',
      });
    },
  });

  assert.equal(call.path, '/api/v1/example');
  assert.equal(call.options.method, 'POST');
  assert.deepEqual(JSON.parse(call.options.body), { value: 'x' });
  assert.match(call.options.headers['X-Request-Id'], /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(call.options.headers.Accept, 'application/json');
  assert.equal(call.options.headers['If-Match'], '"2"');
  assert.equal(call.options.headers['Idempotency-Key'], 'b225f659-299d-4391-86d6-d21465b931da');
  assert.equal(call.options.headers['X-LedgerX-CSRF'], 'B'.repeat(43));
  assert.equal(call.options.headers.Authorization, undefined);
  assert.equal(call.options.headers.Origin, undefined);
  assert.equal(call.options.credentials, 'same-origin');
  assert.equal(result.payload.data.id, 'x');
  assert.equal(result.etag, '"0"');
  assert.equal(result.location, '/api/v1/example/x');
});

test('requestApiJson rejects a legacy desktop session response', async () => {
  const legacyApi = await import('../src/apiClient.js?reject-legacy-session-test');
  await assert.rejects(
    () => legacyApi.requestApiJson('/api/v1/example', {
      fetchImpl: async () => fakeResponse(200, JSON.stringify({ data: { authMode: 'legacy' } })),
    }),
    (error) => error?.name === 'ApiClientError' && error.code === 'INVALID_RESPONSE',
  );
});

test('browser mutations bootstrap once and carry only the in-memory CSRF header', async () => {
  const browserApi = await import('../src/apiClient.js?browser-session-test');
  let bootstrapCalls = 0;
  const mutationCalls = [];
  const fetchImpl = async (path, options) => {
    if (path === '/api/v1/system/session') {
      bootstrapCalls += 1;
      return fakeResponse(200, JSON.stringify({ data: { authMode: 'browser', csrfToken: 'A'.repeat(43) } }));
    }
    mutationCalls.push({ path, options });
    return fakeResponse(200, JSON.stringify({ data: { ok: true }, meta: { dataRevision: 1 } }));
  };

  await Promise.all([
    browserApi.requestApiJson('/api/v1/example', { method: 'POST', body: {}, fetchImpl }),
    browserApi.requestApiJson('/api/v1/example', { method: 'POST', body: {}, fetchImpl }),
  ]);

  assert.equal(bootstrapCalls, 1);
  assert.equal(mutationCalls.length, 2);
  for (const call of mutationCalls) {
    assert.equal(call.options.credentials, 'same-origin');
    assert.equal(call.options.headers['X-LedgerX-CSRF'], 'A'.repeat(43));
    assert.equal(call.options.headers.Authorization, undefined);
  }
});

test('requestApiJson preserves structured API errors and rejects non-api paths', async () => {
  await assert.rejects(
    () => requestApiJson('http://127.0.0.1:1/api/v1/example', { fetchImpl: async () => fakeResponse(200, '{}') }),
    TypeError,
  );

  await assert.rejects(
    () => requestApiJson('/api/v1/example', {
      fetchImpl: async () => fakeResponse(409, JSON.stringify({ error: {
        code: 'REVISION_CONFLICT',
        message: '数据已变化。',
        retryable: false,
        fieldErrors: { name: '名称已变化。' },
        details: { currentRevision: 4 },
      } })),
    }),
    (error) => {
      assert.ok(error instanceof ApiClientError);
      assert.equal(error.status, 409);
      assert.equal(error.code, 'REVISION_CONFLICT');
      assert.equal(error.retryable, false);
      assert.equal(error.fieldErrors.name, '名称已变化。');
      assert.equal(error.details.currentRevision, 4);
      return true;
    },
  );
});

test('requestApiJson distinguishes malformed responses and cancellation', async () => {
  await assert.rejects(
    () => requestApiJson('/api/v1/example', { fetchImpl: async () => fakeResponse(200, '{') }),
    (error) => error instanceof ApiClientError && error.code === 'INVALID_RESPONSE',
  );

  await assert.rejects(
    () => requestApiJson('/api/v1/example', {
      fetchImpl: async () => { throw Object.assign(new Error('aborted'), { name: 'AbortError' }); },
    }),
    (error) => error instanceof ApiClientError && error.code === 'REQUEST_ABORTED',
  );
});

test('createBackup sends an empty body with CSRF and a stable idempotency key', async () => {
  const backupApi = await import('../src/apiClient.js?backup-create-test');
  let call;
  await backupApi.createBackup({
    idempotencyKey: '9fc1df09-f1d3-4c9b-97be-f592f65d0c29',
    fetchImpl: async (path, options) => {
      if (path === '/api/v1/system/session') {
        return fakeResponse(200, JSON.stringify({ data: { authMode: 'browser', csrfToken: 'C'.repeat(43) } }));
      }
      call = { path, options };
      return fakeResponse(201, JSON.stringify({ data: { backup: { id: '9fc1df09-f1d3-4c9b-97be-f592f65d0c29' } }, meta: { dataRevision: 1 } }));
    },
  });
  assert.equal(call.path, '/api/v1/backups');
  assert.equal(call.options.method, 'POST');
  assert.deepEqual(JSON.parse(call.options.body), {});
  assert.equal(call.options.headers['Idempotency-Key'], '9fc1df09-f1d3-4c9b-97be-f592f65d0c29');
  assert.equal(call.options.headers['X-LedgerX-CSRF'], 'C'.repeat(43));
  assert.equal(call.options.credentials, 'same-origin');
});

test('downloadBackup accepts only the versioned backup stream and uses browser session cookies', async () => {
  const backupApi = await import('../src/apiClient.js?backup-download-test');
  const packageBytes = new Blob(['PK\x03\x04backup']);
  let call;
  const result = await backupApi.downloadBackup('9fc1df09-f1d3-4c9b-97be-f592f65d0c29', {
    fetchImpl: async (path, options) => {
      if (path === '/api/v1/system/session') {
        return fakeResponse(200, JSON.stringify({ data: { authMode: 'browser', csrfToken: 'D'.repeat(43) } }));
      }
      call = { path, options };
      return {
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/vnd.ledgerx.backup+zip' }),
        blob: async () => packageBytes,
      };
    },
  });
  assert.equal(call.path, '/api/v1/backups/9fc1df09-f1d3-4c9b-97be-f592f65d0c29/download');
  assert.equal(call.options.method, 'GET');
  assert.equal(call.options.headers.Accept, 'application/vnd.ledgerx.backup+zip');
  assert.equal(call.options.credentials, 'same-origin');
  assert.equal(call.options.headers['X-LedgerX-CSRF'], undefined);
  assert.equal(result.blob, packageBytes);
  assert.equal(result.fileName, '9fc1df09-f1d3-4c9b-97be-f592f65d0c29.ledgerx-backup');

  await assert.rejects(() => backupApi.downloadBackup('9fc1df09-f1d3-4c9b-97be-f592f65d0c29', {
    fetchImpl: async (path) => path === '/api/v1/system/session'
      ? fakeResponse(200, JSON.stringify({ data: { authMode: 'browser', csrfToken: 'E'.repeat(43) } }))
      : { ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }), blob: async () => new Blob(['{}']) },
  }), (error) => error?.name === 'ApiClientError' && error.code === 'INVALID_RESPONSE');
});
