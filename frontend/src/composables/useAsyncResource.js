import { getCurrentInstance, onBeforeUnmount, ref } from 'vue';
import { ApiClientError } from '../apiClient.js';

const RESOURCE_STATES = new Set(['idle', 'loading', 'loading-more', 'success', 'empty', 'error']);

function timeoutError() {
  return new ApiClientError('读取本地服务超时，请重试。', {
    code: 'REQUEST_TIMEOUT',
    retryable: true,
  });
}

/**
 * Small request lifecycle primitive. The caller owns URL construction and
 * projection; this composable only owns cancellation, generations and state.
 */
export function useAsyncResource(loader, { timeoutMs = 15_000 } = {}) {
  if (typeof loader !== 'function') throw new TypeError('loader must be a function');
  const state = ref('idle');
  const data = ref(null);
  const error = ref(null);
  let generation = 0;
  let controller = null;
  let activeQueryKey = null;
  let unmounted = false;

  function cancel() {
    generation += 1;
    controller?.abort();
    controller = null;
    if (!unmounted) state.value = 'idle';
  }

  async function execute({ mode = 'replace', input = null } = {}) {
    if (!['replace', 'append'].includes(mode)) throw new TypeError('mode must be replace or append');
    const queryKey = input && typeof input === 'object' && 'queryKey' in input ? input.queryKey : null;
    const actualMode = mode === 'append' && queryKey === activeQueryKey ? 'append' : 'replace';
    if (actualMode === 'replace') {
      generation += 1;
      controller?.abort();
      activeQueryKey = queryKey;
    }
    const requestGeneration = generation;
    const requestController = new AbortController();
    controller = requestController;
    state.value = actualMode === 'append' ? 'loading-more' : 'loading';
    error.value = null;
    let timedOut = false;
    const timeout = setTimeout(() => {
      timedOut = true;
      requestController.abort();
    }, timeoutMs);
    try {
      const result = await loader({ signal: requestController.signal, input, mode: actualMode });
      if (unmounted || requestGeneration !== generation) return result;
      data.value = result;
      state.value = Array.isArray(result?.items) && result.items.length === 0 ? 'empty' : 'success';
      return result;
    } catch (caught) {
      if (unmounted || requestGeneration !== generation) return undefined;
      if (timedOut) {
        error.value = timeoutError();
      } else if (caught?.code === 'REQUEST_ABORTED' || caught?.name === 'AbortError') {
        return undefined;
      } else {
        error.value = caught;
      }
      state.value = 'error';
      throw error.value;
    } finally {
      clearTimeout(timeout);
      if (controller === requestController) controller = null;
    }
  }

  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      unmounted = true;
      generation += 1;
      controller?.abort();
      controller = null;
    });
  }

  return { state, data, error, execute, cancel, states: RESOURCE_STATES };
}
