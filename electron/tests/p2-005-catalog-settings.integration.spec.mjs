import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { _electron as electron, expect, test } from '@playwright/test';

const require = createRequire(import.meta.url);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const electronRoot = path.join(projectRoot, 'electron');
const electronExecutable = require('electron');
const javaClassPath = [
  path.join(projectRoot, 'target', 'classes'),
  fs.readFileSync(path.join(projectRoot, 'target', 'cp.txt'), 'utf8').trim()
].join(path.delimiter);
const webRoot = path.join(projectRoot, 'frontend', 'dist');
const requestId = '7a5d1d72-6d38-4ad7-a902-2e88b4476f0f';
const PROFILE_A = '家庭账本';
const PROFILE_B = '工作账本';

test.describe.configure({ mode: 'serial' });

let electronApp = null;
let dataDir = null;
let userDataDir = null;
let temporaryRoot = null;
let networkObserver = null;
let mainLog = '';
const mainEvents = [];

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function environmentFor(userData) {
  const javaExecutable = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : (process.platform === 'win32' ? 'java.exe' : 'java');
  return {
    ...process.env,
    LEDGERX_TEST_JAVA_EXECUTABLE: javaExecutable,
    LEDGERX_TEST_JAVA_ARGS_JSON: JSON.stringify(['-cp', javaClassPath, 'com.ledgerx.http.HttpServerMain']),
    LEDGERX_TEST_DATA_DIR: dataDir,
    LEDGERX_TEST_WEB_ROOT: webRoot,
    LEDGERX_TEST_USER_DATA_DIR: userData
  };
}

function attachMainLogs(app) {
  const stream = app.process().stderr;
  if (!stream) return;
  stream.setEncoding('utf8');
  stream.on('data', (chunk) => {
    mainLog += chunk;
    for (const line of chunk.split(/\r?\n/)) {
      if (!line.startsWith('{')) continue;
      try {
        mainEvents.push(JSON.parse(line));
      } catch {
        // Chromium diagnostics are not structured LedgerX events.
      }
    }
  });
}

function eventIndex() {
  return mainEvents.length;
}

async function waitForEvent(predicate, startIndex = 0, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const match = mainEvents.slice(startIndex).find(predicate);
    if (match) return match;
    await sleep(50);
  }
  throw new Error(`timed out waiting for Electron event after ${timeoutMs}ms`);
}

function attachNetworkObserver(app) {
  const requests = [];
  let firstDocumentUrl = null;
  let resolveFirstDocument;
  const firstDocument = new Promise((resolve) => {
    resolveFirstDocument = resolve;
  });
  const handler = (request) => {
    let parsed;
    try {
      parsed = new URL(request.url());
    } catch {
      return;
    }
    if (!['http:', 'https:', 'ws:', 'wss:'].includes(parsed.protocol)) return;
    requests.push({ url: request.url(), resourceType: request.resourceType() });
    if (firstDocumentUrl === null && request.resourceType() === 'document') {
      firstDocumentUrl = request.url();
      resolveFirstDocument(firstDocumentUrl);
    }
  };
  app.context().on('request', handler);
  return { requests, firstDocument, get firstDocumentUrl() { return firstDocumentUrl; }, handler };
}

async function waitForFirstDocument(observer, timeoutMs = 10_000) {
  let timer;
  try {
    return await Promise.race([
      observer.firstDocument,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('首导航未被观察')), timeoutMs);
      })
    ]);
  } finally {
    clearTimeout(timer);
  }
}

function assertLoopbackNetwork(observer, expectedOrigin) {
  assert.ok(observer && observer.firstDocumentUrl, '首导航未被观察');
  const firstDocument = new URL(observer.firstDocumentUrl);
  assert.equal(firstDocument.origin, expectedOrigin);
  assert.equal(firstDocument.protocol, 'http:');
  assert.equal(firstDocument.hostname, '127.0.0.1');
  assert.ok(Number(firstDocument.port) > 0);
  for (const request of observer.requests) {
    assert.equal(new URL(request.url).origin, expectedOrigin,
      `renderer request escaped loopback origin: ${request.url}`);
  }
}

async function launchDesktop({ observeNetwork = false } = {}) {
  userDataDir = fs.mkdtempSync(path.join(temporaryRoot, 'userdata-'));
  electronApp = await electron.launch({
    cwd: electronRoot,
    args: [path.join(electronRoot, 'main.js')],
    env: environmentFor(userDataDir)
  });
  attachMainLogs(electronApp);
  if (observeNetwork) networkObserver = attachNetworkObserver(electronApp);
  const page = await electronApp.firstWindow();
  if (networkObserver) await waitForFirstDocument(networkObserver);
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  return page;
}

async function closeDesktop() {
  if (!electronApp) return;
  const app = electronApp;
  electronApp = null;
  if (networkObserver) {
    app.context().off('request', networkObserver.handler);
    networkObserver = null;
  }
  await app.close();
}

async function waitForProcessExit(pid, timeoutMs = 8_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      process.kill(pid, 0);
    } catch {
      return;
    }
    await sleep(100);
  }
  throw new Error(`process ${pid} did not exit within ${timeoutMs}ms`);
}

async function readStatus(page) {
  return page.evaluate(async (id) => {
    const response = await fetch('/api/v1/system/status', {
      headers: { Accept: 'application/json', 'X-Request-Id': id },
      credentials: 'omit'
    });
    return { status: response.status, payload: await response.json() };
  }, requestId);
}

function requestStatus(port, authorization = null, origin = null) {
  return new Promise((resolve, reject) => {
    const request = http.request({
      hostname: '127.0.0.1',
      port,
      path: '/api/v1/system/status',
      method: 'GET',
      headers: {
        Host: `127.0.0.1:${port}`,
        Accept: 'application/json',
        'X-Request-Id': requestId,
        ...(authorization ? { Authorization: authorization } : {}),
        ...(origin ? { Origin: origin } : {})
      }
    }, (response) => {
      response.resume();
      response.once('end', () => resolve(response.statusCode));
    });
    request.once('error', reject);
    request.end();
  });
}

function profileCard(page, name) {
  return page.locator('.profile-card').filter({
    has: page.getByRole('heading', { name })
  });
}

function fileInventory(root) {
  const result = [];
  if (!fs.existsSync(root)) return result;
  const walk = (directory, prefix = '') => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const relative = path.join(prefix, entry.name);
      if (entry.isDirectory()) walk(path.join(directory, entry.name), relative);
      else result.push(relative);
    }
  };
  walk(root);
  return result.sort();
}

test.beforeAll(() => {
  temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-p2-005-'));
  dataDir = path.join(temporaryRoot, 'data');
  fs.mkdirSync(dataDir);
  assert.equal(path.isAbsolute(dataDir), true);
  assert.notEqual(path.normalize(dataDir), path.normalize(projectRoot));
  assert.notEqual(path.normalize(dataDir), path.normalize(os.homedir()));
  const formalDataDirectory = process.env.LOCALAPPDATA
    ? path.join(process.env.LOCALAPPDATA, 'LedgerX')
    : null;
  if (formalDataDirectory) assert.notEqual(path.normalize(dataDir), path.normalize(formalDataDirectory));
});

test.afterAll(async () => {
  await closeDesktop();
  if (temporaryRoot && fs.existsSync(temporaryRoot)) {
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
});

test('P2-005 completes profile/settings isolation, archive and restart through real desktop path', async () => {
  const firstEventIndex = eventIndex();
  let page = await launchDesktop({ observeNetwork: true });
  const firstOrigin = new URL(page.url()).origin;
  const firstSpawn = await waitForEvent((event) => event.event === 'backend.spawned', firstEventIndex);
  const firstPid = firstSpawn.pid;
  const initialStatus = await readStatus(page);
  assert.equal(initialStatus.status, 200);
  assert.equal(initialStatus.payload.data.state, 'READY');
  assert.equal(initialStatus.payload.data.schemaVersion, 4);
  assert.match(initialStatus.payload.data.activeProfileId,
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(initialStatus.payload.meta.dataRevision, 0);
  assertLoopbackNetwork(networkObserver, firstOrigin);

  let oldAuthorization = null;
  const captureAuthorization = (request) => {
    if (!oldAuthorization && request.url().endsWith('/api/v1/system/status')) {
      void request.allHeaders().then((headers) => {
        if (headers.authorization) oldAuthorization = headers.authorization;
      });
    }
  };
  page.on('request', captureAuthorization);
  await readStatus(page);
  page.off('request', captureAuthorization);
  await expect.poll(() => oldAuthorization).toMatch(/^Bearer [A-Za-z0-9_-]{43}$/);
  const firstPort = Number(new URL(page.url()).port);
  assert.equal(await requestStatus(firstPort), 401);
  assert.equal(await requestStatus(firstPort, null, 'http://evil.invalid'), 403);
  assert.deepEqual(await page.evaluate(() => ({
    require: typeof window.require,
    process: typeof window.process,
    ipcRenderer: typeof window.ipcRenderer,
    token: typeof window.token,
    desktop: typeof window.desktop
  })), {
    require: 'undefined',
    process: 'undefined',
    ipcRenderer: 'undefined',
    token: 'undefined',
    desktop: 'object'
  });

  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page.getByRole('heading', { name: '用户空间' })).toBeFocused();
  await expect(page.getByRole('list', { name: '用户空间列表' }).locator('li')).toHaveCount(1);
  await page.getByLabel('新建用户空间').fill(PROFILE_A);
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('创建用户空间成功。')).toBeVisible();
  await expect(profileCard(page, PROFILE_A).getByText('当前空间')).toBeVisible();

  await page.getByLabel('新建用户空间').fill(PROFILE_B);
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('创建用户空间成功。')).toBeVisible();
  await expect(profileCard(page, PROFILE_B).getByText('当前空间')).toBeVisible();
  await expect(profileCard(page, PROFILE_A).getByRole('button', { name: '切换' })).toBeVisible();

  const filesBeforeSettings = fileInventory(dataDir);
  await page.getByRole('button', { name: '设置' }).click();
  await expect(page.getByRole('heading', { name: '设置' })).toBeFocused();
  await expect(page.getByRole('heading', { name: '手工备份' })).toBeVisible();
  await expect(page.getByText('复制整个 LedgerX 数据目录')).toBeVisible();
  const filesAfterSettings = fileInventory(dataDir);
  assert.deepEqual(filesAfterSettings.filter((name) => /backup|notification|theme/i.test(name)), []);
  assert.deepEqual(filesBeforeSettings.filter((name) => /backup|notification|theme/i.test(name)), []);

  await page.getByRole('button', { name: '用户空间' }).click();
  await profileCard(page, PROFILE_A).getByRole('button', { name: '切换' }).click();
  await expect(page.getByText('切换用户空间成功。')).toBeVisible();
  await page.getByRole('button', { name: '设置' }).click();
  await expect(page.getByText('复制整个 LedgerX 数据目录')).toBeVisible();

  await page.getByRole('button', { name: '用户空间' }).click();
  await profileCard(page, PROFILE_B).getByRole('button', { name: '切换' }).click();
  await expect(page.getByText('切换用户空间成功。')).toBeVisible();
  await page.getByRole('button', { name: '设置' }).click();
  await expect(page.getByText('复制整个 LedgerX 数据目录')).toBeVisible();

  await page.getByRole('button', { name: '用户空间' }).click();
  await profileCard(page, PROFILE_A).getByRole('button', { name: '切换' }).click();
  await expect(page.getByText('切换用户空间成功。')).toBeVisible();

  const archiveRequests = [];
  const archiveRequestListener = (request) => {
    if (request.method() === 'DELETE' && request.url().includes('/api/v1/profiles/')) {
      archiveRequests.push(request.url());
    }
  };
  page.on('request', archiveRequestListener);
  await profileCard(page, PROFILE_B).getByRole('button', { name: '归档' }).click();
  await expect(page.getByRole('group', { name: `确认归档 ${PROFILE_B}` })).toBeVisible();
  assert.equal(archiveRequests.length, 0);
  await page.getByRole('group', { name: `确认归档 ${PROFILE_B}` }).getByRole('button', { name: '取消' }).click();
  assert.equal(archiveRequests.length, 0);
  await profileCard(page, PROFILE_B).getByRole('button', { name: '归档' }).click();
  await page.getByRole('group', { name: `确认归档 ${PROFILE_B}` }).getByRole('button', { name: '确认归档' }).click();
  await expect(page.getByText('归档用户空间成功。')).toBeVisible();
  await expect(page.getByRole('heading', { name: PROFILE_B })).toHaveCount(0);
  assert.equal(archiveRequests.length, 1);
  page.off('request', archiveRequestListener);

  await page.getByLabel('显示已归档').check();
  const archivedCard = profileCard(page, PROFILE_B);
  await expect(archivedCard.locator('.profile-status[data-status="ARCHIVED"]')).toBeVisible();
  await expect(archivedCard.getByRole('button', { name: '恢复' })).toHaveCount(0);
  await expect(archivedCard.getByRole('button', { name: '删除' })).toHaveCount(0);
  const activeBeforeClose = await readStatus(page);
  const activeProfileId = activeBeforeClose.payload.data.activeProfileId;
  assert.match(activeProfileId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  const firstRendererRequestCount = networkObserver.requests.length;

  await closeDesktop();
  await waitForProcessExit(firstPid);
  assert.equal(fs.existsSync(path.join(dataDir, 'profiles.db')), true);

  const restartEventIndex = eventIndex();
  page = await launchDesktop({ observeNetwork: true });
  const restartedOrigin = new URL(page.url()).origin;
  const restartedSpawn = await waitForEvent((event) => event.event === 'backend.spawned', restartEventIndex);
  const restartedStatus = await readStatus(page);
  assert.equal(restartedStatus.status, 200);
  assert.equal(restartedStatus.payload.data.state, 'READY');
  assert.equal(restartedStatus.payload.data.schemaVersion, 4);
  assert.equal(restartedStatus.payload.data.activeProfileId, activeProfileId);
  assert.equal(await requestStatus(Number(new URL(page.url()).port), oldAuthorization), 401);
  assertLoopbackNetwork(networkObserver, restartedOrigin);

  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(profileCard(page, PROFILE_A).getByText('当前空间')).toBeVisible();
  await page.getByLabel('显示已归档').check();
  await expect(profileCard(page, PROFILE_B).locator('.profile-status[data-status="ARCHIVED"]')).toBeVisible();
  await page.getByRole('button', { name: '设置' }).click();
  await expect(page.getByText('复制整个 LedgerX 数据目录')).toBeVisible();
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  const restartRendererRequestCount = networkObserver.requests.length;

  assert.equal(mainLog.includes(PROFILE_A), false);
  assert.equal(mainLog.includes(PROFILE_B), false);
  assert.equal(mainLog.includes('1234.50'), false);
  assert.equal(mainLog.includes(dataDir), false);
  assert.equal(mainLog.includes('LEDGERX_SESSION_TOKEN'), false);
  assert.equal(mainLog.includes('Authorization'), false);

  const finalPid = restartedSpawn.pid;
  await closeDesktop();
  await waitForProcessExit(finalPid);
  assert.equal(fs.existsSync(path.join(dataDir, 'profiles.db')), true);
  console.log(`[P2-005 evidence] real desktop flow profileIsolation=true archived=true reopened=true rendererRequests=${firstRendererRequestCount + restartRendererRequestCount}`);
});
