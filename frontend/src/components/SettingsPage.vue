<script setup>
import { onMounted, ref } from 'vue';

const canOpenDataFolder = ref(false);
const feedback = ref('');

function bridge() {
  return typeof window !== 'undefined' && window.desktop ? window.desktop : null;
}

async function openDataFolder() {
  feedback.value = '';
  const desktop = bridge();
  if (!desktop || typeof desktop.openDataFolder !== 'function') return;
  try {
    const result = await desktop.openDataFolder();
    if (!result || result.ok !== true) feedback.value = '无法打开数据目录，请检查权限后重试。';
  } catch (error) {
    feedback.value = '无法打开数据目录，请检查权限后重试。';
  }
}

onMounted(() => { canOpenDataFolder.value = Boolean(bridge() && typeof bridge().openDataFolder === 'function'); });
</script>

<template>
  <section class="settings-page" aria-labelledby="settings-page-title">
    <h2 id="settings-page-title" tabindex="-1">设置与备份说明</h2>
    <p>LedgerX 当前是本地单用户基础记账版，提供收支记录、分类和账户管理。</p>
    <section class="info-card" aria-labelledby="backup-title">
      <h3 id="backup-title">手工备份</h3>
      <ol>
        <li>完全退出应用，并确认 Java 本地服务进程已经结束。</li>
        <li>复制整个 LedgerX 数据目录；默认位置是 <code>%LocalAppData%\LedgerX</code>。</li>
        <li>恢复前先保留当前目录，再整体替换为备份副本。</li>
      </ol>
      <p class="warning">不要在应用运行中只复制 ledger.db；这不是可靠的备份方式。</p>
      <button v-if="canOpenDataFolder" type="button" @click="openDataFolder">查看数据目录</button>
      <p class="hint">按钮仅用于定位目录，请退出应用后再复制。</p>
    </section>
    <p v-if="feedback" class="error" role="status" aria-live="polite">{{ feedback }}</p>
  </section>
</template>

<style scoped>
.settings-page { display: grid; gap: 1rem; max-width: 52rem; }
.settings-page > p { color: #5d6675; }
.info-card { border: 1px solid #d7dce5; border-radius: .75rem; padding: 1rem; background: white; }
.info-card h3 { margin-top: 0; } li { margin: .6rem 0; } code { overflow-wrap: anywhere; }
.warning { color: #8a1c13; } .hint { color: #5d6675; font-size: .92rem; }
button { min-height: 2.4rem; padding: .45rem .85rem; border: 1px solid #295ecb; border-radius: .45rem; background: #295ecb; color: white; cursor: pointer; }
.error { padding: .65rem .8rem; border-radius: .45rem; background: #fff1f0; color: #8a1c13; }

.settings-page { width: 100%; max-width: 48rem; gap: 1.75rem; }
.settings-page > h2 { margin: 0; color: var(--text); font-size: clamp(1.9rem, 4vw, 3rem); font-weight: 650; letter-spacing: -.04em; line-height: 1.1; }
.settings-page > p { margin: -.85rem 0 .65rem; color: var(--muted-text); line-height: 1.7; }
.info-card { padding: 1.5rem; border-color: var(--border-color); border-radius: .55rem; background: var(--surface); }
.info-card h3 { margin-bottom: 1rem; color: var(--text); font-size: 1.12rem; }
.info-card ol { margin: 0; padding-left: 1.3rem; color: var(--muted-text); line-height: 1.7; }
.info-card li { margin: .85rem 0; }
.info-card code { color: var(--accent-hover); }
.info-card .warning { margin-top: 1.3rem; color: var(--danger); line-height: 1.6; }
.info-card button { min-height: 2.65rem; border-color: var(--accent); background: var(--accent); }
.info-card button:hover:not(:disabled) { background: var(--accent-hover); }
.info-card .hint { color: var(--muted-text); line-height: 1.5; }
.settings-page .error { border: 1px solid #e4c5c0; background: #fbefec; color: var(--danger); }
</style>
