<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { ApiClientError, archiveMetric, createRequestId, getMetric, getMetrics, listFormulaVersions,
  previewFormula, requestApiJson, validateFormula } from '../apiClient.js';
import AppDialog from './common/AppDialog.vue';
import FormulaNodeEditor from './FormulaNodeEditor.vue';
import FormulaVersionHistory from './FormulaVersionHistory.vue';
import { cloneNode, defaultNode, FUNCTION_HINTS, nodeStats } from './formulaHelpers.js';
import { useApiMutation } from '../composables/useApiMutation.js';
import { displayFormatLabel, entityStatusLabel, metricDataStatusLabel } from '../presentationMaps.js';

const props = defineProps({
  canWrite: { type: Boolean, default: false },
  canValidate: { type: Boolean, default: false },
  canFormulaWrite: { type: Boolean, default: false },
});

const metrics = ref([]);
const loading = ref(true);
const error = ref('');
const message = ref('');
const includeArchived = ref(false);
const showEditor = ref(false);
const saving = ref(false);
const validating = ref(false);
const previewing = ref(false);
const previewValue = ref(null);
const formulaFieldErrors = ref({});
const validationController = ref(null);
const previewController = ref(null);
const editorMetric = ref(null);
const history = ref([]);
const historyError = ref('');
const historyLoading = ref(false);
const archiveTarget = ref(null);
const form = ref(blankForm());
const formulaMutation = useApiMutation();
const categories = ref([]);
const accounts = ref([]);
const historyHasMore = ref(false);
const historyCursor = ref(null);

const functionHints = FUNCTION_HINTS;

const activeMetricOptions = computed(() => metrics.value.filter((metric) => metric.status === 'ACTIVE'));

function blankForm(metric = null) {
  return {
    id: metric?.id || `custom-${createRequestId()}`, revision: metric?.revision ?? null, isSystem: metric?.isSystem || false,
    name: metric?.name || '', description: metric?.description || '', displayFormat: metric?.displayFormat || 'CURRENCY',
    precision: metric?.precision ?? 2, visibility: { hidden: metric?.visibility?.hidden || false,
      dashboardEnabled: metric?.visibility?.dashboardEnabled ?? true }, formulaRoot: cloneNode(metric?.formula?.ast?.root || defaultNode('CONSTANT')),
  };
}

function textError(caught) {
  return caught instanceof ApiClientError ? caught.message : '指标服务暂时不可用，请重试。';
}

async function load() {
  loading.value = true; error.value = '';
  try {
    const response = await getMetrics({ includeArchived: includeArchived.value });
    metrics.value = response.payload?.data?.items || [];
  } catch (caught) { error.value = textError(caught); }
  finally { loading.value = false; }
}

function abortFormulaRequests() {
  validationController.value?.abort();
  previewController.value?.abort();
  validationController.value = null;
  previewController.value = null;
}

function focusFirstFormulaError() {
  void nextTick(() => document.querySelector('[data-formula-error="true"]')?.focus());
}

function openEditor() { editorMetric.value = null; history.value = []; historyError.value = ''; historyHasMore.value = false; historyCursor.value = null; previewValue.value = null; formulaFieldErrors.value = {}; form.value = blankForm(); message.value = ''; showEditor.value = true; void loadReferenceData(); }
function closeEditor() { if (!saving.value && !validating.value && !previewing.value) { abortFormulaRequests(); showEditor.value = false; } }

async function editMetric(summary) {
  if (!props.canWrite || saving.value) return;
  message.value = ''; history.value = []; historyError.value = ''; historyHasMore.value = false; historyCursor.value = null; formulaFieldErrors.value = {};
  try {
    const response = await getMetric(summary.id, { includeArchived: summary.status === 'ARCHIVED' });
    const metric = response.payload?.data?.metric;
    if (!metric) throw new Error('missing metric');
    editorMetric.value = metric;
    form.value = blankForm(metric);
    const root = metric.formula?.ast?.root;
    if (root) hydrateFormula(root);
    showEditor.value = true;
    void loadReferenceData();
    if (metric.formula?.formulaId) void loadHistory(metric.formula.formulaId);
  } catch (caught) { message.value = textError(caught); }
}

async function loadReferenceData() {
  try {
    const [categoryResponse, accountResponse] = await Promise.all([
      requestApiJson('/api/v1/categories?includeArchived=false&limit=200'),
      requestApiJson('/api/v1/accounts?includeArchived=false&limit=200'),
    ]);
    categories.value = categoryResponse.payload?.data?.items || [];
    accounts.value = accountResponse.payload?.data?.items || [];
  } catch {
    categories.value = [];
    accounts.value = [];
  }
}

async function loadHistory(formulaId, { append = false } = {}) {
  if (!formulaId || historyLoading.value) return;
  historyLoading.value = true;
  historyError.value = '';
  try {
    const versions = await listFormulaVersions(formulaId, { cursor: append ? historyCursor.value : undefined });
    const payload = versions.payload?.data;
    const incoming = payload?.items || [];
    history.value = append ? [...history.value, ...incoming] : incoming;
    historyCursor.value = payload?.page?.nextCursor ?? null;
    historyHasMore.value = Boolean(payload?.page?.hasMore);
  } catch (caught) {
    historyError.value = textError(caught);
  } finally { historyLoading.value = false; }
}

function loadMoreHistory() {
  if (editorMetric.value?.formula?.formulaId) void loadHistory(editorMetric.value.formula.formulaId, { append: true });
}

function hydrateFormula(root) {
  form.value.formulaRoot = cloneNode(root);
}

function buildRoot() {
  return cloneNode(form.value.formulaRoot);
}

function draftBody() {
  const root = buildRoot();
  return {
    candidateMetricId: form.value.id,
    displayFormat: form.value.displayFormat,
    formula: { ast: { schemaVersion: 1, root } },
    granularity: 'MONTH',
    anchor: new Date().toISOString().slice(0, 10),
  };
}

async function validateDraft() {
  validationController.value?.abort();
  const controller = new AbortController();
  validationController.value = controller;
  validating.value = true; message.value = ''; formulaFieldErrors.value = {};
  try { await validateFormula(draftBody(), { signal: controller.signal }); message.value = '公式校验通过。'; }
  catch (caught) { if (caught?.code === 'REQUEST_ABORTED') return; formulaFieldErrors.value = caught?.fieldErrors || {}; message.value = textError(caught); focusFirstFormulaError(); }
  finally { if (validationController.value === controller) { validationController.value = null; validating.value = false; } }
}

async function previewDraft() {
  previewController.value?.abort();
  const controller = new AbortController();
  previewController.value = controller;
  previewing.value = true; message.value = ''; formulaFieldErrors.value = {};
  try { const result = await previewFormula(draftBody(), { signal: controller.signal }); previewValue.value = result.payload?.data?.value || null; message.value = '预览结果已更新。'; }
  catch (caught) { if (caught?.code === 'REQUEST_ABORTED') return; formulaFieldErrors.value = caught?.fieldErrors || {}; message.value = textError(caught); focusFirstFormulaError(); }
  finally { if (previewController.value === controller) { previewController.value = null; previewing.value = false; } }
}

async function save() {
  message.value = '';
  if (form.value.isSystem) {
    saving.value = true;
    formulaMutation.clear();
    try {
      await formulaMutation.submit({ method: 'PUT', path: `/api/v1/metrics/${encodeURIComponent(form.value.id)}`, body: { visibility: form.value.visibility }, ifMatch: `"${form.value.revision}"`, idempotencyKey: createRequestId() });
      message.value = '系统指标显示设置已保存。'; showEditor.value = false; await load();
    } catch (caught) { message.value = textError(caught); } finally { saving.value = false; }
    return;
  }
  if (!form.value.name.trim()) { message.value = '请填写指标名称。'; return; }
  const stats = nodeStats(form.value.formulaRoot);
  if (stats.depth > 32 || stats.count > 256) { message.value = '公式过于复杂，请减少嵌套或参数。'; formulaFieldErrors.value = { 'formula.ast.root': message.value }; focusFirstFormulaError(); return; }
  saving.value = true;
  const root = buildRoot();
  const body = {
    ...(editorMetric.value ? {} : { id: form.value.id }),
    name: form.value.name.trim(),
    description: form.value.description,
    displayFormat: form.value.displayFormat,
    precision: Number(form.value.precision),
    visibility: form.value.visibility,
    formula: { ast: { schemaVersion: 1, root } },
  };
  try {
    await formulaMutation.submit(editorMetric.value
      ? { method: 'PUT', path: `/api/v1/metrics/${encodeURIComponent(form.value.id)}`, body, ifMatch: `"${form.value.revision}"`, idempotencyKey: createRequestId() }
      : { method: 'POST', path: '/api/v1/metrics', body, idempotencyKey: createRequestId() });
    message.value = '指标已保存。'; showEditor.value = false; await load();
  } catch (caught) { formulaFieldErrors.value = caught?.fieldErrors || {}; message.value = textError(caught); focusFirstFormulaError(); }
  finally { saving.value = false; }
}

function archive(summary) {
  if (!props.canWrite || summary.isSystem || summary.status !== 'ACTIVE') return;
  archiveTarget.value = summary;
}

function cancelArchive() { if (!saving.value) archiveTarget.value = null; }

async function confirmArchive() {
  const summary = archiveTarget.value;
  if (!summary || !props.canWrite || saving.value) return;
  saving.value = true;
  try {
    await archiveMetric(summary.id, { ifMatch: `"${summary.revision}"`, idempotencyKey: createRequestId() });
    message.value = '指标已归档。'; archiveTarget.value = null; await load();
  } catch (caught) { message.value = textError(caught); }
  finally { saving.value = false; }
}

onMounted(load);
onBeforeUnmount(abortFormulaRequests);
</script>

<template>
  <section class="metrics-page" aria-labelledby="metrics-page-title">
    <div class="metrics-heading">
      <div><p class="eyebrow">METRICS & FORMULAS</p><h2 id="metrics-page-title" tabindex="-1">指标与公式</h2><p>系统指标由服务端计算，自定义指标使用中文提示搭建安全公式。</p></div>
      <button v-if="props.canWrite && props.canFormulaWrite" type="button" class="retry-button" @click="openEditor">新建自定义指标</button>
    </div>
    <div class="metrics-toolbar"><label><input v-model="includeArchived" type="checkbox" @change="load"> 显示已归档</label><button type="button" class="secondary-button" @click="load">刷新</button></div>
    <p v-if="message && !showEditor" class="dashboard-message" role="status">{{ message }}</p>
    <div v-if="loading" class="dashboard-state" role="status">正在读取指标…</div>
    <div v-else-if="error" class="dashboard-state dashboard-error" role="alert">{{ error }} <button type="button" @click="load">重试</button></div>
    <div v-else class="metrics-list">
      <article v-for="metric in metrics" :key="metric.id" class="metric-definition-card">
        <div><span class="metric-type">{{ metric.isSystem ? '系统指标' : '自定义指标' }}</span><h3>{{ metric.name }}</h3><p>{{ metric.description || '暂无说明' }}</p></div>
        <div><dl><div><dt>格式</dt><dd>{{ displayFormatLabel(metric.displayFormat) }} · {{ metric.precision }} 位</dd></div><div><dt>状态</dt><dd>{{ entityStatusLabel(metric.status) }}</dd></div><div><dt>总览</dt><dd>{{ metric.visibility?.dashboardEnabled ? '显示' : '不显示' }}</dd></div></dl><div v-if="props.canWrite" class="metric-actions"><button v-if="metric.isSystem || props.canFormulaWrite" type="button" class="secondary-button" @click="editMetric(metric)">{{ metric.isSystem ? '显示设置' : '编辑' }}</button><button v-if="!metric.isSystem && metric.status === 'ACTIVE' && props.canFormulaWrite" type="button" class="danger-button" @click="archive(metric)">归档</button></div></div>
      </article>
      <p v-if="!metrics.length" class="dashboard-state">当前没有指标。</p>
    </div>

    <AppDialog :open="showEditor" title-id="metric-editor-title" initial-focus="#metric-name" :busy="saving || validating || previewing" @close="closeEditor">
      <div class="metric-editor-modal">
        <div class="section-heading"><div><h3 id="metric-editor-title">{{ form.isSystem ? '系统指标显示设置' : (editorMetric ? '编辑自定义指标' : '新建自定义指标') }}</h3><p>{{ form.isSystem ? '系统指标的计算规则不可修改。' : '公式只接受结构化节点，不执行自由文本或代码。' }}</p></div><button type="button" class="secondary-button" :disabled="saving || validating || previewing" @click="closeEditor">关闭</button></div>
        <template v-if="!form.isSystem"><label>名称<input id="metric-name" v-model="form.name" maxlength="80"></label>
        <label>说明<textarea v-model="form.description" maxlength="400"></textarea></label>
        <div class="form-grid"><label>显示格式<select v-model="form.displayFormat"><option value="CURRENCY">金额</option><option value="PERCENT">百分比</option><option value="NUMBER">数字</option><option value="INTEGER">整数</option></select></label><label>精度<input v-model.number="form.precision" type="number" min="0" max="8"></label></div></template>
        <div class="visibility-controls"><label><input v-model="form.visibility.hidden" type="checkbox"> 隐藏数值</label><label><input v-model="form.visibility.dashboardEnabled" type="checkbox"> 在财务总览显示</label></div>
        <div v-if="!form.isSystem" class="formula-builder" aria-label="中文公式构建器">
          <h4>公式节点</h4>
          <p class="formula-hints">使用中文控件组合完整公式；保存的是结构化 AST，不执行浏览器端计算。</p>
          <p class="formula-hints">中文函数提示：安全除法（分母为零时返回不可计算），以及加、减、乘、除、最小值、最大值、平均值、取负、绝对值、四舍五入、限制范围。</p>
          <FormulaNodeEditor v-model:node="form.formulaRoot" :metrics="activeMetricOptions" :categories="categories" :accounts="accounts" :candidate-metric-id="form.id" :field-errors="formulaFieldErrors" />
          <div class="row-actions formula-actions"><button type="button" class="secondary-button" :disabled="!props.canValidate || validating || previewing" @click="validateDraft">{{ validating ? '校验中…' : '校验公式' }}</button><button type="button" class="secondary-button" :disabled="!props.canValidate || validating || previewing" @click="previewDraft">{{ previewing ? '预览中…' : '预览结果' }}</button><span v-if="previewValue" class="formula-preview">预览：{{ previewValue.value ?? '暂无数据' }} · {{ metricDataStatusLabel(previewValue.dataStatus) }}</span></div>
        </div>
        <FormulaVersionHistory v-if="!form.isSystem && editorMetric" :versions="history" :loading="historyLoading" :error="historyError" :has-more="historyHasMore" @load-more="loadMoreHistory" />
        <p v-if="message" class="field-error" :class="{ 'formula-success': message.includes('通过') || message.includes('预览') }" role="status">{{ message }}</p>
        <div class="row-actions"><button type="button" class="retry-button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存指标' }}</button><button type="button" class="secondary-button" :disabled="saving" @click="closeEditor">取消</button></div>
      </div>
    </AppDialog>
    <AppDialog :open="Boolean(archiveTarget)" title-id="metric-archive-title" :busy="saving" :close-on-backdrop="false" @close="cancelArchive">
      <div class="metric-editor-modal confirm-modal">
        <div class="section-heading"><div><h3 id="metric-archive-title">确认归档指标</h3><p>归档后指标不会出现在默认列表和财务总览中，历史版本仍会保留。</p></div><button type="button" class="secondary-button" :disabled="saving" @click="cancelArchive">关闭</button></div>
        <p v-if="archiveTarget">确定归档“{{ archiveTarget.name }}”吗？</p>
        <div class="row-actions"><button type="button" class="danger-button" :disabled="saving" @click="confirmArchive">{{ saving ? '归档中…' : '确认归档' }}</button><button type="button" class="secondary-button" :disabled="saving" @click="cancelArchive">取消</button></div>
      </div>
    </AppDialog>
  </section>
</template>
