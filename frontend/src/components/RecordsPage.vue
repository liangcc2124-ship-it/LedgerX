<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue';
import { ApiClientError, createRequestId, requestApiJson } from '../apiClient.js';
import { APP_CONTEXT_KEY } from '../appContext.js';
import { useApiMutation } from '../composables/useApiMutation.js';
import { useAsyncResource } from '../composables/useAsyncResource.js';
import { balanceSideLabel, entityStatusLabel, recordTypeLabel } from '../presentationMaps.js';
import AppDialog from './common/AppDialog.vue';

const today = () => {
  const date = new Date();
  const pad = (value) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
};

const categories = ref([]);
const accounts = ref([]);
const records = ref([]);
const trash = ref(false);
const loading = ref({ categories: true, accounts: true, records: true });
const errors = ref({ categories: null, accounts: null, records: null });
const message = ref('');
const formOpen = ref(false);
const editing = ref(null);
const form = ref(blankForm());
const saving = ref(false);
const recordConfirmation = ref(null);
const recordPage = ref({ nextCursor: null, hasMore: false });
const recordQueryKey = ref(null);
const formErrors = ref({});
const mutationIntent = ref(null);
const appContext = inject(APP_CONTEXT_KEY, null);
const mutation = useApiMutation();
const mutationState = computed(() => mutation.state.value);
const categoryResource = useAsyncResource(({ signal }) => requestApiJson('/api/v1/categories?limit=200', { signal }).then((response) => ({ items: response.payload?.data?.items || [], meta: response.payload?.meta })));
const accountResource = useAsyncResource(({ signal }) => requestApiJson('/api/v1/accounts?limit=200', { signal }).then((response) => ({ items: response.payload?.data?.items || [], meta: response.payload?.meta })));
const recordsResource = useAsyncResource(({ signal, input }) => {
  const cursor = input?.cursor ? `&cursor=${encodeURIComponent(input.cursor)}` : '';
  return requestApiJson(`/api/v1/records?status=${input.status}&limit=50${cursor}`, { signal }).then((response) => ({ items: response.payload?.data?.items || [], page: response.payload?.data?.page || {}, meta: response.payload?.meta }));
});

function blankForm() {
  const date = today();
  return { recordType: 'VARIABLE_COST', amount: '', occurredOn: date, categoryId: '', accountId: '', settlementOn: date, note: '' };
}

const activeAccounts = computed(() => accounts.value.filter((account) => account.status === 'ACTIVE'));
const usableCategories = computed(() => categories.value.filter((category) => {
  if (category.status !== 'ACTIVE' || category.canUseForRecords !== true) return false;
  return !Array.isArray(category.recordTypes) || category.recordTypes.length === 0 || category.recordTypes.includes(form.value.recordType);
}));

function currentEpoch() { return appContext?.contextEpoch?.value ?? 0; }
function commitMutation(response) { appContext?.commitMutation(response?.payload?.meta); }
function errorText(error, fallback = '操作失败，请重试。') { return error instanceof ApiClientError ? error.message : fallback; }
function fieldError(...paths) { for (const path of paths) { if (formErrors.value[path]) return String(formErrors.value[path]); } return ''; }

async function loadCatalog() {
  const requestEpoch = currentEpoch();
  loading.value = { ...loading.value, categories: true, accounts: true };
  errors.value = { ...errors.value, categories: null, accounts: null };
  const results = await Promise.allSettled([
    categoryResource.execute({ mode: 'replace', input: { queryKey: `categories:${requestEpoch}` } }),
    accountResource.execute({ mode: 'replace', input: { queryKey: `accounts:${requestEpoch}` } }),
  ]);
  if (requestEpoch !== currentEpoch()) return;
  const [categoryResult, accountResult] = results;
  if (categoryResult.status === 'fulfilled' && categoryResult.value) categories.value = categoryResult.value.items;
  else if (categoryResult.status === 'rejected') errors.value = { ...errors.value, categories: errorText(categoryResult.reason, '分类读取失败，请重试。') };
  if (accountResult.status === 'fulfilled' && accountResult.value) accounts.value = accountResult.value.items;
  else if (accountResult.status === 'rejected') errors.value = { ...errors.value, accounts: errorText(accountResult.reason, '账户读取失败，请重试。') };
  loading.value = { ...loading.value, categories: false, accounts: false };
}

async function loadRecords() {
  const status = trash.value ? 'TRASHED' : 'ACTIVE';
  const queryKey = `records:${status}:${currentEpoch()}`;
  recordQueryKey.value = queryKey;
  records.value = [];
  recordPage.value = { nextCursor: null, hasMore: false };
  loading.value = { ...loading.value, records: true };
  errors.value = { ...errors.value, records: null };
  try {
    const result = await recordsResource.execute({ mode: 'replace', input: { status, queryKey } });
    if (!result || recordQueryKey.value !== queryKey) return;
    records.value = result.items;
    recordPage.value = { nextCursor: result.page?.nextCursor ?? null, hasMore: Boolean(result.page?.hasMore) };
  } catch (error) {
    if (recordQueryKey.value === queryKey) errors.value = { ...errors.value, records: errorText(error, '记录读取失败，请重试。') };
  } finally {
    if (recordQueryKey.value === queryKey) loading.value = { ...loading.value, records: false };
  }
}

async function loadMoreRecords() {
  const status = trash.value ? 'TRASHED' : 'ACTIVE';
  const queryKey = recordQueryKey.value;
  const cursor = recordPage.value.nextCursor;
  if (!recordPage.value.hasMore || !cursor || !queryKey || loading.value.records) return;
  loading.value = { ...loading.value, records: true };
  errors.value = { ...errors.value, records: null };
  try {
    const result = await recordsResource.execute({ mode: 'append', input: { status, cursor, queryKey } });
    if (!result || recordQueryKey.value !== queryKey) return;
    const existing = new Set(records.value.map((record) => record.id));
    records.value = [...records.value, ...result.items.filter((record) => !existing.has(record.id))];
    recordPage.value = { nextCursor: result.page?.nextCursor ?? null, hasMore: Boolean(result.page?.hasMore) };
  } catch (error) {
    if (recordQueryKey.value === queryKey) errors.value = { ...errors.value, records: errorText(error, '加载更多记录失败，请重试。') };
  } finally {
    if (recordQueryKey.value === queryKey) loading.value = { ...loading.value, records: false };
  }
}

function retry(part) { if (part === 'records') void loadRecords(); else void loadCatalog(); }
function openCreate() { mutation.clear(); mutationIntent.value = null; formErrors.value = {}; editing.value = null; form.value = blankForm(); message.value = ''; formOpen.value = true; }
function openEdit(record) {
  mutation.clear(); mutationIntent.value = null; formErrors.value = {};
  editing.value = record;
  form.value = { recordType: record.recordType, amount: record.amount, occurredOn: record.occurredOn, categoryId: record.category.id, accountId: record.settlement.account.id, settlementOn: record.settlement.settlementOn, note: record.note };
  formOpen.value = true; message.value = '';
}
function closeForm() {
  if (saving.value || mutationState.value === 'pending-confirmation') return;
  formOpen.value = false;
  editing.value = null;
}
function onTypeChange() { if (!usableCategories.value.some((category) => category.id === form.value.categoryId)) form.value.categoryId = ''; }

function validate() {
  const amount = form.value.amount;
  const positive = /^(0|[1-9][0-9]*)(\.[0-9]{1,2})?$/.test(amount) && !/^0(?:\.0{1,2})?$/.test(amount);
  if (!positive) return '金额必须是大于 0 的两位以内小数。';
  if (!form.value.categoryId) return '请选择分类。';
  if (!form.value.accountId) return '请选择账户。';
  if (!form.value.occurredOn || !form.value.settlementOn) return '请选择日期。';
  if (form.value.note.length > 4000) return '备注不能超过 4000 个字符。';
  return '';
}

async function save() {
  message.value = '';
  formErrors.value = {};
  const validation = validate();
  if (validation) { message.value = validation; return; }
  const body = { ...form.value, id: editing.value?.id || createRequestId(), currency: 'CNY', settlement: { mode: 'PAID_FROM_ACCOUNT', accountId: form.value.accountId, settlementOn: form.value.settlementOn } };
  delete body.accountId; delete body.settlementOn;
  mutationIntent.value = { kind: 'save', successMessage: editing.value ? '记录已更新。' : '记录已保存。' };
  saving.value = true;
  try {
    const response = await mutation.submit({ method: editing.value ? 'PUT' : 'POST', path: editing.value ? `/api/v1/records/${editing.value.id}` : '/api/v1/records', body, ifMatch: editing.value ? `"${editing.value.revision}"` : undefined, idempotencyKey: createRequestId() });
    await completeMutation(response);
  } catch (error) { formErrors.value = error?.fieldErrors || {}; message.value = errorText(error, '保存失败，请重试。'); }
  finally { saving.value = false; }
}

function askRecordAction(record, action) {
  if (saving.value) return;
  recordConfirmation.value = { record, action };
}

function cancelRecordAction() { if (!saving.value && mutationState.value !== 'pending-confirmation') recordConfirmation.value = null; }

async function confirmRecordAction() {
  const pending = recordConfirmation.value;
  if (!pending || saving.value) return;
  saving.value = true;
  try {
    const { record, action } = pending;
    mutationIntent.value = { kind: action, successMessage: action === 'remove' ? '记录已移入回收站。' : '记录已恢复。' };
    if (action === 'remove') {
      await mutation.submit({ method: 'DELETE', path: `/api/v1/records/${record.id}`, ifMatch: `"${record.revision}"`, idempotencyKey: createRequestId() });
    } else {
      await mutation.submit({ method: 'POST', path: `/api/v1/records/${record.id}/restore`, body: {}, ifMatch: `"${record.revision}"`, idempotencyKey: createRequestId() });
    }
    await completeMutation(mutation.result.value);
  } catch (error) { message.value = errorText(error); }
  finally { saving.value = false; }
}

async function completeMutation(response) {
  commitMutation(response);
  const intent = mutationIntent.value;
  message.value = intent?.successMessage || '操作已完成。';
  if (intent?.kind === 'save') { formOpen.value = false; editing.value = null; }
  else recordConfirmation.value = null;
  mutationIntent.value = null;
  void loadRecords();
  void loadCatalog();
}

async function retryMutation() {
  if (!mutationIntent.value || saving.value) return;
  saving.value = true;
  try { const response = await mutation.retrySame(); await completeMutation(response); }
  catch (error) { message.value = errorText(error); }
  finally { saving.value = false; }
}

async function queryMutation() {
  if (!mutationIntent.value || saving.value) return;
  saving.value = true;
  try {
    const response = await mutation.queryOperation();
    if (mutationState.value === 'success') await completeMutation(response);
    else message.value = '操作仍在处理中，请稍后查询或使用相同操作重试。';
  } catch (error) { message.value = errorText(error); }
  finally { saving.value = false; }
}

function setTrash(value) { if (trash.value === value) return; trash.value = value; void loadRecords(); }

watch(() => appContext?.contextEpoch?.value ?? 0, () => { void loadCatalog(); void loadRecords(); });
onMounted(() => { void loadCatalog(); void loadRecords(); });
</script>

<template>
  <section class="records-page" aria-labelledby="records-page-title">
    <div class="records-heading">
      <div><h2 id="records-page-title" tabindex="-1">收支记录</h2><p>收入、固定支出和弹性支出。</p></div>
      <button type="button" :disabled="!activeAccounts.length || !usableCategories.length || loading.accounts || loading.categories" @click="openCreate">新增记录</button>
    </div>
    <p v-if="message && !formOpen" class="records-message" role="status" aria-live="polite">{{ message }}</p>
    <div v-if="loading.accounts" class="records-state">正在读取账户…</div>
    <div v-else-if="errors.accounts" class="records-state records-error">{{ errors.accounts }} <button type="button" @click="retry('accounts')">重试</button></div>
    <div v-else class="balance-grid"><article v-for="account in activeAccounts" :key="account.id"><span>{{ account.name }}</span><strong>{{ account.balance }} {{ account.currency }}</strong><small>{{ balanceSideLabel(account.balanceSide) }}</small></article><p v-if="!activeAccounts.length">请先在分类与账户中新增账户。</p><p v-else-if="!usableCategories.length">当前没有可用于记账的分类，请先在分类与账户中新增或启用分类。</p></div>

    <div class="records-toolbar"><button type="button" :class="{ selected: !trash }" @click="setTrash(false)">当前记录</button><button type="button" :class="{ selected: trash }" @click="setTrash(true)">回收站</button></div>
    <div v-if="loading.records" class="records-state">正在读取记录…</div>
    <div v-else-if="errors.records" class="records-state records-error">{{ errors.records }} <button type="button" @click="retry('records')">重试</button></div>
    <p v-else-if="!records.length" class="records-empty">{{ trash ? '回收站为空。' : '还没有记录。' }}</p>
    <ul v-else class="record-list"><li v-for="record in records" :key="record.id"><div><strong>{{ record.occurredOn }} · {{ recordTypeLabel(record.recordType) }}</strong><span>{{ record.category.name }} · {{ record.settlement.account.name }} · {{ record.note || '无备注' }} · {{ entityStatusLabel(trash ? 'ARCHIVED' : 'ACTIVE') }}</span></div><strong>{{ record.amount }} {{ record.currency }}</strong><div class="record-actions"><button v-if="!trash" type="button" @click="openEdit(record)">编辑</button><button v-if="!trash" type="button" @click="askRecordAction(record, 'remove')">删除</button><button v-else type="button" @click="askRecordAction(record, 'restore')">恢复</button></div></li></ul>
    <div v-if="recordPage.hasMore" class="records-more"><button type="button" :disabled="loading.records" @click="loadMoreRecords">{{ loading.records ? '加载中…' : '加载更多记录' }}</button></div>

  </section>

  <AppDialog :open="formOpen" title-id="record-form-title" initial-focus="input[inputmode='decimal']" :busy="saving" @close="closeForm">
      <section class="record-modal">
        <div class="modal-heading">
          <h3 id="record-form-title">{{ editing ? '编辑记录' : '新增记录' }}</h3>
          <button type="button" class="modal-close" aria-label="关闭表单" :disabled="saving" @click="closeForm">×</button>
        </div>
        <p v-if="message" class="modal-message" role="status" aria-live="polite">{{ message }}</p>
        <form class="record-form" @submit.prevent="save">
          <label>类型<select v-model="form.recordType" @change="onTypeChange"><option value="INCOME">收入</option><option value="FIXED_COST">固定支出</option><option value="VARIABLE_COST">弹性支出</option></select></label>
          <label>金额<input v-model="form.amount" inputmode="decimal" autocomplete="off" placeholder="0.00" :aria-invalid="fieldError('amount') ? 'true' : 'false'"><p v-if="fieldError('amount')" class="field-error" role="alert">{{ fieldError('amount') }}</p></label>
          <label>发生日期<input v-model="form.occurredOn" type="date"></label>
          <label>分类<select v-model="form.categoryId" :aria-invalid="fieldError('categoryId') ? 'true' : 'false'"><option value="" disabled>请选择分类</option><option v-for="category in usableCategories" :key="category.id" :value="category.id">{{ category.name }}</option></select><p v-if="fieldError('categoryId')" class="field-error" role="alert">{{ fieldError('categoryId') }}</p></label>
          <label>结算账户<select v-model="form.accountId" :aria-invalid="fieldError('accountId', 'settlement.accountId') ? 'true' : 'false'"><option value="" disabled>请选择账户</option><option v-for="account in activeAccounts" :key="account.id" :value="account.id">{{ account.name }}</option></select><p v-if="fieldError('accountId', 'settlement.accountId')" class="field-error" role="alert">{{ fieldError('accountId', 'settlement.accountId') }}</p></label>
          <label>结算日期<input v-model="form.settlementOn" type="date"></label>
          <label>备注<textarea v-model="form.note" maxlength="4000" rows="3"></textarea></label>
          <div class="form-actions"><button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button><button type="button" :disabled="saving || mutationState === 'pending-confirmation'" @click="closeForm">取消</button></div>
          <div v-if="mutationState === 'pending-confirmation'" class="mutation-actions"><p class="field-error" role="status">提交结果尚未确认。</p><button type="button" :disabled="saving" @click="queryMutation">查询结果</button><button type="button" :disabled="saving" @click="retryMutation">使用相同操作重试</button></div>
        </form>
      </section>
  </AppDialog>
  <AppDialog :open="Boolean(recordConfirmation)" title-id="record-confirm-title" :busy="saving" :close-on-backdrop="false" @close="cancelRecordAction">
    <section class="record-modal confirm-modal">
      <div class="modal-heading"><h3 id="record-confirm-title">{{ recordConfirmation?.action === 'remove' ? '移入回收站' : '恢复记录' }}</h3><button type="button" class="modal-close" :disabled="saving" aria-label="关闭确认" @click="cancelRecordAction">×</button></div>
      <p>{{ recordConfirmation?.action === 'remove' ? '确定将这笔记录移入回收站吗？' : '确定恢复这笔记录吗？' }}</p>
      <div class="form-actions"><button type="button" :disabled="saving" @click="confirmRecordAction">{{ saving ? '处理中…' : '确认' }}</button><button type="button" :disabled="saving || mutationState === 'pending-confirmation'" @click="cancelRecordAction">取消</button></div>
      <div v-if="mutationState === 'pending-confirmation'" class="mutation-actions"><p class="field-error" role="status">提交结果尚未确认。</p><button type="button" :disabled="saving" @click="queryMutation">查询结果</button><button type="button" :disabled="saving" @click="retryMutation">使用相同操作重试</button></div>
    </section>
  </AppDialog>
</template>

<style scoped>
.records-page { display: grid; gap: 1rem; max-width: 64rem; }
.records-heading, .records-toolbar, .form-actions, .record-actions { display: flex; flex-wrap: wrap; align-items: center; gap: .6rem; }
.records-heading { justify-content: space-between; } h2, h3, p { margin-top: 0; } .records-heading p { margin-bottom: 0; color: #5d6675; }
button { min-height: 2.35rem; padding: .4rem .75rem; border: 1px solid #295ecb; border-radius: .45rem; background: #295ecb; color: white; cursor: pointer; } button:disabled { opacity: .55; cursor: not-allowed; }
.records-toolbar button { background: white; color: #295ecb; } .records-toolbar .selected { background: #295ecb; color: white; }
.balance-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr)); gap: .7rem; } .balance-grid article, .record-list li, .record-form { border: 1px solid #d7dce5; border-radius: .65rem; padding: .8rem; background: white; } .balance-grid article { display: grid; gap: .25rem; } .balance-grid small { color: #5d6675; }
.record-list { list-style: none; padding: 0; margin: 0; display: grid; gap: .6rem; } .record-list li { display: grid; grid-template-columns: 1fr auto auto; gap: .8rem; align-items: center; } .record-list span { display: block; margin-top: .25rem; color: #5d6675; overflow-wrap: anywhere; }
.record-actions button { background: white; color: #295ecb; } .record-form { display: grid; gap: .75rem; max-width: 34rem; } .record-form label { display: grid; gap: .3rem; } input, select, textarea { min-height: 2.35rem; border: 1px solid #aeb7c6; border-radius: .4rem; padding: .4rem .55rem; font: inherit; } .form-actions { justify-content: flex-end; }
.records-state, .records-empty, .records-message { padding: .7rem; border-radius: .45rem; background: #eef4ff; } .records-error { background: #fff1f0; color: #8a1c13; } .records-error button { margin-left: .5rem; } 

.records-page {
  width: 100%;
  max-width: none;
  gap: 1.75rem;
}

.records-heading {
  align-items: flex-end;
  gap: 1.5rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid var(--border-color);
}

.records-heading h2 {
  margin-bottom: 0.55rem;
  color: var(--text);
  font-size: clamp(1.9rem, 4vw, 3rem);
  font-weight: 650;
  letter-spacing: -0.04em;
}

.records-heading p {
  color: var(--muted-text);
  line-height: 1.6;
}

.records-page button {
  min-height: 2.75rem;
  padding: 0.65rem 1rem;
  border-color: var(--accent);
  border-radius: 0.45rem;
  background: var(--accent);
  color: #fff;
}

.records-page button:hover:not(:disabled) {
  background: var(--accent-hover);
}

.records-toolbar {
  gap: 0.4rem;
  padding-bottom: 0.35rem;
  border-bottom: 1px solid var(--border-color);
}

.records-toolbar button,
.record-actions button {
  border-color: transparent;
  background: transparent;
  color: var(--muted-text);
}

.records-toolbar button:hover:not(:disabled),
.records-toolbar button.selected,
.record-actions button:hover:not(:disabled) {
  border-color: var(--accent-soft);
  background: var(--accent-soft);
  color: var(--accent-hover);
}

.balance-grid {
  gap: 1rem;
}

.balance-grid article,
.record-list li,
.record-form {
  border-color: var(--border-color);
  border-radius: 0.55rem;
  background: var(--surface);
}

.balance-grid article {
  gap: 0.45rem;
  padding: 1.15rem 1.25rem;
}

.balance-grid article span,
.balance-grid small,
.record-list span {
  color: var(--muted-text);
}

.record-list {
  gap: 0.7rem;
}

.record-list li {
  gap: 1rem;
  padding: 1.15rem 1.25rem;
}

.record-list li > div:first-child {
  min-width: 0;
}

.record-list li > strong {
  color: var(--text);
  white-space: nowrap;
}

.record-form {
  max-width: 42rem;
  gap: 1rem;
  padding: 1.5rem;
}

.record-form h3 {
  margin-bottom: 0.25rem;
  color: var(--text);
  font-size: 1.25rem;
}

.record-form label {
  gap: 0.45rem;
  color: var(--muted-text);
  font-size: 0.9rem;
}

.record-form input,
.record-form select,
.record-form textarea {
  min-height: 2.8rem;
  border-color: #cfc7bd;
  border-radius: 0.4rem;
  background: #fff;
}

.form-actions {
  margin-top: 0.35rem;
}

.form-actions button:last-child {
  border-color: var(--border-color);
  background: transparent;
  color: var(--muted-text);
}

.records-state,
.records-empty,
.records-message {
  padding: 1rem 1.1rem;
  border: 1px solid var(--border-color);
  border-radius: 0.45rem;
  background: var(--surface-muted);
  color: var(--muted-text);
  line-height: 1.55;
}

.records-error {
  border-color: #e4c5c0;
  background: #fbefec;
  color: var(--danger);
}

@media (max-width: 42rem) {
  .records-heading,
  .record-list li {
    align-items: flex-start;
  }

  .record-list li {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .record-list li > strong {
    grid-column: 2;
    grid-row: 1;
  }

  .record-list li .record-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
    width: 100%;
    border-top: 1px solid var(--border-color);
    padding-top: 0.7rem;
  }
}
@media (max-width: 36rem) { .record-list li { grid-template-columns: 1fr; } .record-actions button { flex: 1; } .records-heading > button { width: 100%; } }

.modal-backdrop {
  position: fixed;
  z-index: 20;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 1.25rem;
  overflow: auto;
  background: rgba(37, 35, 33, 0.28);
}

.record-modal {
  width: min(100%, 42rem);
  max-height: calc(100vh - 2.5rem);
  overflow: auto;
  padding: 1.5rem;
  border: 1px solid var(--border-color);
  border-radius: 0.7rem;
  background: var(--surface);
  box-shadow: 0 1.5rem 4rem rgba(37, 35, 33, 0.2);
}

.modal-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border-color);
}

.modal-heading h3 {
  margin: 0;
  color: var(--text);
  font-size: 1.3rem;
}

.modal-close {
  display: grid;
  place-items: center;
  width: 2.5rem;
  min-height: 2.5rem;
  padding: 0;
  border-color: transparent;
  background: transparent;
  color: var(--muted-text);
  font-size: 1.7rem;
  line-height: 1;
}

.modal-close:hover {
  background: var(--accent-soft);
  color: var(--accent-hover);
}

.record-modal .record-form {
  max-width: none;
  margin-top: 1.25rem;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.record-modal .record-form button {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}

.record-modal .record-form button:hover:not(:disabled) {
  background: var(--accent-hover);
}

.record-modal .record-form .form-actions {
  position: sticky;
  bottom: -1px;
  padding-top: 0.8rem;
  background: var(--surface);
}

.record-modal .record-form .form-actions button:last-child {
  border-color: var(--border-color);
  background: transparent;
  color: var(--muted-text);
}

.record-modal .record-form .form-actions button:last-child:hover:not(:disabled) {
  background: var(--accent-soft);
  color: var(--accent-hover);
}

.modal-message {
  margin: 1rem 0 0;
  padding: 0.8rem 0.9rem;
  border: 1px solid #e4c5c0;
  border-radius: 0.45rem;
  background: #fbefec;
  color: var(--danger);
  line-height: 1.5;
}

@media (max-width: 36rem) {
  .modal-backdrop {
    place-items: start center;
    padding: 0.75rem;
  }

  .record-modal {
    max-height: calc(100vh - 1.5rem);
    padding: 1.15rem;
  }
}
</style>
