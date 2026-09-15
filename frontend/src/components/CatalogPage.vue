<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ApiClientError, createRequestId, requestApiJson } from '../apiClient.js';

const categories = ref([]);
const accounts = ref([]);
const categoryPage = ref({ nextCursor: null, hasMore: false });
const accountPage = ref({ nextCursor: null, hasMore: false });
const includeArchived = ref(false);
const loading = ref({ categories: true, accounts: true });
const errors = ref({ categories: '', accounts: '' });
const message = ref('');
const categoryForm = ref(null);
const accountForm = ref(null);
const categoryFieldError = ref('');
const accountFieldError = ref('');
const confirmingArchive = ref(null);
const mergeForm = ref(false);
const mergeSourceId = ref('');
const mergeTargetId = ref('');
const confirmingMerge = ref(false);
const mutationState = ref({ kind: 'idle', message: '' });
const pendingMutation = ref(null);
const writesDisabled = ref(false);

const categoryParents = computed(() => categories.value.filter((item) => item.status === 'ACTIVE' && item.parentId === null));
const visibleCategories = computed(() => categories.value);
const visibleAccounts = computed(() => accounts.value);
const mergeSources = computed(() => categories.value.filter((item) => item.status === 'ACTIVE' && !item.isSystem));
const mergeTargets = computed(() => categories.value.filter((item) => item.status === 'ACTIVE' && item.canUseForRecords === true && item.id !== mergeSourceId.value && item.parentId !== mergeSourceId.value));

let categoryGeneration = 0;
let accountGeneration = 0;
let categoryController = null;
let accountController = null;

function isUuid(value) { return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value); }
function isMoney(value) { return typeof value === 'string' && /^-?(0|[1-9][0-9]*)\.[0-9]{2}$/.test(value); }
function isCategory(value) {
  return value && isUuid(value.id) && Number.isSafeInteger(value.revision) && value.revision >= 0
    && typeof value.name === 'string' && value.name.length > 0 && (value.parentId === null || isUuid(value.parentId))
    && Array.isArray(value.recordTypes) && typeof value.isSystem === 'boolean' && typeof value.isLegacyCustom === 'boolean'
    && ['ACTIVE', 'ARCHIVED'].includes(value.status) && Number.isInteger(value.sortOrder)
    && typeof value.defaultRecognitionMethod === 'string' && (value.recommendedDepreciationMethod === null || typeof value.recommendedDepreciationMethod === 'string')
    && typeof value.canUseForRecords === 'boolean';
}
function isAccount(value) {
  return value && isUuid(value.id) && Number.isSafeInteger(value.revision) && value.revision >= 0 && typeof value.name === 'string'
    && ['CASH', 'BANK', 'WALLET', 'CREDIT', 'LOAN', 'OTHER_ASSET', 'OTHER_LIABILITY'].includes(value.kind)
    && ['ASSET', 'LIABILITY'].includes(value.balanceSide) && /^\d{4}-\d{2}-\d{2}$/.test(value.openingOn)
    && isMoney(value.openingBalance) && isMoney(value.balance) && value.currency === 'CNY'
    && typeof value.includeInAvailableCash === 'boolean' && typeof value.isSystem === 'boolean' && ['ACTIVE', 'ARCHIVED'].includes(value.status);
}
function parseList(result, kind) {
  const data = result.payload?.data; const page = data?.page; const revision = result.payload?.meta?.dataRevision;
  if (!Array.isArray(data?.items) || !data.items.every(kind === 'categories' ? isCategory : isAccount) || !page
      || typeof page.hasMore !== 'boolean' || (page.nextCursor !== null && typeof page.nextCursor !== 'string')
      || !Number.isSafeInteger(revision) || revision < 0 || result.etag !== `"${revision}"`) throw new ApiClientError('本地服务返回了无法读取的数据。', { code: 'INVALID_RESPONSE', retryable: false });
  return { items: data.items, nextCursor: page.nextCursor, hasMore: page.hasMore };
}
function errorMessage(error) {
  if (!(error instanceof ApiClientError)) return '本地服务暂时不可用，请重试。';
  if (error.code === 'INVALID_RESPONSE') return '本地服务返回了无法读取的数据。';
  if (error.status === 401) return '登录状态已失效，请重新启动应用。';
  if (error.status === 423) return '数据正在恢复，请稍后重试。';
  if (error.status === 409 || error.status === 428) return '数据已被其他操作更新，请刷新后重试。';
  return error.message || '本地服务暂时不可用，请重试。';
}
function cancelRead(kind) { (kind === 'categories' ? categoryController : accountController)?.abort(); }
async function load(kind, { append = false } = {}) {
  const generation = kind === 'categories' ? ++categoryGeneration : ++accountGeneration; cancelRead(kind);
  const controller = new AbortController(); if (kind === 'categories') categoryController = controller; else accountController = controller;
  const timeout = setTimeout(() => controller.abort(), 15000); loading.value = { ...loading.value, [kind]: true }; errors.value = { ...errors.value, [kind]: '' };
  const page = kind === 'categories' ? categoryPage.value : accountPage.value; const params = new URLSearchParams({ includeArchived: String(includeArchived.value), limit: '200' });
  if (append && page.nextCursor) params.set('cursor', page.nextCursor);
  try {
    const result = await requestApiJson(`/api/v1/${kind}?${params.toString()}`, { signal: controller.signal });
    if (generation !== (kind === 'categories' ? categoryGeneration : accountGeneration)) return;
    const parsed = parseList(result, kind); const target = kind === 'categories' ? categories : accounts; const known = new Set(target.value.map((item) => item.id));
    const incoming = append ? parsed.items.filter((item) => !known.has(item.id)) : parsed.items; target.value = append ? [...target.value, ...incoming] : incoming;
    if (kind === 'categories') categoryPage.value = { nextCursor: parsed.nextCursor, hasMore: parsed.hasMore }; else accountPage.value = { nextCursor: parsed.nextCursor, hasMore: parsed.hasMore };
  } catch (error) {
    if (generation !== (kind === 'categories' ? categoryGeneration : accountGeneration)) return;
    errors.value = { ...errors.value, [kind]: error?.code === 'REQUEST_ABORTED' && controller.signal.aborted ? `${kind === 'categories' ? '分类' : '账户'}读取超时，请重试。` : errorMessage(error) };
  } finally {
    clearTimeout(timeout); if (kind === 'categories' && categoryController === controller) categoryController = null; if (kind === 'accounts' && accountController === controller) accountController = null; loading.value = { ...loading.value, [kind]: false };
  }
}
function reload() { categoryPage.value = { nextCursor: null, hasMore: false }; accountPage.value = { nextCursor: null, hasMore: false }; void load('categories'); void load('accounts'); }
function toggleArchived() { categories.value = []; accounts.value = []; reload(); }
function startCategory(category = null) { categoryFieldError.value = ''; categoryForm.value = category ? { id: category.id, name: category.name, parentId: category.parentId, revision: category.revision } : { id: null, name: '', parentId: null, revision: null }; }
function startAccount(account = null) { accountFieldError.value = ''; accountForm.value = account ? { id: account.id, name: account.name, kind: account.kind, openingOn: account.openingOn, openingBalance: account.openingBalance, includeInAvailableCash: account.includeInAvailableCash, revision: account.revision } : { id: null, name: '', kind: 'BANK', openingOn: localDate(), openingBalance: '0.00', includeInAvailableCash: true, revision: null }; }
function localDate() { const date = new Date(); const pad = (value) => String(value).padStart(2, '0'); return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`; }
function cancelForms() { categoryForm.value = null; accountForm.value = null; categoryFieldError.value = ''; accountFieldError.value = ''; }
function categoryParentName(category) { return category.parentId ? categories.value.find((item) => item.id === category.parentId)?.name || '未知父分类' : '顶级'; }
function accountSide(kind) { return ['CREDIT', 'LOAN', 'OTHER_LIABILITY'].includes(kind) ? 'LIABILITY' : 'ASSET'; }
function beginMutation(action, request) { pendingMutation.value = { action, request }; void runMutation(pendingMutation.value); }
function mutationSuccessMessage(pending) {
  if (pending.action === 'merge') return '分类已合并。';
  const resource = pending.action === 'category' ? '分类' : '账户';
  if (pending.request.method === 'POST') return `${resource}已创建。`;
  if (pending.request.method === 'PUT') return `${resource}已更新。`;
  return `${resource}已归档。`;
}
async function runMutation(pending) {
  if (!pending) return; mutationState.value = { kind: 'loading', message: '正在保存…' }; const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 30000);
  try {
    await requestApiJson(pending.request.path, { method: pending.request.method, body: pending.request.body, ifMatch: pending.request.ifMatch, idempotencyKey: pending.request.idempotencyKey, signal: controller.signal });
    pendingMutation.value = null; mutationState.value = { kind: 'success', message: mutationSuccessMessage(pending) }; confirmingArchive.value = null; confirmingMerge.value = false; mergeForm.value = false; cancelForms();
    if (pending.action === 'category' || pending.action === 'merge') await load('categories'); if (pending.action === 'account') await load('accounts');
  } catch (error) {
    if (error.status === 423) writesDisabled.value = true; if (error.status === 409 || error.status === 428) pendingMutation.value = null;
    mutationState.value = { kind: 'error', message: error.code === 'REQUEST_ABORTED' ? '提交结果待确认：请查询结果或使用相同请求重试。' : errorMessage(error) };
    if (error.fieldErrors?.name) categoryFieldError.value = String(error.fieldErrors.name); if (error.fieldErrors?.openingBalance) accountFieldError.value = String(error.fieldErrors.openingBalance);
  } finally { clearTimeout(timeout); }
}
function retryPending() { if (pendingMutation.value) void runMutation(pendingMutation.value); }
async function queryPendingOperation() {
  if (!pendingMutation.value) return; mutationState.value = { kind: 'loading', message: '正在查询操作结果…' };
  try { const result = await requestApiJson(`/api/v1/operations/${encodeURIComponent(pendingMutation.value.request.idempotencyKey)}`); if (result.payload?.data?.status !== 'COMPLETED') { mutationState.value = { kind: 'error', message: '操作尚未完成，请稍后查询。' }; return; } const action = pendingMutation.value.action; pendingMutation.value = null; mutationState.value = { kind: 'success', message: '保存成功。' }; if (action === 'category' || action === 'merge') await load('categories'); else await load('accounts'); }
  catch (error) { mutationState.value = { kind: 'error', message: error.status === 404 ? '暂未找到操作结果，请稍后查询。' : errorMessage(error) }; }
}
async function saveCategory() {
  const form = categoryForm.value; const name = form.name.trim(); if (Array.from(name).length < 1 || Array.from(name).length > 100) { categoryFieldError.value = '分类名称长度必须为 1 到 100 个字符。'; return; }
  if (form.parentId && !categoryParents.value.some((item) => item.id === form.parentId)) { categoryFieldError.value = '父分类必须是当前活动顶级分类。'; return; }
  const creating = !form.id; beginMutation('category', { method: creating ? 'POST' : 'PUT', path: creating ? '/api/v1/categories' : `/api/v1/categories/${form.id}`, body: { ...(creating ? { id: createRequestId() } : {}), name, parentId: form.parentId || null }, ifMatch: creating ? undefined : `"${form.revision}"`, idempotencyKey: createRequestId() });
}
function askArchive(kind, item) { if (writesDisabled.value || item.isSystem || item.status !== 'ACTIVE') return; confirmingArchive.value = { kind, id: item.id }; }
function cancelArchive() { confirmingArchive.value = null; }
function confirmArchive(kind, item) { if (!confirmingArchive.value || confirmingArchive.value.kind !== kind || confirmingArchive.value.id !== item.id) return; beginMutation(kind, { method: 'DELETE', path: `/api/v1/${kind === 'category' ? 'categories' : 'accounts'}/${item.id}`, ifMatch: `"${item.revision}"`, idempotencyKey: createRequestId() }); }
async function saveAccount() {
  const form = accountForm.value; const name = form.name.trim(); if (Array.from(name).length < 1 || Array.from(name).length > 100) { accountFieldError.value = '账户名称长度必须为 1 到 100 个字符。'; return; }
  if (!/^-?(0|[1-9][0-9]*)(\.[0-9]{1,2})?$/.test(form.openingBalance) || (form.openingBalance.startsWith('-') && /^-?0(?:\.0{1,2})?$/.test(form.openingBalance))) { accountFieldError.value = '期初余额必须是规范金额字符串。'; return; }
  const creating = !form.id; beginMutation('account', { method: creating ? 'POST' : 'PUT', path: creating ? '/api/v1/accounts' : `/api/v1/accounts/${form.id}`, body: { ...(creating ? { id: createRequestId() } : {}), name, kind: form.kind, openingOn: form.openingOn, openingBalance: form.openingBalance, currency: 'CNY', includeInAvailableCash: accountSide(form.kind) === 'LIABILITY' ? false : form.includeInAvailableCash }, ifMatch: creating ? undefined : `"${form.revision}"`, idempotencyKey: createRequestId() });
}
function openMerge() { mergeForm.value = true; mergeSourceId.value = ''; mergeTargetId.value = ''; confirmingMerge.value = false; }
function cancelMerge() { mergeForm.value = false; confirmingMerge.value = false; }
function askMerge() { if (mergeSourceId.value && mergeTargetId.value && mergeSourceId.value !== mergeTargetId.value) confirmingMerge.value = true; }
function confirmMerge() { if (!confirmingMerge.value) return; const source = categories.value.find((item) => item.id === mergeSourceId.value); const target = categories.value.find((item) => item.id === mergeTargetId.value); if (!source || !target) return; beginMutation('merge', { method: 'POST', path: `/api/v1/categories/${target.id}/merge`, body: { sources: [{ id: source.id, expectedRevision: source.revision }] }, ifMatch: `"${target.revision}"`, idempotencyKey: createRequestId() }); }
onMounted(reload);
onUnmounted(() => { categoryGeneration += 1; accountGeneration += 1; cancelRead('categories'); cancelRead('accounts'); });
</script>

<template>
  <section class="catalog-page" aria-labelledby="catalog-page-title">
    <div class="catalog-heading"><div><h2 id="catalog-page-title" tabindex="-1">分类与账户</h2><p>管理收支记录使用的分类和资金账户。</p></div><label class="archived-toggle"><input v-model="includeArchived" type="checkbox" @change="toggleArchived"> 显示已归档</label></div>
    <p v-if="mutationState.kind !== 'idle'" class="catalog-message" :class="`message-${mutationState.kind}`" role="status">{{ mutationState.message }}<button v-if="mutationState.kind === 'error' && pendingMutation" type="button" @click="retryPending">使用相同请求重试</button><button v-if="mutationState.kind === 'error' && pendingMutation" type="button" @click="queryPendingOperation">查询结果</button></p>
    <div class="catalog-columns">
      <section class="catalog-card" aria-labelledby="categories-title"><div class="section-heading"><h3 id="categories-title">分类</h3><div class="row-actions"><button type="button" :disabled="writesDisabled" @click="startCategory()">新增分类</button><button type="button" :disabled="writesDisabled" @click="openMerge">合并来源</button></div></div><div v-if="loading.categories && !categories.length" class="catalog-state" role="status">正在读取分类…</div><div v-else-if="errors.categories" class="catalog-state catalog-error" role="alert">{{ errors.categories }} <button type="button" @click="load('categories')">重试</button></div><ul v-else class="catalog-list"><li v-for="category in visibleCategories" :key="category.id" :class="{ child: category.parentId }"><div><strong>{{ category.name }}</strong><span>{{ categoryParentName(category) }} · {{ category.isSystem ? '系统' : '自定义' }} · {{ category.canUseForRecords ? '可用于记录' : '仅分组' }} · {{ category.status }}</span></div><div class="row-actions"><button v-if="!category.isSystem && category.status === 'ACTIVE'" type="button" :disabled="writesDisabled" @click="startCategory(category)">编辑</button><button v-if="!category.isSystem && category.status === 'ACTIVE'" type="button" :disabled="writesDisabled" @click="askArchive('category', category)">归档</button><div v-if="confirmingArchive?.kind === 'category' && confirmingArchive.id === category.id" class="confirm-actions" role="group" :aria-label="`确认归档 ${category.name}`"><span>归档后不可恢复。</span><button type="button" :disabled="writesDisabled" @click="confirmArchive('category', category)">确认归档</button><button type="button" @click="cancelArchive">取消</button></div></div></li></ul>
        <form v-if="categoryForm" class="inline-form" @submit.prevent="saveCategory"><h4>{{ categoryForm.id ? '编辑分类' : '新增分类' }}</h4><label>名称<input v-model="categoryForm.name" maxlength="100" autofocus :aria-invalid="categoryFieldError ? 'true' : 'false'"></label><p v-if="categoryFieldError" class="field-error" role="alert">{{ categoryFieldError }}</p><label>父分类<select v-model="categoryForm.parentId"><option :value="null">不设父分类</option><option v-for="parent in categoryParents" :key="parent.id" :value="parent.id">{{ parent.name }}</option></select></label><div class="row-actions"><button type="submit" :disabled="writesDisabled">保存</button><button type="button" @click="cancelForms">取消</button></div></form>
        <form v-if="mergeForm" class="inline-form" @submit.prevent="askMerge"><h4>合并分类</h4><label>来源分类<select v-model="mergeSourceId"><option value="">请选择来源</option><option v-for="source in mergeSources" :key="source.id" :value="source.id">{{ source.name }}</option></select></label><label>目标分类<select v-model="mergeTargetId"><option value="">请选择目标</option><option v-for="target in mergeTargets" :key="target.id" :value="target.id">{{ target.name }}</option></select></label><div v-if="confirmingMerge" class="confirm-actions" role="group"><span>确认后来源分类会被归档。</span><button type="button" :disabled="writesDisabled" @click="confirmMerge">确认合并</button><button type="button" @click="cancelMerge">取消</button></div><div v-else class="row-actions"><button type="submit" :disabled="writesDisabled || !mergeSourceId || !mergeTargetId">继续</button><button type="button" @click="cancelMerge">取消</button></div></form>
        <button v-if="categoryPage.hasMore" type="button" :disabled="loading.categories" @click="load('categories', { append: true })">加载更多分类</button>
      </section>
      <section class="catalog-card" aria-labelledby="accounts-title"><div class="section-heading"><h3 id="accounts-title">账户</h3><button type="button" :disabled="writesDisabled" @click="startAccount()">新增账户</button></div><div v-if="loading.accounts && !accounts.length" class="catalog-state" role="status">正在读取账户…</div><div v-else-if="errors.accounts" class="catalog-state catalog-error" role="alert">{{ errors.accounts }} <button type="button" @click="load('accounts')">重试</button></div><ul v-else class="catalog-list"><li v-for="account in visibleAccounts" :key="account.id"><div><strong>{{ account.name }}</strong><span>{{ account.kind }} · {{ account.balanceSide }} · 期初 {{ account.openingBalance }} · 余额 {{ account.balance }} {{ account.currency }} · {{ account.status }}</span></div><div class="row-actions"><button v-if="account.status === 'ACTIVE'" type="button" :disabled="writesDisabled" @click="startAccount(account)">编辑</button><button v-if="!account.isSystem && account.status === 'ACTIVE'" type="button" :disabled="writesDisabled" @click="askArchive('account', account)">归档</button><div v-if="confirmingArchive?.kind === 'account' && confirmingArchive.id === account.id" class="confirm-actions" role="group" :aria-label="`确认归档 ${account.name}`"><span>归档后不可恢复。</span><button type="button" :disabled="writesDisabled" @click="confirmArchive('account', account)">确认归档</button><button type="button" @click="cancelArchive">取消</button></div></div></li></ul>
        <form v-if="accountForm" class="inline-form" @submit.prevent="saveAccount"><h4>{{ accountForm.id ? '编辑账户' : '新增账户' }}</h4><label>名称<input v-model="accountForm.name" maxlength="100" autofocus></label><label>类型<select v-model="accountForm.kind"><option value="CASH">CASH</option><option value="BANK">BANK</option><option value="WALLET">WALLET</option><option value="CREDIT">CREDIT</option><option value="LOAN">LOAN</option><option value="OTHER_ASSET">OTHER_ASSET</option><option value="OTHER_LIABILITY">OTHER_LIABILITY</option></select></label><label>开户日期<input v-model="accountForm.openingOn" type="date"></label><label>期初余额<input v-model="accountForm.openingBalance" inputmode="decimal"></label><label><input v-model="accountForm.includeInAvailableCash" type="checkbox" :disabled="accountSide(accountForm.kind) === 'LIABILITY'"> 计入可用现金（负债账户不可用）</label><p v-if="accountFieldError" class="field-error" role="alert">{{ accountFieldError }}</p><div class="row-actions"><button type="submit" :disabled="writesDisabled">保存</button><button type="button" @click="cancelForms">取消</button></div></form>
        <button v-if="accountPage.hasMore" type="button" :disabled="loading.accounts" @click="load('accounts', { append: true })">加载更多账户</button>
      </section>
    </div>
  </section>
</template>

<style scoped>
.catalog-page { display: grid; gap: 1rem; max-width: 72rem; } .catalog-heading, .section-heading, .row-actions, .confirm-actions { display: flex; flex-wrap: wrap; align-items: center; gap: .6rem; } .catalog-heading, .section-heading { justify-content: space-between; } h2, h3, h4, p { margin-top: 0; } .catalog-heading p { margin-bottom: 0; color: #5d6675; }
.catalog-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; } .catalog-card, .inline-form { border: 1px solid #d7dce5; border-radius: .7rem; padding: .85rem; background: white; } .catalog-card { display: grid; gap: .7rem; align-content: start; } .section-heading h3 { margin-bottom: 0; }
button { min-height: 2.3rem; padding: .4rem .7rem; border: 1px solid #295ecb; border-radius: .45rem; background: #295ecb; color: white; cursor: pointer; } button:disabled { opacity: .55; cursor: not-allowed; } .row-actions button, .confirm-actions button:last-child { background: white; color: #295ecb; }
.catalog-list { list-style: none; padding: 0; margin: 0; display: grid; gap: .5rem; } .catalog-list li { display: flex; justify-content: space-between; gap: .7rem; padding: .65rem; border: 1px solid #edf0f4; border-radius: .45rem; } .catalog-list li.child { margin-left: 1rem; } .catalog-list span { display: block; margin-top: .2rem; color: #5d6675; font-size: .9rem; overflow-wrap: anywhere; }
.inline-form { display: grid; gap: .6rem; } .inline-form h4 { margin-bottom: 0; } .inline-form label { display: grid; gap: .25rem; } input, select { min-height: 2.3rem; border: 1px solid #aeb7c6; border-radius: .4rem; padding: .35rem .5rem; font: inherit; } .inline-form label:last-of-type { display: flex; align-items: center; } .inline-form label:last-of-type input { min-height: auto; } .catalog-state, .catalog-message { padding: .65rem .75rem; border-radius: .45rem; background: #eef4ff; } .message-error, .catalog-error, .field-error { background: #fff1f0; color: #8a1c13; } .message-success { background: #edf8f0; color: #176b3a; } .field-error { margin: 0; padding: .4rem; } .confirm-actions { justify-content: flex-end; color: #8a1c13; font-size: .9rem; } .archived-toggle { color: #5d6675; }
@media (max-width: 44rem) { .catalog-columns { grid-template-columns: 1fr; } } @media (max-width: 22rem) { .catalog-list li { display: grid; } .row-actions button, .confirm-actions button { flex: 1; } }
</style>
