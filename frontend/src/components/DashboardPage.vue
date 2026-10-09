<script setup>
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue';
import { ApiClientError, createRequestId, getDashboard } from '../apiClient.js';
import { APP_CONTEXT_KEY } from '../appContext.js';
import { useApiMutation } from '../composables/useApiMutation.js';
import { metricDataStatusLabel } from '../presentationMaps.js';
import DashboardGrid from './DashboardGrid.vue';
import AppDialog from './common/AppDialog.vue';

const periods = [
  { value: 'DAY', label: '日' },
  { value: 'WEEK', label: '周' },
  { value: 'MONTH', label: '月' },
  { value: 'YEAR', label: '年' },
];
const props = defineProps({ canEdit: { type: Boolean, default: false } });
const appContext = inject(APP_CONTEXT_KEY, null);

const pad = (value) => String(value).padStart(2, '0');
function localDate() {
  const now = new Date();
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
const granularity = ref('MONTH');
const anchor = ref(localDate());
const dashboard = ref(null);
const loading = ref(true);
const error = ref('');
const message = ref('');
const editing = ref(false);
const saving = ref(false);
const resetConfirmOpen = ref(false);
const draftItems = ref([]);
const layoutRevision = ref(null);
const layoutMutation = useApiMutation();
const layoutMutationState = computed(() => layoutMutation.state.value);
const layoutMutationIntent = ref(null);
let requestGeneration = 0;
let controller = null;

const visibleItems = computed(() => {
  const items = editing.value ? draftItems.value : (dashboard.value?.layout?.items || []);
  return [...items].sort((a, b) => a.y - b.y || a.x - b.x || a.widgetId.localeCompare(b.widgetId));
});

const heroCard = computed(() => {
  const cards = dashboard.value?.cards || [];
  for (const metricId of ['net-assets', 'available-cash', 'net-result']) {
    const match = cards.find((card) => card.metric?.id === metricId);
    if (match) return match;
  }
  return cards[0] || null;
});

function formatHeroValue(card) {
  const value = card?.value;
  if (!value || value.value === null || value.value === undefined) return '—';
  const numeric = Number(value.value);
  if (!Number.isFinite(numeric)) return String(value.value);
  const precision = Number.isInteger(value.precision) ? value.precision : (card.metric?.precision ?? 2);
  const rendered = numeric.toLocaleString('zh-CN', { minimumFractionDigits: precision, maximumFractionDigits: precision });
  if (value.displayFormat === 'PERCENT') return `${rendered}%`;
  if (value.displayFormat === 'INTEGER') return rendered;
  return value.displayFormat === 'CURRENCY' ? `¥${rendered}` : rendered;
}

function heroChange(card) {
  const percent = Number(card?.change?.percent);
  if (Number.isFinite(percent)) return `${percent >= 0 ? '+' : ''}${percent.toFixed(1)}%`;
  return '暂无变化';
}

function cloneItems(items) {
  return (items || []).map((item) => ({ ...item }));
}

function errorText(value) {
  return value instanceof ApiClientError ? value.message : '总览暂时无法读取，请重试。';
}

async function load() {
  requestGeneration += 1;
  const generation = requestGeneration;
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = '';
  try {
    const result = await getDashboard({ granularity: granularity.value, anchor: anchor.value, signal: controller.signal });
    if (generation !== requestGeneration) return;
    dashboard.value = result.payload.data;
    layoutRevision.value = dashboard.value.layout?.revision ?? null;
    if (!editing.value) draftItems.value = cloneItems(dashboard.value.layout?.items);
  } catch (caught) {
    if (caught?.name === 'AbortError' || caught?.code === 'REQUEST_ABORTED') return;
    if (generation === requestGeneration) error.value = errorText(caught);
  } finally {
    if (generation === requestGeneration) loading.value = false;
  }
}

function beginEdit() {
  if (!props.canEdit || !dashboard.value || loading.value) return;
  layoutMutation.clear();
  layoutMutationIntent.value = null;
  draftItems.value = cloneItems(dashboard.value?.layout?.items);
  layoutRevision.value = dashboard.value?.layout?.revision ?? null;
  message.value = '';
  editing.value = true;
}

function cancelEdit() {
  if (layoutMutationState.value === 'pending-confirmation') return;
  draftItems.value = cloneItems(dashboard.value?.layout?.items);
  editing.value = false;
  message.value = '已取消布局修改。';
}

async function saveLayout() {
  if (!props.canEdit || !editing.value || saving.value) return;
  saving.value = true;
  message.value = '';
  layoutMutationIntent.value = { kind: 'save', successMessage: '布局已保存。' };
  try {
    const result = await layoutMutation.submit({ method: 'PUT', path: '/api/v1/dashboard/layout', body: { items: draftItems.value }, ifMatch: `"${layoutRevision.value}"`, idempotencyKey: createRequestId() });
    await completeLayoutMutation(result);
  } catch (caught) {
    message.value = caught?.status === 409 || caught?.status === 428 ? '布局已在其他操作中更新，请重新加载后再保存。' : errorText(caught);
  } finally {
    saving.value = false;
  }
}

async function resetLayout() {
  if (!props.canEdit || !editing.value || saving.value) return;
  resetConfirmOpen.value = true;
}

function cancelResetConfirm() {
  if (!saving.value && layoutMutationState.value !== 'pending-confirmation') resetConfirmOpen.value = false;
}

async function confirmResetLayout() {
  if (!props.canEdit || !editing.value || saving.value) return;
  saving.value = true;
  message.value = '';
  layoutMutationIntent.value = { kind: 'reset', successMessage: '已恢复默认布局。' };
  try {
    const result = await layoutMutation.submit({ method: 'POST', path: '/api/v1/dashboard/layout/reset', body: {}, ifMatch: `"${layoutRevision.value}"`, idempotencyKey: createRequestId() });
    await completeLayoutMutation(result);
  } catch (caught) {
    message.value = errorText(caught);
  } finally {
    saving.value = false;
  }
}

async function completeLayoutMutation(response) {
  const layout = response?.payload?.data?.layout;
  if (!layout) return;
  appContext?.commitMutation(response.payload.meta);
  dashboard.value = { ...dashboard.value, layout };
  draftItems.value = cloneItems(layout.items);
  layoutRevision.value = layout.revision;
  const intent = layoutMutationIntent.value;
  message.value = intent?.successMessage || '布局操作已完成。';
  if (intent?.kind === 'reset') resetConfirmOpen.value = false;
  editing.value = false;
  layoutMutationIntent.value = null;
}

async function retryLayoutMutation() {
  if (!layoutMutationIntent.value || saving.value) return;
  saving.value = true;
  try { const result = await layoutMutation.retrySame(); await completeLayoutMutation(result); }
  catch (caught) { message.value = errorText(caught); }
  finally { saving.value = false; }
}

async function queryLayoutMutation() {
  if (!layoutMutationIntent.value || saving.value) return;
  saving.value = true;
  try {
    const result = await layoutMutation.queryOperation();
    if (layoutMutationState.value === 'success') await completeLayoutMutation(result);
    else message.value = '布局操作仍在处理中，请稍后查询或使用相同操作重试。';
  } catch (caught) { message.value = errorText(caught); }
  finally { saving.value = false; }
}

function clampItem(item) {
  const minW = item.minW ?? 3;
  const minH = item.minH ?? 2;
  const maxW = item.maxW ?? 12;
  const maxH = item.maxH ?? 6;
  item.w = Math.max(minW, Math.min(maxW, item.w));
  item.h = Math.max(minH, Math.min(maxH, item.h));
  item.x = Math.max(0, Math.min(12 - item.w, item.x));
  item.y = Math.max(0, item.y);
}

function updateItem(widgetId, update) {
  const item = draftItems.value.find((entry) => entry.widgetId === widgetId);
  if (!item) return;
  const candidate = { ...item, ...update };
  clampItem(candidate);
  if (draftItems.value.some((entry) => entry.widgetId !== widgetId && overlaps(candidate, entry))) return;
  Object.assign(item, candidate);
}

function overlaps(left, right) {
  return left.x < right.x + right.w && right.x < left.x + left.w
    && left.y < right.y + right.h && right.y < left.y + left.h;
}

function keyboardAdjust({ widgetId, property, amount }) {
  if (!editing.value) return;
  const item = draftItems.value.find((entry) => entry.widgetId === widgetId);
  if (item) updateItem(widgetId, { [property]: item[property] + amount });
}

function gridLayoutChanged(changed) {
  const positions = new Map(changed.map((item) => [item.widgetId, item]));
  draftItems.value = draftItems.value.map((item) => positions.has(item.widgetId)
    ? { ...item, ...positions.get(item.widgetId) } : item);
}

function navigatePeriod(direction) {
  if (editing.value || loading.value || !dashboard.value) return;
  const period = dashboard.value.period || {};
  const nextAnchor = direction === 'previous'
    ? dashboard.value.previousPeriod?.start
    : direction === 'next' ? period.endExclusive : localDate();
  if (nextAnchor) anchor.value = nextAnchor;
}

watch([granularity, anchor], () => { if (!editing.value) void load(); });
onMounted(load);
onUnmounted(() => { requestGeneration += 1; controller?.abort(); });
</script>

<template>
  <section class="dashboard-page" aria-labelledby="dashboard-page-title">
    <div class="dashboard-heading">
      <div>
        <p class="eyebrow">FINANCIAL OVERVIEW</p>
        <h2 id="dashboard-page-title" tabindex="-1">财务总览</h2>
        <p class="dashboard-lede">从日、周、月、年四个层次查看账本表现。</p>
      </div>
      <div class="dashboard-actions">
        <button v-if="props.canEdit && !editing" type="button" :disabled="loading || !dashboard" @click="beginEdit">编辑布局</button>
        <template v-else-if="editing">
          <button type="button" class="retry-button" :disabled="saving" @click="saveLayout">{{ saving ? '保存中…' : '保存布局' }}</button>
          <button type="button" class="secondary-button" :disabled="saving || layoutMutationState === 'pending-confirmation'" @click="resetLayout">恢复默认并保存</button>
          <button type="button" class="secondary-button" :disabled="saving || layoutMutationState === 'pending-confirmation'" @click="cancelEdit">取消</button>
        </template>
      </div>
    </div>

    <div v-if="dashboard" class="dashboard-balance" aria-label="当前余额概览">
      <div>
        <p class="balance-label">当前余额</p>
        <p class="balance-value">{{ formatHeroValue(heroCard) }}</p>
        <p class="balance-caption">{{ heroCard?.metric?.name || '净资产' }} · {{ dashboard.period?.label || dashboard.period?.start }}</p>
      </div>
      <div class="balance-change">
        <span>较上期</span>
        <strong>{{ heroChange(heroCard) }}</strong>
      </div>
    </div>

    <div class="dashboard-toolbar">
      <div class="period-tabs" role="group" aria-label="总览时间粒度">
        <button v-for="period in periods" :key="period.value" type="button" :aria-pressed="granularity === period.value" :class="{ selected: granularity === period.value }" :disabled="editing" @click="granularity = period.value">{{ period.label }}</button>
      </div>
      <div class="period-navigation" role="group" aria-label="期间导航"><button type="button" :disabled="editing || loading || !dashboard" @click="navigatePeriod('previous')">上一期</button><button type="button" :disabled="editing || loading || !dashboard" @click="navigatePeriod('current')">本期</button><button type="button" :disabled="editing || loading || !dashboard" @click="navigatePeriod('next')">下一期</button></div>
      <label class="anchor-picker">期间锚点 <input v-model="anchor" type="date" :disabled="editing"></label>
    </div>

    <p v-if="message" class="dashboard-message" role="status">{{ message }}</p>
    <div v-if="loading" class="dashboard-state" role="status">正在读取总览…</div>
    <div v-else-if="error" class="dashboard-state dashboard-error" role="alert">{{ error }} <button type="button" @click="load">重试</button></div>
    <div v-else-if="dashboard" class="dashboard-content">
      <div class="period-summary">
        <span>{{ dashboard.period?.label || dashboard.period?.start }}</span>
        <span class="period-status">{{ metricDataStatusLabel(dashboard.period?.status) }}</span>
        <span>对比 {{ dashboard.previousPeriod?.start }} 起</span>
      </div>
      <DashboardGrid :items="visibleItems" :cards="dashboard.cards || []" :editing="editing"
        :period-label="dashboard.period?.label || dashboard.period?.start || ''"
        aria-label="财务指标卡片" @layout-change="gridLayoutChanged" @adjust="keyboardAdjust" />
      <p v-if="!visibleItems.length" class="dashboard-state">当前没有可展示的指标卡片。</p>
    </div>
    <div v-if="layoutMutationState === 'pending-confirmation'" class="dashboard-message dashboard-pending" role="status"><span>布局操作结果尚未确认。</span><button type="button" :disabled="saving" @click="queryLayoutMutation">查询结果</button><button type="button" :disabled="saving" @click="retryLayoutMutation">使用相同操作重试</button></div>
    <AppDialog :open="resetConfirmOpen" title-id="dashboard-reset-title" :busy="saving" :close-on-backdrop="false" @close="cancelResetConfirm">
      <section class="dashboard-confirm-modal">
        <div class="modal-heading"><h3 id="dashboard-reset-title">恢复默认布局</h3><button type="button" class="modal-close" :disabled="saving" aria-label="关闭确认框" @click="cancelResetConfirm">×</button></div>
        <p>确定恢复默认布局吗？当前未保存的布局调整将被丢弃。</p>
        <div class="dashboard-confirm-actions"><button type="button" class="secondary-button" :disabled="saving || layoutMutationState === 'pending-confirmation'" @click="cancelResetConfirm">取消</button><button type="button" :disabled="saving" @click="confirmResetLayout">{{ saving ? '恢复中…' : '确认恢复' }}</button></div>
        <div v-if="layoutMutationState === 'pending-confirmation'" class="dashboard-confirm-actions"><button type="button" :disabled="saving" @click="queryLayoutMutation">查询结果</button><button type="button" :disabled="saving" @click="retryLayoutMutation">使用相同操作重试</button></div>
      </section>
    </AppDialog>
  </section>
</template>
