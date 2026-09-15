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

test('requestApiJson sends relative JSON requests without renderer credentials', async () => {
  let call;
  const result = await requestApiJson('/api/v1/example', {
    method: 'POST',
    body: { value: 'x' },
    ifMatch: '"2"',
    idempotencyKey: 'b225f659-299d-4391-86d6-d21465b931da',
    fetchImpl: async (path, options) => {
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
  assert.equal(call.options.headers.Authorization, undefined);
  assert.equal(call.options.headers.Origin, undefined);
  assert.equal(result.payload.data.id, 'x');
  assert.equal(result.etag, '"0"');
  assert.equal(result.location, '/api/v1/example/x');
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
