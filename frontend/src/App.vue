<script setup>
import { nextTick, onMounted, onUnmounted, ref } from 'vue';
import { ApiClientError, getSystemStatus } from './apiClient.js';
import ProfilesPage from './components/ProfilesPage.vue';
import SettingsPage from './components/SettingsPage.vue';
import RecordsPage from './components/RecordsPage.vue';
import CatalogPage from './components/CatalogPage.vue';

const POLL_DELAY_MS = 500;
const STARTUP_DEADLINE_MS = 15_000;

const view = ref({ kind: 'loading', data: null, error: null });
const folderFeedback = ref('');
const activePage = ref('home');
const pageTitles = {
  home: '财务总览',
  profiles: '用户空间',
  settings: '设置与备份说明',
  catalog: '分类与账户',
  records: '收支记录',
};

let generation = 0;
let pollTimer = null;
let requestController = null;
let requestPromise = null;

function clearPollTimer() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

function cancelPendingRequest() {
  if (requestController) {
    requestController.abort();
    requestController = null;
  }
}

function isCurrent(round) {
  return round === generation;
}

function setRetryableError(error) {
  if (error instanceof ApiClientError && error.status === 401) {
    view.value = { kind: 'auth-error', data: null, error };
    return;
  }
  view.value = { kind: 'error', data: null, error };
}

function setStartupTimeout() {
  view.value = {
    kind: 'timeout',
    data: null,
    error: new ApiClientError('初始化本地数据超时，请重试。', {
      code: 'STARTUP_TIMEOUT',
      retryable: true,
    }),
  };
}

function selectPage(page) {
  if (!['home', 'profiles', 'settings', 'catalog', 'records'].includes(page) || view.value.kind !== 'ready') {
    return;
  }
  activePage.value = page;
  void nextTick(() => {
    const headingId = page === 'profiles' ? 'profiles-page-title'
      : page === 'settings' ? 'settings-page-title' : page === 'catalog' ? 'catalog-page-title' : page === 'records' ? 'records-page-title' : 'home-page-title';
    document.getElementById(headingId)?.focus();
  });
}

function refreshAfterProfileActivation() {
  // A profile mutation already completed. Refresh the status in the background
  // so the page that initiated the mutation remains mounted and can show the
  // result; startup/retry flows still use the loading view below.
  void loadStatus({ preservePage: true });
}

function schedulePoll(round, deadline) {
  clearPollTimer();
  const remaining = deadline - Date.now();
  if (remaining <= 0) {
    if (isCurrent(round)) {
      setStartupTimeout();
    }
    return;
  }
  pollTimer = setTimeout(() => {
    pollTimer = null;
    void requestStatus(round, deadline);
  }, Math.min(POLL_DELAY_MS, remaining));
}

async function requestStatus(round, deadline) {
  if (!isCurrent(round)) {
    return;
  }
  if (Date.now() >= deadline) {
    setStartupTimeout();
    return;
  }

  const controller = new AbortController();
  requestController = controller;
  const currentRequest = getSystemStatus({ signal: controller.signal });
  requestPromise = currentRequest;
  try {
    const response = await currentRequest;
    if (!isCurrent(round)) {
      return;
    }
    if (response.data.state === 'READY') {
      clearPollTimer();
      view.value = { kind: 'ready', data: response.data, error: null };
      return;
    }
    if (response.data.state === 'RECOVERY_REQUIRED') {
      clearPollTimer();
      folderFeedback.value = '';
      view.value = { kind: 'recovery', data: response.data, error: null };
      return;
    }

    view.value = { kind: 'bootstrapping', data: response.data, error: null };
    schedulePoll(round, deadline);
  } catch (error) {
    if (!isCurrent(round) || (error && error.name === 'AbortError')) {
      return;
    }
    setRetryableError(error instanceof ApiClientError
      ? error
      : new ApiClientError('本地服务暂时不可用，请重试。'));
  } finally {
    if (requestController === controller) {
      requestController = null;
    }
    if (requestPromise === currentRequest) {
      requestPromise = null;
    }
  }
}

async function loadStatus({ preservePage = false } = {}) {
  const previousRequest = requestPromise;
  generation += 1;
  const round = generation;
  const deadline = Date.now() + STARTUP_DEADLINE_MS;
  clearPollTimer();
  cancelPendingRequest();
  folderFeedback.value = '';
  if (!preservePage) {
    view.value = { kind: 'loading', data: null, error: null };
  }

  if (previousRequest) {
    try {
      await previousRequest;
    } catch (error) {
      // The previous request is intentionally cancelled when a new round starts.
    }
  }
  if (!isCurrent(round)) {
    return;
  }
  void requestStatus(round, deadline);
}

function desktopBridge() {
  if (typeof window === 'undefined' || !window.desktop) {
    return null;
  }
  return window.desktop;
}

function canOpenFolder(method) {
  const bridge = desktopBridge();
  return Boolean(bridge && typeof bridge[method] === 'function');
}

async function openFolder(method) {
  const bridge = desktopBridge();
  if (!bridge || typeof bridge[method] !== 'function') {
    return;
  }
  try {
    const result = await bridge[method]();
    if (!result || result.ok !== true) {
      folderFeedback.value = '无法打开目录，请检查权限后重试。';
    }
  } catch (error) {
    folderFeedback.value = '无法打开目录，请检查权限后重试。';
  }
}

onMounted(() => {
  void loadStatus();
});

onUnmounted(() => {
  generation += 1;
  clearPollTimer();
  cancelPendingRequest();
});
</script>

<template>
  <main class="page-shell">
    <template v-if="view.kind === 'ready'">
      <div class="app-shell">
        <aside class="app-sidebar">
          <div class="brand-lockup" aria-label="LedgerX">
            <span class="brand-mark" aria-hidden="true">L</span>
            <span class="brand-name">LedgerX</span>
          </div>
          <p class="sidebar-caption">本地财务工作台</p>

          <nav class="main-navigation" aria-label="主导航">
            <button type="button" :class="['nav-button', { 'is-selected': activePage === 'home' }]" :aria-current="activePage === 'home' ? 'page' : undefined" @click="selectPage('home')">
              <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="m4 10 8-6 8 6v9a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z" /></svg></span><span>首页</span>
            </button>
            <button type="button" :class="['nav-button', { 'is-selected': activePage === 'profiles' }]" :aria-current="activePage === 'profiles' ? 'page' : undefined" @click="selectPage('profiles')">
              <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3" /><path d="M5 20a7 7 0 0 1 14 0M18 5.5a3 3 0 0 1 0 5.2" /></svg></span><span>用户空间</span>
            </button>
            <button type="button" :class="['nav-button', { 'is-selected': activePage === 'catalog' }]" :aria-current="activePage === 'catalog' ? 'page' : undefined" @click="selectPage('catalog')">
              <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 7h16M4 12h16M4 17h10" /></svg></span><span>分类与账户</span>
            </button>
            <button type="button" :class="['nav-button', { 'is-selected': activePage === 'records' }]" :aria-current="activePage === 'records' ? 'page' : undefined" @click="selectPage('records')">
              <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M6 3h9l3 3v15H6zM14 3v4h4M9 12h6M9 16h6" /></svg></span><span>收支记录</span>
            </button>
            <button type="button" :class="['nav-button', { 'is-selected': activePage === 'settings' }]" :aria-current="activePage === 'settings' ? 'page' : undefined" @click="selectPage('settings')">
              <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Z" /><path d="m19 13 2-1-2-1-.4-1.5 1-2-2-2-2 1L14 7l-1-2h-2L10 7l-1.6.5-2-1-2 2 1 2L5 11l-2 1 2 1 .4 1.5-1 2 2 2 2-1L10 17l1 2h2l1-2 1.6-.5 2 1 2-2-1-2z" /></svg></span><span>设置</span>
            </button>
          </nav>

          <div class="sidebar-footer">
            <span class="connection-state"><span class="status-dot is-ready" aria-hidden="true"></span>本地服务已连接</span>
            <span class="sidebar-version">版本 {{ view.data.applicationVersion }}</span>
          </div>
        </aside>

        <section class="workspace" aria-label="LedgerX 工作区">
          <header class="workspace-header">
            <div>
              <p class="workspace-breadcrumb">LedgerX <span aria-hidden="true">/</span> {{ pageTitles[activePage] }}</p>
              <p class="workspace-context">版本 {{ view.data.applicationVersion }} · 状态 {{ view.data.state }}</p>
            </div>
            <span class="workspace-status"><span class="status-dot is-ready" aria-hidden="true"></span>已连接</span>
          </header>

          <div class="workspace-content">
            <section v-if="activePage === 'home'" class="home-panel" aria-labelledby="home-page-title">
              <div class="home-intro">
                <h2 id="home-page-title" aria-label="首页" tabindex="-1">财务总览</h2>
                <p class="home-lede">掌握你的财务现状，专注当下，规划未来。</p>
                <p class="home-description">本地账本已经准备好。你可以从收支记录开始，逐步完善分类、账户和用户空间。</p>
                <button type="button" class="retry-button" @click="selectPage('records')">添加第一笔记录</button>
              </div>
              <div class="home-divider" aria-hidden="true"></div>
              <dl class="home-summary" aria-label="本地账本状态">
                <div><dt>服务状态</dt><dd>已连接</dd></div>
                <div><dt>数据位置</dt><dd>本地保存</dd></div>
                <div><dt>当前版本</dt><dd>{{ view.data.applicationVersion }}</dd></div>
              </dl>
            </section>
            <ProfilesPage v-else-if="activePage === 'profiles'" @profile-activation-complete="refreshAfterProfileActivation" />
            <SettingsPage v-else-if="activePage === 'settings'" @profile-activation-complete="refreshAfterProfileActivation" />
            <CatalogPage v-else-if="activePage === 'catalog'" />
            <RecordsPage v-else />
          </div>
        </section>
      </div>
    </template>

    <section v-else class="status-card" aria-labelledby="page-title">
      <p class="eyebrow">LedgerX</p>
      <h1 id="page-title">本地财务工作台</h1>

      <div class="status-panel" role="status" aria-live="polite" aria-atomic="true">
        <template v-if="view.kind === 'loading'">
          <span class="status-dot is-loading" aria-hidden="true"></span>
          <p>正在连接本地服务…</p>
        </template>

        <template v-else-if="view.kind === 'bootstrapping'">
          <span class="status-dot is-loading" aria-hidden="true"></span>
          <div class="status-copy">
            <p class="status-title">正在初始化本地数据…</p>
            <p class="status-detail">状态 {{ view.data.state }} · 版本 {{ view.data.applicationVersion }}</p>
          </div>
        </template>

        <template v-else-if="view.kind === 'ready'">
          <span class="status-dot is-ready" aria-hidden="true"></span>
          <div class="status-copy">
            <p class="status-title">本地服务已连接</p>
            <p class="status-detail">版本 {{ view.data.applicationVersion }} · 状态 {{ view.data.state }}<span v-if="view.data.activeProfileId"> · 当前空间 {{ view.data.activeProfileId }}</span></p>
          </div>
        </template>

        <template v-else-if="view.kind === 'recovery'">
          <span class="status-dot is-error" aria-hidden="true"></span>
          <div class="status-copy">
            <p class="status-title">本地数据需要恢复</p>
            <p class="status-detail">请检查日志并完成恢复后，再刷新状态。</p>
          </div>
        </template>

        <template v-else-if="view.kind === 'auth-error'">
          <span class="status-dot is-error" aria-hidden="true"></span>
          <div class="status-copy">
            <p class="status-title">桌面会话无效</p>
            <p class="status-detail">请重新启动 LedgerX。</p>
          </div>
        </template>

        <template v-else>
          <span class="status-dot is-error" aria-hidden="true"></span>
          <div class="status-copy">
            <p class="status-title">{{ view.kind === 'timeout' ? '初始化本地数据超时' : '本地服务暂时不可用' }}</p>
            <p class="status-detail">{{ view.error?.message || '请检查服务后重试。' }}</p>
          </div>
        </template>
      </div>

      <div v-if="view.kind === 'recovery'" class="recovery-actions">
        <button class="retry-button" type="button" @click="loadStatus">刷新状态</button>
        <button v-if="canOpenFolder('openLogsFolder')" class="secondary-button" type="button" @click="openFolder('openLogsFolder')">
          打开日志目录
        </button>
        <button v-if="canOpenFolder('openDataFolder')" class="secondary-button" type="button" @click="openFolder('openDataFolder')">
          打开数据目录
        </button>
      </div>

      <p v-if="folderFeedback" class="action-feedback" role="status" aria-live="polite">{{ folderFeedback }}</p>

      <button
        v-if="view.kind === 'bootstrapping' || view.kind === 'error' || view.kind === 'timeout'"
        class="retry-button"
        type="button"
        @click="loadStatus"
      >
        重试连接
      </button>

      <template v-if="view.kind === 'ready'">
        <nav class="main-navigation" aria-label="主导航">
          <button
            type="button"
            :class="['nav-button', { 'is-selected': activePage === 'home' }]"
            :aria-current="activePage === 'home' ? 'page' : undefined"
            @click="selectPage('home')"
          >首页</button>
          <button
            type="button"
            :class="['nav-button', { 'is-selected': activePage === 'profiles' }]"
            :aria-current="activePage === 'profiles' ? 'page' : undefined"
            @click="selectPage('profiles')"
          >用户空间</button>
          <button
            type="button"
            :class="['nav-button', { 'is-selected': activePage === 'settings' }]"
            :aria-current="activePage === 'settings' ? 'page' : undefined"
            @click="selectPage('settings')"
          >设置</button>
          <button
            type="button"
            :class="['nav-button', { 'is-selected': activePage === 'catalog' }]"
            :aria-current="activePage === 'catalog' ? 'page' : undefined"
            @click="selectPage('catalog')"
          >分类与账户</button>
          <button
            type="button"
            :class="['nav-button', { 'is-selected': activePage === 'records' }]"
            :aria-current="activePage === 'records' ? 'page' : undefined"
            @click="selectPage('records')"
          >收支记录</button>
        </nav>

        <section v-if="activePage === 'home'" class="home-panel" aria-labelledby="home-page-title">
          <h2 id="home-page-title" tabindex="-1">首页</h2>
          <p>本地服务已准备好，可以从导航进入收支记录、分类与账户、用户空间或设置。</p>
          <button type="button" class="retry-button" @click="selectPage('records')">添加第一笔记录</button>
        </section>
        <ProfilesPage v-else-if="activePage === 'profiles'" @profile-activation-complete="refreshAfterProfileActivation" />
        <SettingsPage v-else-if="activePage === 'settings'" @profile-activation-complete="refreshAfterProfileActivation" />
        <CatalogPage v-else-if="activePage === 'catalog'" />
        <RecordsPage v-else @profile-activation-complete="refreshAfterProfileActivation" />
      </template>
    </section>
  </main>
</template>
