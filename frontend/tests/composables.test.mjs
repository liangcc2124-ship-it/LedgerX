import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiClientError } from '../src/apiClient.js';
import { useApiMutation } from '../src/composables/useApiMutation.js';
import { useAsyncResource } from '../src/composables/useAsyncResource.js';

test('useAsyncResource lets only the latest replace response update state', async () => {
  const resolvers = new Map();
  const resource = useAsyncResource(({ input }) => new Promise((resolve) => {
    resolvers.set(input.value, resolve);
  }));
  const first = resource.execute({ input: { value: 'old', queryKey: 'q1' } });
  const second = resource.execute({ input: { value: 'new', queryKey: 'q2' } });
  resolvers.get('old')({ items: ['old'] });
  resolvers.get('new')({ items: ['new'] });
  await Promise.all([first, second]);
  assert.deepEqual(resource.data.value, { items: ['new'] });
  assert.equal(resource.state.value, 'success');
});

test('useApiMutation timeout keeps a frozen same-key request for retry', async () => {
  const calls = [];
  let resolveSecond;
  const request = (path, options) => {
    calls.push({ path, options });
    if (calls.length === 1) return new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
    });
    return new Promise((resolve) => { resolveSecond = () => resolve({ payload: { data: { ok: true } } }); });
  };
  const mutation = useApiMutation({ timeoutMs: 10, request });
  const intent = { method: 'POST', path: '/api/v1/profiles', body: { name: '家庭账本' }, idempotencyKey: 'same-key' };
  await assert.rejects(mutation.submit(intent), (error) => error.code === 'MUTATION_PENDING');
  assert.equal(mutation.state.value, 'pending-confirmation');
  assert.equal(Object.isFrozen(mutation.pendingRequest.value), true);
  const retry = mutation.retrySame();
  resolveSecond();
  await retry;
  assert.equal(calls.length, 2);
  assert.equal(calls[0].path, calls[1].path);
  assert.equal(calls[0].options.method, calls[1].options.method);
  assert.deepEqual(calls[0].options.body, calls[1].options.body);
  assert.equal(calls[0].options.idempotencyKey, calls[1].options.idempotencyKey);
  assert.notEqual(calls[0].options.signal, calls[1].options.signal);
});

test('useApiMutation keeps validation and conflict states distinct', async () => {
  const validation = useApiMutation({ request: async () => { throw new ApiClientError('invalid', { status: 400, code: 'VALIDATION_FAILED', fieldErrors: { name: '重复' } }); } });
  await assert.rejects(validation.submit({ method: 'POST', path: '/api/v1/profiles', body: {}, idempotencyKey: 'validation-key' }));
  assert.equal(validation.state.value, 'validation-error');
  assert.deepEqual(validation.error.value.fieldErrors, { name: '重复' });

  const conflict = useApiMutation({ request: async () => { throw new ApiClientError('conflict', { status: 409, code: 'REVISION_CONFLICT' }); } });
  await assert.rejects(conflict.submit({ method: 'PUT', path: '/api/v1/profiles/id', body: {}, ifMatch: '"1"', idempotencyKey: 'conflict-key' }));
  assert.equal(conflict.state.value, 'conflict');
  assert.ok(conflict.pendingRequest.value);
});
