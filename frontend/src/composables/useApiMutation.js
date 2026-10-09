import { getCurrentInstance, onBeforeUnmount, ref, toRaw } from 'vue';
import { ApiClientError, requestApiJson } from '../apiClient.js';

function clone(value) {
  const raw = toRaw(value);
  if (raw === undefined || raw === null || typeof raw !== 'object') return raw;
  if (Array.isArray(raw)) return raw.map(clone);
  return Object.fromEntries(Object.entries(raw).map(([key, item]) => [key, clone(item)]));
}

function freeze(value) {
  if (!value || typeof value !== 'object' || Object.isFrozen(value)) return value;
  Object.freeze(value);
  Object.values(value).forEach(freeze);
  return value;
}

function normalizeRequest(request) {
  if (!request || typeof request !== 'object' || typeof request.path !== 'string') {
    throw new TypeError('mutation request requires a path');
  }
  const snapshot = clone({
    method: request.method || 'GET',
    path: request.path,
    body: request.body,
    ifMatch: request.ifMatch,
    idempotencyKey: request.idempotencyKey,
  });
  return freeze(snapshot);
}

function errorForTimeout() {
  return new ApiClientError('提交仍在处理中，请查询结果或使用相同操作重试。', {
    code: 'MUTATION_PENDING',
    retryable: true,
  });
}

export function useApiMutation({ timeoutMs = 15_000, request = requestApiJson } = {}) {
  const state = ref('idle');
  const pendingRequest = ref(null);
  const error = ref(null);
  const result = ref(null);
  let controller = null;
  let unmounted = false;

  async function execute(snapshot) {
    controller?.abort();
    const requestController = new AbortController();
    controller = requestController;
    state.value = 'submitting';
    error.value = null;
    let timedOut = false;
    const timeout = setTimeout(() => {
      timedOut = true;
      requestController.abort();
    }, timeoutMs);
    try {
      const response = await request(snapshot.path, {
        method: snapshot.method,
        body: snapshot.body,
        ifMatch: snapshot.ifMatch,
        idempotencyKey: snapshot.idempotencyKey,
        signal: requestController.signal,
      });
      if (unmounted) return undefined;
      result.value = response;
      state.value = 'success';
      pendingRequest.value = null;
      return response;
    } catch (caught) {
      if (unmounted) return undefined;
      const normalized = timedOut ? errorForTimeout() : caught;
      error.value = normalized;
      if (normalized?.code === 'MUTATION_PENDING') state.value = 'pending-confirmation';
      else if (normalized?.status === 400 || normalized?.code === 'VALIDATION_FAILED' || normalized?.code === 'FORMULA_INVALID') state.value = 'validation-error';
      else if (normalized?.status === 409 || normalized?.status === 428 || normalized?.code === 'REVISION_CONFLICT' || normalized?.code === 'PRECONDITION_REQUIRED') state.value = 'conflict';
      else state.value = 'error';
      throw normalized;
    } finally {
      clearTimeout(timeout);
      if (controller === requestController) controller = null;
    }
  }

  async function submit(requestValue) {
    const snapshot = normalizeRequest(requestValue);
    pendingRequest.value = snapshot;
    return execute(snapshot);
  }

  async function retrySame() {
    if (!pendingRequest.value || !['pending-confirmation', 'conflict', 'error'].includes(state.value)) return undefined;
    return execute(pendingRequest.value);
  }

  async function queryOperation() {
    if (!pendingRequest.value?.idempotencyKey) return undefined;
    const response = await request(`/api/v1/operations/${encodeURIComponent(pendingRequest.value.idempotencyKey)}`);
    if (response.payload?.data?.status === 'COMPLETED') {
      result.value = response;
      state.value = 'success';
      pendingRequest.value = null;
    } else {
      state.value = 'pending-confirmation';
    }
    return response;
  }

  function clear() {
    controller?.abort();
    controller = null;
    pendingRequest.value = null;
    error.value = null;
    result.value = null;
    state.value = 'idle';
  }

  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      unmounted = true;
      controller?.abort();
      controller = null;
    });
  }

  return { state, pendingRequest, error, result, submit, retrySame, queryOperation, clear };
}
