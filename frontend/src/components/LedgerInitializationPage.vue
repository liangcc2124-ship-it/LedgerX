<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ApiClientError, createRequestId, getLedgerInitialization, initializeLedger } from '../apiClient.js';

const emit = defineEmits(['initialization-complete']);
const summary = ref(null);
const loading = ref(true);
const submitting = ref(false);
const loadError = ref('');
const submitError = ref('');
const fieldErrors = ref({});
const pendingRequest = ref(null);
const form = reactive({ ledgerStartOn: '', defaultAccountOpeningOn: '', openingBalance: '0.00', accountName: '' });

const isReview = computed(() => summary.value?.setupState === 'REVIEW_REQUIRED');
const conflicts = computed(() => summary.value?.accountsNeedingOpeningDateReview || []);

function atLeastStart(date) {
  const start = form.ledgerStartOn;
  return date && start && date < start ? start : date;
}

watch(() => form.ledgerStartOn, (value) => {
  if (!isReview.value) form.defaultAccountOpeningOn = value;
  else form.defaultAccountOpeningOn = atLeastStart(form.defaultAccountOpeningOn);
  for (const account of conflicts.value) {
    accountDates[account.accountId] = atLeastStart(accountDates[account.accountId]);
  }
});

const accountDates = reactive({});

async function loadSummary() {
  loading.value = true;
  loadError.value = '';
  try {
    const response = await getLedgerInitialization();
    summary.value = response.payload.data;
    const defaultAccount = response.payload.data.defaultAccount;
    const suggested = response.payload.data.suggestedLedgerStartOn || response.payload.data.today;
    form.ledgerStartOn = suggested;
    form.defaultAccountOpeningOn = isReview.value
      ? atLeastStart(defaultAccount.openingOn)
      : suggested;
    form.accountName = defaultAccount.name;
    form.openingBalance = defaultAccount.openingBalance;
    for (const account of conflicts.value) {
      accountDates[account.accountId] = atLeastStart(account.openingOn);
    }
  } catch (error) {
    loadError.value = error?.message || '无法读取账本初始化资料，请重试。';
  } finally {
    loading.value = false;
  }
}

function buildPayload() {
  const accountOpeningDates = conflicts.value
    .slice().sort((left, right) => left.accountId.localeCompare(right.accountId))
    .map((account) => ({ accountId: account.accountId, openingOn: accountDates[account.accountId] }));
  return {
    ledgerStartOn: form.ledgerStartOn,
    defaultAccountOpeningOn: isReview.value ? form.defaultAccountOpeningOn : form.ledgerStartOn,
    openingBalance: form.openingBalance,
    accountName: form.accountName,
    confirmExistingData: isReview.value,
    accountOpeningDates,
  };
}

async function submit() {
  submitError.value = '';
  fieldErrors.value = {};
  const body = buildPayload();
  const fingerprint = JSON.stringify(body);
  if (!pendingRequest.value || pendingRequest.value.fingerprint !== fingerprint) {
    pendingRequest.value = { fingerprint, key: createRequestId() };
  }
  submitting.value = true;
  try {
    const response = await initializeLedger(body, { idempotencyKey: pendingRequest.value.key });
    pendingRequest.value = null;
    emit('initialization-complete', response.payload);
  } catch (error) {
    if (error instanceof ApiClientError) {
      fieldErrors.value = error.fieldErrors || {};
      submitError.value = error.code === 'ALREADY_INITIALIZED'
        ? '账本已经完成初始化，正在刷新状态。'
        : error.message || '初始化未完成，请检查输入后重试。';
      if (error.code === 'ALREADY_INITIALIZED') emit('initialization-complete', null);
    } else {
      submitError.value = error?.message || '初始化未完成，请重试。';
    }
  } finally {
    submitting.value = false;
  }
}

function fieldError(field) { return fieldErrors.value[field] || ''; }

onMounted(loadSummary);
</script>

<template>
  <section class="ledger-setup-page" aria-labelledby="ledger-setup-title">
    <header class="ledger-setup-header">
      <span class="brand-mark" aria-hidden="true">L</span>
      <div>
        <p class="eyebrow">LedgerX · 本地账本</p>
        <h1 id="ledger-setup-title">{{ isReview ? '确认账本起始信息' : '开始使用 LedgerX' }}</h1>
        <p class="ledger-setup-lede">
          {{ isReview
            ? '我们保留了历史记录。请核对账本起始日与账户开户日；这里只调整你明确确认的账户资料，不会改写历史记录。'
            : '设定从哪一天开始记录，以及这一天账户里已有的余额。保存后，之后的记录会按这些基础信息计算。' }}
        </p>
      </div>
    </header>

    <p v-if="loading" class="ledger-setup-message" role="status">正在读取账本资料…</p>
    <section v-else-if="loadError" class="ledger-setup-panel" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="retry-button" @click="loadSummary">重新读取</button>
    </section>
    <form v-else class="ledger-setup-panel" @submit.prevent="submit">
      <div v-if="isReview" class="ledger-setup-review-note">
        <strong>这是已有账本</strong>
        <p>默认账户原开户日为 {{ summary.defaultAccount.openingOn }}，原期初余额为 {{ summary.defaultAccount.openingBalance }}。请确认下方资料。历史数据会继续保留。</p>
      </div>

      <label class="ledger-setup-field">
        <span>账本起始日</span>
        <input v-model="form.ledgerStartOn" type="date" required :max="summary.today" aria-describedby="ledger-start-hint ledger-start-error">
        <small id="ledger-start-hint">{{ summary.suggestedLedgerStartOn ? `建议从已有资料中最早的日期 ${summary.suggestedLedgerStartOn} 开始。` : `不能晚于今天（${summary.today}）。` }}</small>
        <small v-if="fieldError('ledgerStartOn')" id="ledger-start-error" class="ledger-setup-error">{{ fieldError('ledgerStartOn') }}</small>
      </label>

      <div class="ledger-setup-section">
        <h2>默认账户</h2>
        <label class="ledger-setup-field">
          <span>账户名称</span>
          <input v-model="form.accountName" type="text" maxlength="100" required autocomplete="off">
          <small v-if="fieldError('accountName')" class="ledger-setup-error">{{ fieldError('accountName') }}</small>
        </label>
        <label v-if="isReview" class="ledger-setup-field">
          <span>默认账户开户日</span>
          <input v-model="form.defaultAccountOpeningOn" type="date" required :min="form.ledgerStartOn" :max="summary.defaultAccount.earliestSettlementOn || undefined">
          <small v-if="summary.defaultAccount.earliestSettlementOn">已有结算记录最早为 {{ summary.defaultAccount.earliestSettlementOn }}。</small>
          <small v-if="summary.defaultAccount.earliestSettlementOn && summary.defaultAccount.openingOn > summary.defaultAccount.earliestSettlementOn">原开户日晚于已有结算日，请明确调整到最早结算日或更早。</small>
          <small v-if="fieldError('defaultAccountOpeningOn')" class="ledger-setup-error">{{ fieldError('defaultAccountOpeningOn') }}</small>
        </label>
        <p v-else class="ledger-setup-hint">开户日与账本起始日一致：{{ form.ledgerStartOn || '请选择起始日' }}</p>
        <label class="ledger-setup-field">
          <span>该账户在开户日的期初余额（元）</span>
          <input v-model="form.openingBalance" type="text" inputmode="decimal" required autocomplete="off" pattern="^-?\d+(\.\d{1,2})?$">
          <small>填写开户日已有的余额；不要把之后的收支再计入期初余额。</small>
          <small v-if="fieldError('openingBalance')" class="ledger-setup-error">{{ fieldError('openingBalance') }}</small>
        </label>
      </div>

      <section v-if="isReview && conflicts.length" class="ledger-setup-section" aria-labelledby="review-accounts-title">
        <h2 id="review-accounts-title">需要确认开户日的账户</h2>
        <p>这些账户的历史结算日期早于当前开户日。将开户日调整到最早结算日或更早，才能确保回收站记录仍可恢复。</p>
        <div v-for="account in conflicts" :key="account.accountId" class="ledger-setup-account-row">
          <div><strong>{{ account.name }}</strong><small>原开户日 {{ account.openingOn }} · 最早结算 {{ account.earliestSettlementOn }}</small></div>
          <label>
            <span class="visually-hidden">{{ account.name }}的新开户日</span>
            <input v-model="accountDates[account.accountId]" type="date" required :min="form.ledgerStartOn" :max="account.earliestSettlementOn">
            <small v-if="fieldError(`accountOpeningDates.${account.accountId}`)" class="ledger-setup-error">{{ fieldError(`accountOpeningDates.${account.accountId}`) }}</small>
          </label>
        </div>
      </section>

      <p v-if="submitError" class="ledger-setup-error" role="alert">{{ submitError }}</p>
      <div class="ledger-setup-actions">
        <button class="retry-button" type="submit" :disabled="submitting || !form.ledgerStartOn">
          {{ submitting ? '正在保存…' : isReview ? '确认并启用账本' : '保存并开始记账' }}
        </button>
      </div>
    </form>
    <p class="ledger-setup-footer">数据只保存在这台电脑上。你可以关闭浏览器，之后再通过本机 LedgerX 服务继续使用。</p>
  </section>
</template>
