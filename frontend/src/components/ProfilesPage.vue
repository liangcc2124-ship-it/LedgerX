<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ApiClientError, createRequestId, requestApiJson } from '../apiClient.js';

const emit = defineEmits(['profile-activation-complete']);

const profiles = ref([]);
const activeProfileId = ref(null);
const includeArchived = ref(false);
const nextCursor = ref(null);
const hasMore = ref(false);
const readState = ref('loading');
const readError = ref('');
const announcement = ref('');
const profileName = ref('');
const nameError = ref('');
const confirmingArchiveId = ref(null);
const mutationState = ref({ kind: 'idle', action: '', message: '' });
const pendingMutation = ref(null);
const writesDisabled = ref(false);
const revisionConflict = ref(false);

let readGeneration = 0;
let readController = null;

const sortedProfiles = computed(() => profiles.value);

function statusLabel(status) {
  return {
    ACTIVE: '使用中',
    INACTIVE: '未使用',
    ARCHIVED: '已归档',
  }[status] || '未知状态';
}

function isUuid(value) {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function isProfile(value) {
  return value && typeof value === 'object'
    && isUuid(value.id)
    && typeof value.name === 'string'
    && value.name.length > 0
    && ['ACTIVE', 'INACTIVE', 'ARCHIVED'].includes(value.status)
    && typeof value.createdAt === 'string'
    && (value.lastOpenedAt === null || typeof value.lastOpenedAt === 'string')
    && Number.isInteger(value.revision)
    && value.revision >= 0;
}

function parseList(payload) {
  const data = payload && payload.data;
  const page = data && data.page;
  if (!data || !Array.isArray(data.items) || !data.items.every(isProfile)
      || !page || typeof page.hasMore !== 'boolean'
      || (page.nextCursor !== null && typeof page.nextCursor !== 'string')
      || !isUuid(data.activeProfileId)) {
    throw new ApiClientError('本地服务返回了无法读取的用户空间数据，请重试。', {
      code: 'INVALID_RESPONSE',
      retryable: false,
    });
  }
  return {
    items: data.items,
    activeProfileId: data.activeProfileId,
    nextCursor: page.nextCursor,
    hasMore: page.hasMore,
  };
}

function apiErrorMessage(error) {
  if (!(error instanceof ApiClientError)) return '本地服务暂时不可用，请重试。';
  if (error.status === 401 || error.code === 'AUTHENTICATION_REQUIRED') {
    return '登录状态已失效，请重新启动应用。';
  }
  if (error.status === 423) return '数据正在恢复，请稍后重试。';
  if (error.status === 409 || error.status === 428
      || error.code === 'REVISION_CONFLICT' || error.code === 'PRECONDITION_REQUIRED') {
    return '数据已变化，请刷新后重试。';
  }
  if (error.code === 'INVALID_RESPONSE') return '本地服务返回了不兼容的数据，请重试。';
  return error.message || '本地服务暂时不可用，请重试。';
}

function operationMessage(action) {
  return action === 'create' ? '创建用户空间' : action === 'activate' ? '切换用户空间' : '归档用户空间';
}

async function loadProfiles({ append = false } = {}) {
  const generation = ++readGeneration;
  if (readController) readController.abort();
  readController = new AbortController();
  const controller = readController;
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, 15000);
  readState.value = append ? 'loading-more' : 'loading';
  readError.value = '';
  const params = new URLSearchParams({ includeArchived: String(includeArchived.value), limit: '50' });
  if (append && nextCursor.value) params.set('cursor', nextCursor.value);
  try {
    const result = await requestApiJson(`/api/v1/profiles?${params.toString()}`, {
      signal: controller.signal,
    });
    if (generation !== readGeneration) return;
    const parsed = parseList(result.payload);
    if (!append && parsed.items.length === 0) {
      throw new ApiClientError('本地服务没有可用的用户空间，请恢复数据后重试。', {
        code: 'INVALID_RESPONSE',
        retryable: false,
      });
    }
    const knownIds = new Set(profiles.value.map((profile) => profile.id));
    const incoming = append ? parsed.items.filter((profile) => !knownIds.has(profile.id)) : parsed.items;
    profiles.value = append ? [...profiles.value, ...incoming] : incoming;
    activeProfileId.value = parsed.activeProfileId;
    nextCursor.value = parsed.nextCursor;
    hasMore.value = parsed.hasMore;
    writesDisabled.value = false;
    readState.value = 'ready';
    announcement.value = append ? `已加载 ${incoming.length} 个用户空间。` : `已加载 ${profiles.value.length} 个用户空间。`;
  } catch (error) {
    if (generation !== readGeneration) return;
    if (error.code === 'REQUEST_ABORTED' && !timedOut) return;
    readState.value = 'error';
    writesDisabled.value = error.status === 423;
    readError.value = timedOut ? '读取用户空间超时，请重试。' : apiErrorMessage(error);
  } finally {
    clearTimeout(timeout);
    if (readController === controller) readController = null;
  }
}

function changeArchivedFilter() {
  confirmingArchiveId.value = null;
  nextCursor.value = null;
  hasMore.value = false;
  profiles.value = [];
  loadProfiles();
}

function validateName() {
  nameError.value = '';
  const value = profileName.value.trim();
  const length = Array.from(value).length;
  if (length < 1) nameError.value = '请输入用户空间名称。';
  else if (length > 100) nameError.value = '用户空间名称不能超过 100 个字符。';
  return nameError.value === '';
}

function beginMutation(action, request) {
  const pending = { action, request };
  pendingMutation.value = pending;
  runMutation(pending);
}

function applyMutationProjection(action, payload) {
  const profile = payload && payload.data && payload.data.profile;
  if (!isProfile(profile)) return;
  if (action === 'create') {
    activeProfileId.value = profile.id;
    profiles.value = [profile, ...profiles.value.filter((item) => item.id !== profile.id)];
  } else if (action === 'activate') {
    activeProfileId.value = profile.id;
    profiles.value = profiles.value.map((item) => item.id === profile.id ? profile : item);
  } else if (action === 'archive') {
    profiles.value = includeArchived.value
      ? profiles.value.map((item) => item.id === profile.id ? profile : item)
      : profiles.value.filter((item) => item.id !== profile.id);
  }
}

async function runMutation(pending) {
  revisionConflict.value = false;
  mutationState.value = { kind: 'loading', action: pending.action, message: `${operationMessage(pending.action)}中…` };
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 30000);
  try {
    const result = await requestApiJson(pending.request.path, {
      method: pending.request.method,
      body: pending.request.body,
      ifMatch: pending.request.ifMatch,
      idempotencyKey: pending.request.idempotencyKey,
      signal: controller.signal,
    });
    clearTimeout(timeout);
    writesDisabled.value = false;
    applyMutationProjection(pending.action, result.payload);
    pendingMutation.value = null;
    mutationState.value = { kind: 'success', action: pending.action, message: `${operationMessage(pending.action)}成功。` };
    profileName.value = '';
    nameError.value = '';
    confirmingArchiveId.value = null;
    await loadProfiles();
    if (pending.action === 'activate' || pending.action === 'create') emit('profile-activation-complete', result.payload);
  } catch (error) {
    clearTimeout(timeout);
    if (error.status === 423) writesDisabled.value = true;
    revisionConflict.value = error.status === 409 || error.status === 428
      || error.code === 'REVISION_CONFLICT' || error.code === 'PRECONDITION_REQUIRED';
    mutationState.value = { kind: 'error', action: pending.action, message: error.code === 'REQUEST_ABORTED' ? '提交结果待确认：操作仍在处理中，请查询结果或稍后使用相同操作重试。' : apiErrorMessage(error) };
    if (error.fieldErrors && error.fieldErrors.name) nameError.value = String(error.fieldErrors.name);
  }
}

function refreshAfterConflict() {
  pendingMutation.value = null;
  revisionConflict.value = false;
  mutationState.value = { kind: 'idle', action: '', message: '' };
  loadProfiles();
}

function submitCreate() {
  if (!validateName() || mutationState.value.kind === 'loading' || writesDisabled.value) return;
  const name = profileName.value.trim();
  beginMutation('create', {
    method: 'POST',
    path: '/api/v1/profiles',
    body: { id: createRequestId(), name },
    idempotencyKey: createRequestId(),
  });
}

function activateProfile(profile) {
  if (profile.status !== 'INACTIVE' || mutationState.value.kind === 'loading' || writesDisabled.value) return;
  beginMutation('activate', {
    method: 'POST',
    path: `/api/v1/profiles/${profile.id}/activate`,
    body: {},
    ifMatch: `"${profile.revision}"`,
    idempotencyKey: createRequestId(),
  });
}

function askArchive(profile) {
  if (profile.status !== 'INACTIVE' || mutationState.value.kind === 'loading' || writesDisabled.value) return;
  confirmingArchiveId.value = confirmingArchiveId.value === profile.id ? null : profile.id;
}

function confirmArchive(profile) {
  if (confirmingArchiveId.value !== profile.id || writesDisabled.value) return;
  beginMutation('archive', {
    method: 'DELETE',
    path: `/api/v1/profiles/${profile.id}`,
    ifMatch: `"${profile.revision}"`,
    idempotencyKey: createRequestId(),
  });
}

function retryPending() {
  if (pendingMutation.value) runMutation(pendingMutation.value);
}

async function queryPendingOperation() {
  if (!pendingMutation.value) return;
  const key = pendingMutation.value.request.idempotencyKey;
  mutationState.value = { kind: 'loading', action: pendingMutation.value.action, message: '正在查询操作结果…' };
  try {
    const result = await requestApiJson(`/api/v1/operations/${encodeURIComponent(key)}`);
    const status = result.payload && result.payload.data && result.payload.data.status;
    if (status === 'COMPLETED') {
      const action = pendingMutation.value.action;
      applyMutationProjection(action, result.payload);
      pendingMutation.value = null;
      mutationState.value = { kind: 'success', action, message: `${operationMessage(action)}成功。` };
      await loadProfiles();
      if (action === 'activate' || action === 'create') emit('profile-activation-complete');
    } else {
      mutationState.value = { kind: 'error', action: pendingMutation.value.action, message: '操作尚未完成，请稍后查询。' };
    }
  } catch (error) {
    if (error.status === 423) writesDisabled.value = true;
    mutationState.value = { kind: 'error', action: pendingMutation.value.action, message: error.status === 404 ? '暂未找到操作结果，请稍后查询或重试。' : apiErrorMessage(error) };
  }
}

function retryRead() {
  loadProfiles({ append: false });
}

onMounted(() => loadProfiles());
onUnmounted(() => {
  readGeneration += 1;
  if (readController) readController.abort();
});
</script>

<template>
  <section class="profiles-page" aria-labelledby="profiles-page-title">
    <p class="eyebrow">Profiles</p>
    <h2 id="profiles-page-title" tabindex="-1">用户空间</h2>
    <p class="page-intro">管理本地数据使用的用户空间。切换空间不会删除账本或备份。</p>

    <form class="create-profile" @submit.prevent="submitCreate" novalidate>
      <label for="profile-name">新建用户空间</label>
      <div class="create-row">
        <input
          id="profile-name"
          v-model="profileName"
          type="text"
          autocomplete="off"
          :aria-invalid="nameError ? 'true' : 'false'"
          :aria-describedby="nameError ? 'profile-name-error' : undefined"
          placeholder="例如：家庭账本"
        >
        <button type="submit" :disabled="mutationState.kind === 'loading' || writesDisabled">创建</button>
      </div>
      <p v-if="nameError" id="profile-name-error" class="field-error" role="alert">{{ nameError }}</p>
    </form>

    <div class="profiles-toolbar">
      <label class="archive-filter">
        <input v-model="includeArchived" type="checkbox" @change="changeArchivedFilter">
        显示已归档
      </label>
      <button type="button" class="secondary-button" @click="retryRead">刷新</button>
    </div>

    <p v-if="announcement" class="sr-only" role="status" aria-live="polite">{{ announcement }}</p>
    <p v-if="mutationState.kind !== 'idle'" class="operation-message" :class="`operation-${mutationState.kind}`" role="status" aria-live="polite">
      {{ mutationState.message }}
      <template v-if="mutationState.kind === 'error' && pendingMutation">
        <button type="button" class="inline-action" :disabled="writesDisabled" @click="retryPending">重试</button>
        <button type="button" class="inline-action" @click="queryPendingOperation">查询结果</button>
      </template>
      <button v-if="mutationState.kind === 'error' && revisionConflict" type="button" class="inline-action" @click="refreshAfterConflict">刷新</button>
    </p>

    <div v-if="readState === 'loading'" class="loading-state" role="status">正在加载用户空间…</div>
    <div v-else-if="readState === 'error'" class="error-state" role="alert">
      <p>{{ readError }}</p>
      <button type="button" @click="retryRead">重新加载</button>
    </div>
    <template v-else>
      <ul class="profile-list" aria-label="用户空间列表">
        <li v-for="profile in sortedProfiles" :key="profile.id" class="profile-card">
          <div class="profile-copy">
            <h3>{{ profile.name }}</h3>
            <p class="profile-meta">
              <span class="profile-status" :data-status="profile.status">{{ statusLabel(profile.status) }}</span>
              <span v-if="profile.lastOpenedAt">最近打开：{{ profile.lastOpenedAt }}</span>
            </p>
          </div>
          <div class="profile-actions">
            <span v-if="profile.id === activeProfileId" class="active-mark">当前空间</span>
            <button
              v-else-if="profile.status === 'INACTIVE'"
              type="button"
              class="secondary-button"
              :disabled="mutationState.kind === 'loading' || writesDisabled"
              @click="activateProfile(profile)"
            >切换</button>
            <span v-else-if="profile.status === 'ARCHIVED'" class="muted-action">已归档</span>
            <button
              v-if="profile.status === 'INACTIVE'"
              type="button"
              class="danger-button"
              :disabled="mutationState.kind === 'loading' || writesDisabled"
              @click="askArchive(profile)"
            >归档</button>
            <div v-if="confirmingArchiveId === profile.id" class="archive-confirm" role="group" :aria-label="`确认归档 ${profile.name}`">
              <span>归档后不会删除账本或备份。</span>
              <button type="button" class="danger-button" :disabled="writesDisabled" @click="confirmArchive(profile)">确认归档</button>
              <button type="button" class="secondary-button" @click="confirmingArchiveId = null">取消</button>
            </div>
          </div>
        </li>
      </ul>
      <p v-if="hasMore" class="load-more-wrap">
        <button type="button" class="secondary-button" :disabled="readState === 'loading-more'" @click="loadProfiles({ append: true })">
          {{ readState === 'loading-more' ? '正在加载…' : '加载更多' }}
        </button>
      </p>
    </template>
  </section>
</template>

<style scoped>
.profiles-page { display: grid; gap: 1rem; max-width: 58rem; }
.page-intro { color: var(--muted-text, #5d6675); margin: 0; }
.create-profile, .profile-card { border: 1px solid var(--border-color, #d7dce5); border-radius: 0.75rem; padding: 1rem; background: var(--surface, #fff); }
.create-profile { display: grid; gap: 0.65rem; }
.create-row { display: flex; gap: 0.65rem; }
.create-row input { min-width: 0; flex: 1; }
.create-row button, .secondary-button, .danger-button, .error-state button { min-height: 2.5rem; padding: 0.45rem 0.9rem; border-radius: 0.45rem; border: 1px solid var(--accent, #295ecb); background: var(--accent, #295ecb); color: #fff; cursor: pointer; }
.secondary-button { background: transparent; color: var(--accent, #295ecb); }
.danger-button { border-color: #b42318; background: #b42318; }
button:disabled { opacity: 0.55; cursor: not-allowed; }
input[type='text'] { min-height: 2.5rem; border: 1px solid var(--border-color, #aeb7c6); border-radius: 0.45rem; padding: 0.45rem 0.65rem; font: inherit; }
.field-error, .error-state { color: #b42318; }
.profiles-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
.archive-filter { display: inline-flex; align-items: center; gap: 0.45rem; }
.profile-list { list-style: none; display: grid; gap: 0.75rem; padding: 0; margin: 0; }
.profile-card { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; }
.profile-copy h3 { margin: 0; overflow-wrap: anywhere; }
.profile-meta { display: flex; flex-wrap: wrap; gap: 0.65rem; color: var(--muted-text, #5d6675); font-size: 0.9rem; }
.profile-status { font-weight: 600; }
.profile-status[data-status='ARCHIVED'] { color: #6b7280; }
.profile-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 0.5rem; }
.active-mark { color: #176b3a; font-weight: 600; }
.muted-action { color: #6b7280; }
.archive-confirm { display: flex; flex-basis: 100%; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 0.5rem; color: #8a1c13; font-size: 0.9rem; }
.operation-message { margin: 0; padding: 0.65rem 0.8rem; border-radius: 0.45rem; background: #eef4ff; color: #174ea6; }
.operation-error { background: #fff1f0; color: #8a1c13; }
.operation-success { background: #edf8f0; color: #176b3a; }
.inline-action { margin-left: 0.65rem; border: 0; background: transparent; color: inherit; text-decoration: underline; cursor: pointer; font: inherit; }
.loading-state, .error-state { padding: 1rem 0; }
.load-more-wrap { text-align: center; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 36rem) {
  .create-row, .profile-card { flex-direction: column; }
  .create-row button, .profile-actions, .profile-actions > button { width: 100%; }
  .profile-actions { justify-content: stretch; }
  .archive-confirm { justify-content: stretch; }
  .archive-confirm > * { width: 100%; }
}
</style>
