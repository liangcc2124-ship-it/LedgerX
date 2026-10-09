import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const projectRoot = path.resolve(frontendRoot, '..');
const dataRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'ledgerx-browser-p7-'));
const targetRoot = path.join(projectRoot, 'target', 'p7-003');
let buildManifest;
try {
  buildManifest = JSON.parse(await fs.readFile(
    path.join(targetRoot, 'release-evidence', 'build-manifest.json'), 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
  const pom = await fs.readFile(path.join(projectRoot, 'pom.xml'), 'utf8');
  const projectHeader = pom.slice(0, pom.indexOf('<dependencies>'));
  const projectIdentity = projectHeader.match(/<artifactId>([^<]+)<\/artifactId>\s*<version>([^<]+)<\/version>/);
  assert.ok(projectIdentity, 'Maven project identity is available');
  buildManifest = { javaArtifact: `${projectIdentity[1]}-${projectIdentity[2]}.jar` };
}
const javaArtifact = path.join(targetRoot, buildManifest.javaArtifact);
const javaExecutable = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : (process.platform === 'win32' ? 'java.exe' : 'java');
const evidenceRoot = process.env.LEDGERX_BROWSER_EVIDENCE_DIR
  ? path.resolve(process.env.LEDGERX_BROWSER_EVIDENCE_DIR)
  : null;
let browser;
let javaProcess;
let server;
let checkingExpectedSetupGate = false;
let expectedSetupGateConsoleErrors = 0;

function applicationEnvironment() {
  const environment = { ...process.env };
  delete environment.LEDGERX_SESSION_TOKEN;
  delete environment.LEDGERX_DATA_DIR;
  delete environment.LEDGERX_WEB_ROOT;
  delete environment.LEDGERX_DEV_API_TOKEN;
  environment.LEDGERX_TEST_DATA_DIR = dataRoot;
  if (process.env.JAVA_HOME) {
    const javaBin = path.join(process.env.JAVA_HOME, 'bin');
    const pathKey = Object.keys(environment).find((key) => key.toLowerCase() === 'path') || 'Path';
    environment[pathKey] = process.platform === 'win32' && process.env.SystemRoot
      ? `${javaBin}${path.delimiter}${path.join(process.env.SystemRoot, 'System32')}`
      : javaBin;
  }
  return environment;
}

async function startServer() {
  const environment = applicationEnvironment();
  return new Promise((resolve, reject) => {
    const child = spawn(javaExecutable, ['-Djava.awt.headless=true', '-jar', javaArtifact], {
      cwd: projectRoot,
      env: environment,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });
    javaProcess = child;
    let output = '';
    let settled = false;
    const timeout = setTimeout(() => finish(new Error(`Java server did not become ready. ${output.slice(-1000)}`)), 15000);
    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (error) reject(error);
      else resolve(value);
    };
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      output += chunk;
      const match = output.match(/LEDGERX_READY \{"port":(\d+),"protocol":"1"\}/);
      if (match) finish(null, { child, origin: `http://127.0.0.1:${match[1]}` });
    });
    child.stderr.on('data', (chunk) => { output += chunk; });
    child.once('error', (error) => finish(error));
    child.once('exit', (code) => finish(new Error(`Java server exited before ready (${code}). ${output.slice(-1000)}`)));
  });
}

async function stopServer(child) {
  if (!child || child.exitCode !== null) return true;
  const closed = new Promise((resolve) => child.once('exit', resolve));
  try { child.kill('SIGINT'); } catch { child.kill(); }
  const stopped = await Promise.race([closed.then(() => true), new Promise((resolve) => setTimeout(() => resolve(false), 8000))]);
  if (!stopped && child.exitCode === null) child.kill();
  return stopped;
}

async function assertPortReleased(origin) {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    try {
      await fetch(`${origin}/health/live`, { signal: AbortSignal.timeout(500) });
    } catch {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`The Java service port remained open after shutdown: ${origin}`);
}

async function openRecords(page, origin, verifyResponsiveLayout = false) {
  const response = await page.goto(`${origin}/`);
  assert.equal(response.status(), 200, 'Java serves the Vue application shell');
  assert.equal(await page.title(), 'LedgerX');
  assert.ok(page.url().startsWith(origin), 'browser remains on the Java same-origin address');
  await page.waitForFunction(() => Boolean(document.getElementById('ledger-setup-title')
    || document.querySelector('.connection-state')), { timeout: 10000 });
  if (await page.locator('#ledger-setup-title').count()) {
    await page.getByRole('heading', { name: '开始使用 LedgerX' }).waitFor();
    assert.equal(await page.getByRole('navigation', { name: '主导航' }).count(), 0,
      'business navigation is absent before setup');
    checkingExpectedSetupGate = true;
    const blocked = await page.evaluate(async () => {
      const response = await fetch('/api/v1/records?status=ACTIVE&limit=50', {
        credentials: 'same-origin',
        headers: { 'X-Request-Id': crypto.randomUUID() },
      });
      return { status: response.status, body: await response.json() };
    });
    checkingExpectedSetupGate = false;
    assert.equal(blocked.status, 409, 'Java blocks direct business API reads before setup');
    assert.equal(blocked.body.error.code, 'LEDGER_SETUP_REQUIRED');
    assert.equal(expectedSetupGateConsoleErrors, 1,
      'the deliberate setup gate is the only expected HTTP console error');
    const startOn = await page.getByLabel('账本起始日').inputValue();
    await page.getByRole('button', { name: '保存并开始记账' }).click();
    await page.getByRole('navigation', { name: '主导航' }).waitFor({ timeout: 10000 });
    const initialized = await page.evaluate(async () => {
      const response = await fetch('/api/v1/system/status', {
        credentials: 'same-origin',
        headers: { 'X-Request-Id': crypto.randomUUID() },
      });
      return (await response.json()).data;
    });
    assert.equal(initialized.setupState, 'COMPLETED');
    assert.equal(initialized.ledgerStartOn, startOn);
  }
  await page.getByText('本地服务已连接').waitFor({ timeout: 10000 });
  assert.equal(await page.locator('vite-error-overlay').count(), 0, 'no Vite error overlay is rendered');
  await page.getByRole('button', { name: '收支记录' }).click();
  await page.getByRole('heading', { name: '收支记录' }).waitFor();
  await page.getByRole('button', { name: '新增记录' }).waitFor({ state: 'visible' });
  if (verifyResponsiveLayout) {
    const originalViewport = { width: 1440, height: 900 };
    for (const viewport of [originalViewport, { width: 1024, height: 768 }, { width: 320, height: 480 }]) {
      await page.setViewportSize(viewport);
      await page.getByRole('button', { name: '新增记录' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });
      const bounds = await dialog.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        return { left: rect.left, right: rect.right, scrollHeight: element.scrollHeight,
          clientHeight: element.clientHeight };
      });
      assert.ok(bounds.left >= 0 && bounds.right <= viewport.width + 1,
        `record dialog fits the ${viewport.width}px viewport`);
      await dialog.getByRole('button', { name: '保存' }).scrollIntoViewIfNeeded();
      assert.equal(await dialog.getByRole('button', { name: '保存' }).isVisible(), true,
        `save action is reachable at ${viewport.width}x${viewport.height}`);
      if (viewport.width === 320) {
        assert.ok(bounds.scrollHeight > bounds.clientHeight,
          'low-height narrow view provides internal dialog scrolling');
      }
      await dialog.getByRole('button', { name: '取消' }).click();
    }
    await page.setViewportSize(originalViewport);
  }
}

async function assertSecondWriterRejected() {
  const environment = applicationEnvironment();
  const duplicate = spawn(javaExecutable, ['-Djava.awt.headless=true', '-jar', javaArtifact], {
    cwd: projectRoot,
    env: environment,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  let output = '';
  duplicate.stdout.setEncoding('utf8');
  duplicate.stderr.setEncoding('utf8');
  duplicate.stdout.on('data', (chunk) => { output += chunk; });
  duplicate.stderr.on('data', (chunk) => { output += chunk; });
  const result = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      duplicate.kill();
      reject(new Error('A second Java service did not reject the already locked data directory.'));
    }, 10000);
    duplicate.once('error', (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    duplicate.once('exit', (code) => {
      clearTimeout(timeout);
      resolve(code);
    });
  });
  assert.notEqual(result, 0, 'a second service cannot start with the same SQLite data root');
  assert.match(output, /already using this data directory/i);
}

try {
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  const pageErrors = [];
  const consoleIssues = [];
  const mutations = [];
  const backupCreates = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      if (checkingExpectedSetupGate && message.type() === 'error'
          && message.text().includes('409 (Conflict)')) {
        expectedSetupGateConsoleErrors += 1;
        return;
      }
      consoleIssues.push(`${message.type()}: ${message.text()}`);
    }
  });
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/records')
        && ['POST', 'PUT', 'DELETE'].includes(request.method())) {
      mutations.push({ method: request.method(), url: request.url(), headers: request.headers() });
    }
    if (request.url().includes('/api/v1/backups') && request.method() === 'POST') {
      backupCreates.push({ url: request.url(), headers: request.headers(), body: request.postDataJSON() });
    }
  });

  server = await startServer();
  await assertSecondWriterRejected();
  await openRecords(page, server.origin, true);
  if (evidenceRoot) {
    await fs.mkdir(evidenceRoot, { recursive: true });
    await page.screenshot({ path: path.join(evidenceRoot, 'records-loaded.png'), fullPage: false });
  }
  const cookies = await context.cookies(server.origin);
  const sessionCookie = cookies.find((cookie) => cookie.name === 'ledgerx_session');
  assert.ok(sessionCookie, 'browser session cookie is set');
  assert.equal(sessionCookie.httpOnly, true);
  assert.equal(sessionCookie.sameSite, 'Strict');
  assert.equal(sessionCookie.path, '/');
  assert.equal(await page.evaluate(() => document.cookie.includes('ledgerx_session')), false,
    'session cookie is not visible to JavaScript');

  const note = `browser-e2e-${Date.now()}`;
  await page.getByRole('button', { name: '新增记录' }).click();
  await page.getByLabel('类型').selectOption('INCOME');
  const categoryId = await page.getByLabel('分类').locator('option:not([value=""])').first().getAttribute('value');
  const accountId = await page.getByLabel('结算账户').locator('option:not([value=""])').first().getAttribute('value');
  assert.ok(categoryId, 'seeded income category is available');
  assert.ok(accountId, 'seeded settlement account is available');
  await page.getByLabel('金额').fill('12.30');
  await page.getByLabel('分类').selectOption(categoryId);
  await page.getByLabel('结算账户').selectOption(accountId);
  await page.getByLabel('备注').fill(note);
  await page.getByRole('button', { name: '保存' }).click();
  await page.getByText('记录已保存。').waitFor({ timeout: 10000 });
  await page.getByText(note).waitFor();
  assert.equal(mutations.length, 1, 'one user save produces one record request');
  assert.match(mutations[0].headers['x-ledgerx-csrf'] || '', /^[A-Za-z0-9_-]{43}$/);
  assert.equal(mutations[0].headers.authorization, undefined);

  const oldCookieValue = sessionCookie.value;
  await page.reload();
  await openRecords(page, server.origin);
  await page.getByText(note).waitFor();
  const originalRow = page.locator('.record-list li').filter({ hasText: note });
  await originalRow.getByRole('button', { name: '编辑' }).click();
  await page.getByLabel('金额').fill('13.50');
  const editedNote = `${note}-edited`;
  await page.getByLabel('备注').fill(editedNote);
  await page.getByRole('button', { name: '保存' }).click();
  await page.getByText('记录已更新。').waitFor();
  await page.getByText(editedNote).waitFor();
  assert.equal(mutations.length, 2, 'edit sends one replacement request');

  await page.getByRole('button', { name: '设置' }).click();
  await page.getByRole('heading', { name: '设置与备份' }).waitFor();
  await page.getByRole('button', { name: '立即备份' }).click();
  await page.getByText('备份已创建并通过完整性校验。').first().waitFor({ timeout: 15000 });
  const backupRow = page.locator('.backup-item').first();
  await backupRow.getByText('1 条记录').waitFor();
  await backupRow.getByText('完整性已验证').waitFor();
  assert.equal(backupCreates.length, 1, 'creating one backup sends one request');
  assert.match(backupCreates[0].headers['x-ledgerx-csrf'] || '', /^[A-Za-z0-9_-]{43}$/);
  assert.match(backupCreates[0].headers['idempotency-key'] || '', /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.deepEqual(backupCreates[0].body, {}, 'backup creation uses the closed empty request body');
  await backupRow.getByRole('button', { name: '校验' }).click();
  await page.getByRole('status').filter({ hasText: '备份完整性校验通过' }).waitFor();
  const backupDownloadPromise = page.waitForEvent('download');
  await backupRow.getByRole('button', { name: '下载' }).click();
  const backupDownload = await backupDownloadPromise;
  assert.match(backupDownload.suggestedFilename(), /^[0-9a-f-]{36}\.ledgerx-backup$/);
  const downloadedPackage = await backupDownload.path();
  assert.ok(downloadedPackage && (await fs.stat(downloadedPackage)).size > 0,
    'browser receives a non-empty backup package');
  if (evidenceRoot) {
    await fs.mkdir(evidenceRoot, { recursive: true });
    await page.screenshot({ path: path.join(evidenceRoot, 'settings-backup-list.png'), fullPage: false });
  }

  const firstOrigin = server.origin;
  assert.equal(await stopServer(server.child), true, 'Java service stops on SIGINT');
  await assertPortReleased(firstOrigin);
  server = await startServer();
  await openRecords(page, server.origin);
  await page.getByText(editedNote).waitFor();
  await page.getByRole('button', { name: '设置' }).click();
  await page.getByRole('heading', { name: '设置与备份' }).waitFor();
  const persistedBackupRow = page.locator('.backup-item').first();
  await persistedBackupRow.getByText('尚未校验').waitFor();
  await persistedBackupRow.getByText('1 条记录').waitFor();
  await persistedBackupRow.getByRole('button', { name: '校验' }).click();
  await page.getByRole('status').filter({ hasText: '备份完整性校验通过' }).waitFor();
  await persistedBackupRow.getByText('完整性已验证').waitFor();
  await page.getByRole('button', { name: '收支记录' }).click();
  await page.getByRole('heading', { name: '收支记录' }).waitFor();
  const restartedCookies = await context.cookies(server.origin);
  const refreshedCookie = restartedCookies.find((cookie) => cookie.name === 'ledgerx_session');
  assert.ok(refreshedCookie && refreshedCookie.value !== oldCookieValue,
    'service restart replaces the previous process session');

  const editedRow = page.locator('.record-list li').filter({ hasText: editedNote });
  await editedRow.getByRole('button', { name: '删除' }).click();
  await page.getByRole('heading', { name: '移入回收站' }).waitFor();
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await page.getByText('记录已移入回收站。').waitFor();
  await page.getByRole('button', { name: '回收站' }).click();
  const trashedRow = page.locator('.record-list li').filter({ hasText: editedNote });
  await trashedRow.getByRole('button', { name: '恢复' }).click();
  await page.getByRole('heading', { name: '恢复记录' }).waitFor();
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await page.getByText('记录已恢复。').waitFor();
  await page.getByRole('button', { name: '当前记录' }).click();
  await page.getByText(editedNote).waitFor();
  await page.locator('.balance-grid article strong').getByText('13.50 CNY', { exact: true }).waitFor();
  const restoredOrigin = server.origin;
  assert.equal(await stopServer(server.child), true, 'service stops after restore');
  await assertPortReleased(restoredOrigin);
  server = await startServer();
  await openRecords(page, server.origin);
  await page.getByText(editedNote).waitFor();
  await page.locator('.balance-grid article strong').getByText('13.50 CNY', { exact: true }).waitFor();
  if (evidenceRoot) {
    await page.screenshot({ path: path.join(evidenceRoot, 'record-restored-after-restart.png'), fullPage: false });
  }
  assert.equal(mutations.length, 4, 'create, edit, trash and restore each send one mutation');
  for (const mutation of mutations) {
    assert.match(mutation.headers['x-ledgerx-csrf'] || '', /^[A-Za-z0-9_-]{43}$/);
    assert.equal(mutation.headers.authorization, undefined);
  }
  assert.deepEqual(pageErrors, [], 'page has no uncaught browser errors');
  assert.deepEqual(consoleIssues, [], 'page has no browser console errors or warnings');
  console.log('P7-002/P7-004 real browser E2E passed: setup gate and initialization, HttpOnly session, CSRF mutations, create/edit/trash/restore, balance, refresh, service restart, SQLite reload.');
  console.log('P9-001 real browser E2E passed: create a consistent backup through Java/SQLite, deep verify, stream-download, then list and verify it again after service restart.');
} finally {
  const finalOrigin = javaProcess && javaProcess.exitCode === null ? server?.origin : null;
  try {
    assert.equal(await stopServer(javaProcess), true, 'final Java service stops');
    if (finalOrigin) await assertPortReleased(finalOrigin);
  } finally {
    if (browser) await browser.close();
    const resolvedTemp = path.resolve(os.tmpdir());
    const resolvedData = path.resolve(dataRoot);
    if (resolvedData.startsWith(`${resolvedTemp}${path.sep}`)
        && path.basename(resolvedData).startsWith('ledgerx-browser-p7-')) {
      await fs.rm(resolvedData, { recursive: true, force: true });
    }
  }
}
