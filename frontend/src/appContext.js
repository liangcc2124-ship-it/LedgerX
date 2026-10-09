import { readonly, ref } from 'vue';

export const APP_CONTEXT_KEY = Symbol('ledgerx-app-context');

export function createAppContext() {
  const activeProfileId = ref(null);
  const dataRevision = ref(null);
  const capabilities = ref([]);
  const contextEpoch = ref(0);

  function applyProfileStatus(status) {
    const data = status?.data || status || {};
    const nextProfileId = data.activeProfileId ?? null;
    if (activeProfileId.value !== null && activeProfileId.value !== nextProfileId) contextEpoch.value += 1;
    activeProfileId.value = nextProfileId;
    dataRevision.value = status?.meta?.dataRevision ?? data.dataRevision ?? null;
    capabilities.value = Array.isArray(data.capabilities) ? [...data.capabilities] : [];
  }

  function commitMutation(meta) {
    if (meta && Object.prototype.hasOwnProperty.call(meta, 'dataRevision')) {
      dataRevision.value = meta.dataRevision;
    }
  }

  return {
    activeProfileId: readonly(activeProfileId),
    dataRevision: readonly(dataRevision),
    capabilities: readonly(capabilities),
    contextEpoch: readonly(contextEpoch),
    commitMutation,
    applyProfileStatus,
  };
}

