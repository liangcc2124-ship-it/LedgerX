<script setup>
import { computed, onMounted, ref } from 'vue';
import { ApiClientError, createBackup, createRequestId, downloadBackup, listBackups, verifyBackup } from '../apiClient.js';

const props = defineProps({ capabilities: { type: Array, default: () => [] } });

const canList = computed(() => props.capabilities.includes('backups.list'));
const canCreate = computed(() => props.capabilities.includes('backups.create'));
const canVerify = computed(() => props.capabilities.includes('backups.verify'));
const canDownload = computed(() => props.capabilities.includes('backups.download'));
const backups = ref([]);
const loading = ref(false);
const nextCursor = ref(null);
const hasMore = ref(false);
const loadError = ref('');
const createState = ref({ kind: 'idle', message: '' });
const pendingCreateKey = ref(null);
const verifyingIds = ref(new Set());
const downloadingId = ref(null);
const announcement = ref('');

const statusLabel = {
  VALID: '完整性已验证',
  NOT_VERIFIED: '尚未校验',
  INVALID: '校验失败',
};

function isBackup(value) {
  return value && typeof value === 'object'
    && typeof value.id === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value.id)
    && typeof value.profileId === 'string'
    && typeof value.createdAt === 'string'
    && Number.isInteger(value.formatVersion)
    && Number.isInteger(value.schemaVersion)
    && Number.isSafeInteger(value.dataRevision)
    && value.dataRevision >= 0
    && value.counts && ['records', 'categories', 'accounts', 'metrics'].every((key) => Number.isSafeInteger(value.counts[key]) && value.counts[key] >= 0)
    && Number.isSafeInteger(value.sizeBytes)
    && ['VALID', 'NOT_VERIFIED', 'INVALID'].includes(value.integrityStatus)
    && value.encrypted === false
    && typeof value.fileName === 'string';
}

function isBackupListItem(value) {
  if (!value || typeof value !== 'object' || typeof value.id !== 'string'
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value.id)
      || !Number.isSafeInteger(value.sizeBytes) || value.sizeBytes < 0
      || !['VALID', 'NOT_VERIFIED', 'INVALID'].includes(value.integrityStatus)
      || value.encrypted !== false || typeof value.fileName !== 'string') return false;
  if (value.integrityStatus === 'INVALID') {
    return value.profileId === null && value.createdAt === null && value.schemaVersion === null
      && value.applicationVersion === null && value.counts && typeof value.counts === 'object';
  }
  return isBackup(value);
}

function invalidResponse() {
  return new ApiClientError('本地服务返回了无法读取的备份列表，请重试。', {
    code: 'INVALID_RESPONSE',
    retryable: false,
  });
}

function friendlyError(error) {
  if (error instanceof ApiClientError && error.status === 401) return '本地会话已失效，请重新打开 LedgerX。';
  if (error instanceof ApiClientError && error.status === 409) return '请先完成当前账本的初始化，再创建备份。';
  if (error instanceof ApiClientError && error.status === 422) return '备份文件未通过完整性检查，请不要把它当作可恢复备份。';
  if (error?.code === 'REQUEST_ABORTED') return '操作已取消。';
  return error?.message || '本地服务暂时不可用，请重试。';
}

async function loadBackups({ append = false, retainValidatedId = null } = {}) {
  if (!canList.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const result = await listBackups({ limit: 25, cursor: append ? nextCursor.value : undefined });
    const data = result.payload?.data;
    if (!data || !Array.isArray(data.items) || !data.items.every(isBackupListItem)
        || !data.page || typeof data.page.hasMore !== 'boolean'
        || (data.page.nextCursor !== null && typeof data.page.nextCursor !== 'string')) {
      throw invalidResponse();
    }
    const loadedItems = data.items.map((item) => item.id === retainValidatedId
      && item.integrityStatus === 'NOT_VERIFIED'
      && backups.value.some((known) => known.id === item.id && known.integrityStatus === 'VALID')
      ? { ...item, integrityStatus: 'VALID' }
      : item);
    if (append) {
      const knownIds = new Set(backups.value.map((item) => item.id));
      backups.value = [...backups.value, ...loadedItems.filter((item) => !knownIds.has(item.id))];
    } else {
      backups.value = loadedItems;
    }
    nextCursor.value = data.page.nextCursor;
    hasMore.value = data.page.hasMore;
  } catch (error) {
    loadError.value = friendlyError(error);
  } finally {
    loading.value = false;
  }
}

async function runCreateBackup({ retry = false } = {}) {
  if (!canCreate.value || createState.value.kind === 'loading') return;
  if (!retry || !pendingCreateKey.value) pendingCreateKey.value = createRequestId();
  createState.value = { kind: 'loading', message: '正在创建并校验备份…' };
  announcement.value = '';
  try {
    const result = await createBackup({ idempotencyKey: pendingCreateKey.value });
    const backup = result.payload?.data?.backup;
    if (!isBackup(backup) || backup.integrityStatus !== 'VALID') throw invalidResponse();
    backups.value = [backup, ...backups.value.filter((item) => item.id !== backup.id)];
    pendingCreateKey.value = null;
    createState.value = { kind: 'success', message: '备份已创建并通过完整性校验。' };
    announcement.value = '备份已创建并通过完整性校验。';
    await loadBackups({ retainValidatedId: backup.id });
  } catch (error) {
    createState.value = { kind: 'error', message: friendlyError(error) };
  }
}

async function verify(item) {
  if (!canVerify.value || verifyingIds.value.has(item.id)) return;
  verifyingIds.value = new Set(verifyingIds.value).add(item.id);
  try {
    const result = await verifyBackup(item.id);
    const backup = result.payload?.data?.backup;
    const verification = result.payload?.data?.verification;
    if (!isBackup(backup) || verification?.status !== 'VALID' || typeof verification.verifiedAt !== 'string') {
      throw invalidResponse();
    }
    backups.value = backups.value.map((known) => known.id === item.id
      ? { ...backup, integrityStatus: verification.status, verifiedAt: verification.verifiedAt }
      : known);
    announcement.value = `${formatDate(item.createdAt)} 的备份完整性校验通过。`;
  } catch (error) {
    if (error?.status === 422) {
      backups.value = backups.value.map((known) => known.id === item.id ? { ...known, integrityStatus: 'INVALID' } : known);
    }
    loadError.value = friendlyError(error);
  } finally {
    const active = new Set(verifyingIds.value);
    active.delete(item.id);
    verifyingIds.value = active;
  }
}

async function download(item) {
  if (!canDownload.value || downloadingId.value) return;
  downloadingId.value = item.id;
  loadError.value = '';
  try {
    const result = await downloadBackup(item.id);
    const url = URL.createObjectURL(result.blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = result.fileName;
    anchor.hidden = true;
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    announcement.value = `已开始下载 ${result.fileName}。`;
  } catch (error) {
    loadError.value = friendlyError(error);
  } finally {
    downloadingId.value = null;
  }
}

function formatDate(value) {
  if (typeof value !== 'string') return '日期信息不可用';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date);
}

function formatBytes(value) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

onMounted(loadBackups);
</script>

<template>
  <section class="settings-page" aria-labelledby="settings-page-title">
    <h2 id="settings-page-title" tabindex="-1">设置与备份</h2>
    <p>LedgerX 在本机运行。这里可以为当前用户空间创建一份可下载的账本快照。</p>

    <section class="info-card backup-card" aria-labelledby="backup-title">
      <div class="backup-heading">
        <div>
          <h3 id="backup-title">本机备份</h3>
          <p class="hint">创建时会校验账本快照；备份文件包含财务数据，未加密，请保存在可信位置。</p>
        </div>
        <button v-if="canCreate" type="button" :disabled="createState.kind === 'loading'" @click="runCreateBackup()">
          {{ createState.kind === 'loading' ? '创建中…' : '立即备份' }}
        </button>
      </div>

      <p v-if="createState.kind !== 'idle'" class="operation-message" :class="`operation-${createState.kind}`" role="status" aria-live="polite">
        {{ createState.message }}
        <button v-if="createState.kind === 'error' && pendingCreateKey" type="button" class="inline-action" @click="runCreateBackup({ retry: true })">使用同一请求重试</button>
      </p>
      <p v-if="loadError" class="operation-message operation-error" role="alert">{{ loadError }}</p>
      <p v-if="announcement" class="sr-only" role="status" aria-live="polite">{{ announcement }}</p>

      <div v-if="!canList" class="empty-state">
        账本初始化完成后，备份功能会在这里显示。
      </div>
      <div v-else-if="loading && backups.length === 0" class="empty-state" role="status">正在读取备份…</div>
      <div v-else-if="loadError && backups.length === 0" class="empty-state">
        <button type="button" class="secondary-button" @click="loadBackups">重新加载</button>
      </div>
      <div v-else-if="backups.length === 0" class="empty-state">还没有备份。建议在重要记账前后各创建一份。</div>
      <ul v-else class="backup-list" aria-label="备份列表">
        <li v-for="item in backups" :key="item.id" class="backup-item">
          <div class="backup-summary">
            <h4>{{ formatDate(item.createdAt) }}</h4>
            <p class="backup-details">
              <span class="integrity-status" :data-status="item.integrityStatus">{{ statusLabel[item.integrityStatus] || '状态未知' }}</span>
              <template v-if="item.integrityStatus !== 'INVALID'">
                <span>{{ item.counts.records }} 条记录</span>
                <span>{{ item.counts.categories }} 个分类</span>
                <span>{{ item.counts.accounts }} 个账户</span>
                <span>{{ item.counts.metrics }} 个指标</span>
              </template>
              <span v-else>备份元数据不可读取</span>
              <span>{{ formatBytes(item.sizeBytes) }}</span>
            </p>
            <p class="backup-file">{{ item.fileName }}</p>
          </div>
          <div class="backup-actions">
            <button v-if="canVerify" type="button" class="secondary-button" :disabled="verifyingIds.has(item.id)" @click="verify(item)">
              {{ verifyingIds.has(item.id) ? '校验中…' : '校验' }}
            </button>
            <button v-if="canDownload" type="button" class="secondary-button" :disabled="Boolean(downloadingId)" @click="download(item)">
              {{ downloadingId === item.id ? '准备下载…' : '下载' }}
            </button>
          </div>
        </li>
      </ul>
      <div v-if="canList && backups.length > 0" class="backup-footer">
        <span>{{ hasMore ? `已显示 ${backups.length} 份备份。` : `当前已显示 ${backups.length} 份备份。` }}</span>
        <div class="footer-actions">
          <button v-if="hasMore" type="button" class="text-button" :disabled="loading" @click="loadBackups({ append: true })">{{ loading ? '加载中…' : '加载更多' }}</button>
          <button type="button" class="text-button" :disabled="loading" @click="loadBackups()">{{ loading ? '刷新中…' : '刷新列表' }}</button>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.settings-page { display: grid; width: 100%; max-width: 48rem; gap: 1.75rem; }
.settings-page > h2 { margin: 0; color: var(--text); font-size: clamp(1.9rem, 4vw, 3rem); font-weight: 650; letter-spacing: -.04em; line-height: 1.1; }
.settings-page > p { margin: -.85rem 0 .65rem; color: var(--muted-text); line-height: 1.7; }
.info-card { min-width: 0; padding: 1.5rem; border: 1px solid var(--border-color); border-radius: .55rem; background: var(--surface); }
.info-card h3 { margin: 0; color: var(--text); font-size: 1.12rem; }
.backup-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; }
.backup-heading > div { min-width: 0; }
.hint { margin: .55rem 0 0; color: var(--muted-text); font-size: .92rem; line-height: 1.55; }
button { min-height: 2.55rem; padding: .5rem .9rem; border: 1px solid var(--accent); border-radius: .45rem; background: var(--accent); color: white; cursor: pointer; font: inherit; font-weight: 600; }
button:hover:not(:disabled) { background: var(--accent-hover); }
button:disabled { opacity: .58; cursor: not-allowed; }
.secondary-button { flex: 0 0 auto; border-color: var(--border-color); background: transparent; color: var(--muted-text); }
.secondary-button:hover:not(:disabled) { background: var(--accent-soft); color: var(--accent-hover); }
.empty-state { padding: 1.25rem 0 .25rem; color: var(--muted-text); line-height: 1.6; }
.backup-list { display: grid; gap: .7rem; margin: 1.2rem 0 0; padding: 0; list-style: none; }
.backup-item { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 1rem; padding: 1rem; border: 1px solid var(--border-color); border-radius: .45rem; }
.backup-summary { min-width: 0; }
.backup-summary h4 { margin: 0; color: var(--text); font-size: 1rem; }
.backup-details { display: flex; flex-wrap: wrap; gap: .4rem .8rem; margin: .5rem 0 0; color: var(--muted-text); font-size: .9rem; }
.integrity-status { font-weight: 600; }
.integrity-status[data-status='VALID'] { color: var(--success); }
.integrity-status[data-status='INVALID'] { color: var(--danger); }
.backup-file { margin: .45rem 0 0; color: var(--muted-text); font-size: .82rem; overflow-wrap: anywhere; }
.backup-actions { display: flex; flex: 0 0 auto; gap: .5rem; }
.backup-footer { display: flex; justify-content: space-between; align-items: center; gap: 1rem; margin-top: 1rem; color: var(--muted-text); font-size: .88rem; }
.footer-actions { display: flex; align-items: center; gap: .75rem; }
.text-button, .inline-action { min-height: auto; padding: .25rem; border: 0; background: transparent; color: var(--accent-hover); font: inherit; text-decoration: underline; }
.operation-message { margin: 1rem 0 0; padding: .8rem 1rem; border: 1px solid var(--border-color); border-radius: .45rem; background: var(--surface-muted); color: var(--muted-text); line-height: 1.5; }
.operation-error { border-color: #e4c5c0; background: #fbefec; color: var(--danger); }
.operation-success { border-color: #c7ddd0; background: #eef6f0; color: var(--success); }
.inline-action { margin-left: .6rem; color: inherit; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 36rem) {
  .info-card { padding: 1rem; }
  .backup-heading { flex-direction: column; }
  .backup-heading > button { width: 100%; }
  .backup-item { align-items: stretch; flex-direction: column; }
  .backup-actions > button { flex: 1; }
  .backup-footer { align-items: flex-start; flex-direction: column; }
  .footer-actions { flex-wrap: wrap; }
}
</style>
