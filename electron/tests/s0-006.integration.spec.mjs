import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
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
const requestId = '6e40b7c4-8e2f-4d44-9c01-4d4a5f7e2b19';

test.describe.configure({ mode: 'serial' });

let electronApp;
let dataDir;
let userDataDir;
let temporaryRoot;
const userDataDirectories = [];
const mainEvents = [];
let mainLog = '';
let networkObserver = null;

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
    LEDGERX_TEST_USER_DATA_DIR: userData,
    ELECTRON_ENABLE_LOGGING: '1'
  };
}

function environmentForDefault(localAppData, inheritedDataDir = null) {
  const environment = { ...process.env };
  for (const name of [
    'LEDGERX_TEST_JAVA_EXECUTABLE',
    'LEDGERX_TEST_JAVA_ARGS_JSON',
    'LEDGERX_TEST_DATA_DIR',
    'LEDGERX_TEST_WEB_ROOT',
    'LEDGERX_TEST_USER_DATA_DIR',
    'LEDGERX_DATA_DIR'
  ]) {
    delete environment[name];
  }
  environment.LOCALAPPDATA = localAppData;
  environment.APPDATA = path.join(path.dirname(localAppData), 'roaming');
  if (inheritedDataDir) {
    environment.LEDGERX_DATA_DIR = inheritedDataDir;
  }
  environment.ELECTRON_ENABLE_LOGGING = '1';
  return environment;
}

function attachMainLogs(app) {
  const stream = app.process().stderr;
  if (!stream) {
    return;
  }
  stream.setEncoding('utf8');
  stream.on('data', (chunk) => {
    mainLog += chunk;
    for (const line of chunk.split(/\r?\n/)) {
      if (!line.startsWith('{')) {
        continue;
      }
      try {
        mainEvents.push(JSON.parse(line));
      } catch {
        // Chromium diagnostics are intentionally ignored; structured LedgerX events are retained.
      }
    }
  });
}

async function waitForEvent(predicate, startIndex = 0, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const match = mainEvents.slice(startIndex).find(predicate);
    if (match) {
      return match;
    }
    await sleep(50);
  }
  throw new Error(`timed out waiting for structured Electron event after ${timeoutMs}ms; recent=${JSON.stringify(mainEvents.slice(-8))}`);
}

function eventIndex() {
  return mainEvents.length;
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
    if (!['http:', 'https:', 'ws:', 'wss:'].includes(parsed.protocol)) {
      return;
    }
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
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

function assertLoopbackNetwork(observer, expectedOrigin) {
  assert.ok(observer && observer.firstDocumentUrl, '首导航未被观察');
  assert.equal(new URL(observer.firstDocumentUrl).origin, expectedOrigin);
  assert.equal(new URL(observer.firstDocumentUrl).protocol, 'http:');
  assert.equal(new URL(observer.firstDocumentUrl).hostname, '127.0.0.1');
  assert.ok(Number(new URL(observer.firstDocumentUrl).port) > 0);
  for (const request of observer.requests) {
    const parsed = new URL(request.url);
    assert.equal(parsed.origin, expectedOrigin, `renderer request escaped loopback origin: ${request.url}`);
  }
}

async function launchDesktop({ observeNetwork = false } = {}) {
  userDataDir = fs.mkdtempSync(path.join(temporaryRoot, 'userdata-'));
  userDataDirectories.push(userDataDir);
  electronApp = await electron.launch({
    cwd: electronRoot,
    args: [path.join(electronRoot, 'main.js')],
    env: environmentFor(userDataDir)
  });
  if (observeNetwork) {
    networkObserver = attachNetworkObserver(electronApp);
  }
  attachMainLogs(electronApp);
  const page = await electronApp.firstWindow();
  if (networkObserver) {
    await waitForFirstDocument(networkObserver);
  }
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  return page;
}

async function closeDesktop() {
  if (!electronApp) {
    return;
  }
  const app = electronApp;
  electronApp = null;
  if (networkObserver) {
    app.context().off('request', networkObserver.handler);
    networkObserver = null;
  }
  await app.close();
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

function requestStatus(port, authorization, origin = null) {
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
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve({
        status: response.statusCode,
        body: Buffer.concat(chunks).toString('utf8')
      }));
    });
    request.on('error', reject);
    request.end();
  });
}

function isProcessAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function waitForProcessExit(pid, timeoutMs = 8_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!isProcessAlive(pid)) {
      return;
    }
    await sleep(100);
  }
  throw new Error(`process ${pid} did not exit within ${timeoutMs}ms`);
}

function startRawDesktop(dataPath, userPath) {
  const child = spawn(electronExecutable, [path.join(electronRoot, 'main.js')], {
    cwd: electronRoot,
    env: { ...environmentFor(userPath), LEDGERX_TEST_DATA_DIR: dataPath },
    windowsHide: true,
    stdio: ['ignore', 'ignore', 'pipe']
  });
  const result = { child, events: [], log: '' };
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => {
    result.log += chunk;
    for (const line of chunk.split(/\r?\n/)) {
      if (!line.startsWith('{')) {
        continue;
      }
      try {
        result.events.push(JSON.parse(line));
      } catch {
        // Ignore Chromium diagnostics and retain only structured LedgerX events.
      }
    }
  });
  return result;
}

async function waitForRawEvent(raw, predicate, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const match = raw.events.find(predicate);
    if (match) {
      return match;
    }
    await sleep(50);
  }
  throw new Error(`timed out waiting for raw Electron event after ${timeoutMs}ms`);
}

function spawnSecondInstance() {
  const child = spawn(electronExecutable, [path.join(electronRoot, 'main.js')], {
    cwd: electronRoot,
    env: environmentFor(userDataDir),
    windowsHide: true,
    stdio: ['ignore', 'ignore', 'pipe']
  });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (child.exitCode === null) {
        child.kill();
      }
      reject(new Error('second Electron instance did not exit after the single-instance handoff'));
    }, 8_000);
    child.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.once('exit', (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal });
    });
  });
}

test.beforeAll(() => {
  temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-s0-006-'));
  dataDir = path.join(temporaryRoot, 'data');
  fs.mkdirSync(dataDir);
  assert.equal(path.isAbsolute(dataDir), true);
  assert.notEqual(path.normalize(dataDir), path.normalize(projectRoot));
  assert.notEqual(path.normalize(dataDir), path.normalize(os.homedir()));
  const formalDataDirectory = process.env.LOCALAPPDATA
    ? path.join(process.env.LOCALAPPDATA, 'LedgerX')
    : null;
  if (formalDataDirectory) {
    assert.notEqual(path.normalize(dataDir), path.normalize(formalDataDirectory));
  }
});

test.afterAll(async () => {
  await closeDesktop();
  if (temporaryRoot && fs.existsSync(temporaryRoot)) {
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
});

test('S0-006 validates real Electron, Java, REST, SQLite restart and recovery', async () => {
  let page = await launchDesktop({ observeNetwork: true });
  const firstOrigin = new URL(page.url()).origin;
  const firstPort = Number(new URL(page.url()).port);
  const firstSpawnIndex = eventIndex();
  const firstSpawn = await waitForEvent((event) => event.event === 'backend.spawned', 0);
  const firstPid = firstSpawn.pid;
  assert.ok(Number.isInteger(firstPid) && firstPid > 0);

  const initialStatus = await readStatus(page);
  assert.equal(initialStatus.status, 200);
  assert.equal(initialStatus.payload.data.state, 'READY');
  assert.equal(initialStatus.payload.data.schemaVersion, 4);
  assert.match(initialStatus.payload.data.activeProfileId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(initialStatus.payload.meta.dataRevision, 0);
  assertLoopbackNetwork(networkObserver, firstOrigin);

  for (const assetName of fs.readdirSync(path.join(webRoot, 'assets'))) {
    const asset = fs.readFileSync(path.join(webRoot, 'assets', assetName), 'utf8');
    assert.equal(asset.includes('LEDGERX_SESSION_TOKEN'), false);
    assert.equal(asset.includes('Bearer '), false);
    assert.equal(asset.includes(dataDir), false);
  }

  let oldAuthorization;
  let resolveAuthorization;
  const authorizationCaptured = new Promise((resolve) => {
    resolveAuthorization = resolve;
  });
  const requestListener = (request) => {
    if (request.url().endsWith('/api/v1/system/status')) {
      void request.allHeaders().then((headers) => {
        oldAuthorization = headers.authorization;
        resolveAuthorization();
      });
    }
  };
  page.on('request', requestListener);
  const rendererStatus = await readStatus(page);
  page.off('request', requestListener);
  await authorizationCaptured;
  assert.equal(rendererStatus.status, 200);
  assert.match(oldAuthorization, /^Bearer [A-Za-z0-9_-]{43}$/);
  assert.equal(await requestStatus(firstPort, null).then((result) => result.status), 401);
  assert.equal(await requestStatus(firstPort, null, 'http://evil.invalid').then((result) => result.status), 403);
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
  const secondInstance = await spawnSecondInstance();
  assert.equal(secondInstance.code, 0);
  assert.equal(new URL(page.url()).origin, firstOrigin);
  assert.equal(mainEvents.filter((event) => event.event === 'backend.spawned').length, 1);

  for (let index = 0; index < 10; index += 1) {
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('本地服务已连接')).toBeVisible();
    assert.equal(new URL(page.url()).origin, firstOrigin);
  }
  assertLoopbackNetwork(networkObserver, firstOrigin);
  const observedRequestCount = networkObserver.requests.length;

  process.kill(firstPid);
  await waitForProcessExit(firstPid);
  await expect(page.getByRole('heading', { name: '本地服务暂时不可用' })).toBeVisible();
  assert.equal(electronApp.process().exitCode, null);
  assert.equal(await page.evaluate(() => typeof window.desktop?.retryBackend), 'function');
  const errorPageProbe = await page.evaluate(() => ({
    href: window.location.href,
    retryText: document.getElementById('retry')?.textContent,
    scriptCount: document.scripts.length
  }));
  assert.match(errorPageProbe.href, /error\.html/);
  assert.equal(errorPageProbe.retryText, '重试连接');
  assert.equal(errorPageProbe.scriptCount, 1);
  const retryStart = eventIndex();
  // The IPC call destroys the local error page synchronously before its
  // promise can resolve; click the real error-page button and accept
  // that expected page-closed signal, then assert the new backend session.
  await page.getByRole('button', { name: '重试连接' }).click().catch((error) => {
    if (!String(error && error.message).includes('closed')) {
      throw error;
    }
  });
  await waitForEvent((event) => event.event === 'backend.spawned', retryStart);
  page = await electronApp.firstWindow();
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  const retryStatus = await readStatus(page);
  assert.equal(retryStatus.payload.data.activeProfileId, initialStatus.payload.data.activeProfileId);
  const retryPid = mainEvents.slice(retryStart).find((event) => event.event === 'backend.spawned').pid;
  assert.notEqual(retryPid, firstPid);

  await closeDesktop();
  await waitForProcessExit(retryPid);
  assert.equal(fs.existsSync(path.join(dataDir, 'profiles.db')), true);

  page = await launchDesktop();
  const restartedPort = Number(new URL(page.url()).port);
  const restartedStatus = await readStatus(page);
  assert.equal(restartedStatus.payload.data.state, 'READY');
  assert.equal(restartedStatus.payload.data.activeProfileId, initialStatus.payload.data.activeProfileId);
  assert.equal(restartedStatus.payload.meta.dataRevision, 0);
  assert.equal(await requestStatus(restartedPort, oldAuthorization).then((result) => result.status), 401);

  const finalSpawn = mainEvents.slice(firstSpawnIndex).filter((event) => event.event === 'backend.spawned').at(-1);
  assert.ok(finalSpawn && Number.isInteger(finalSpawn.pid));
  await closeDesktop();
  await waitForProcessExit(finalSpawn.pid);

  const abnormalDataDir = path.join(temporaryRoot, 'abnormal-data');
  const abnormalUserDataDir = path.join(temporaryRoot, 'abnormal-userdata');
  fs.mkdirSync(abnormalDataDir);
  fs.mkdirSync(abnormalUserDataDir);
  const abnormal = startRawDesktop(abnormalDataDir, abnormalUserDataDir);
  const abnormalSpawn = await waitForRawEvent(abnormal, (event) => event.event === 'backend.spawned');
  await waitForRawEvent(abnormal, (event) => event.event === 'renderer.loaded');
  abnormal.child.kill();
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('abnormal Electron parent did not exit')), 8_000);
    abnormal.child.once('exit', () => {
      clearTimeout(timer);
      resolve();
    });
  });
  await waitForProcessExit(abnormalSpawn.pid);
  assert.equal(abnormal.log.includes(abnormalDataDir), false);

  assert.equal(mainLog.includes(dataDir), false);
  assert.equal(mainLog.includes('LEDGERX_SESSION_TOKEN'), false);
  assert.equal(mainLog.includes('Authorization'), false);
  console.log(`[BUG-004 evidence] isolated Electron firstPid=${firstPid} retryPid=${retryPid} firstPort=${firstPort} restartedPort=${restartedPort} rendererRequests=${observedRequestCount}`);
});

test('BUG-001 default startup passes the canonical data root and survives reopen', async () => {
  const defaultRoot = fs.mkdtempSync(path.join(temporaryRoot, 'bug-001-default-'));
  const localAppData = path.join(defaultRoot, 'local');
  const userPath = path.join(defaultRoot, 'electron-user');
  const inheritedDataDir = path.join(defaultRoot, 'inherited-data');
  fs.mkdirSync(localAppData, { recursive: true });
  fs.mkdirSync(userPath, { recursive: true });
  fs.mkdirSync(inheritedDataDir, { recursive: true });
  const canonicalDataDir = path.join(localAppData, 'LedgerX');
  const formalDataDirectory = process.env.LOCALAPPDATA
    ? path.join(process.env.LOCALAPPDATA, 'LedgerX')
    : null;
  for (const isolatedPath of [defaultRoot, localAppData, userPath, inheritedDataDir]) {
    assert.notEqual(path.normalize(isolatedPath), path.normalize(projectRoot));
    assert.notEqual(path.normalize(isolatedPath), path.normalize(os.homedir()));
    if (formalDataDirectory) {
      assert.notEqual(path.normalize(isolatedPath), path.normalize(formalDataDirectory));
    }
  }

  const firstStart = eventIndex();
  electronApp = await electron.launch({
    cwd: electronRoot,
    args: [path.join(electronRoot, 'main.js'), `--user-data-dir=${userPath}`],
    env: environmentForDefault(localAppData, inheritedDataDir)
  });
  networkObserver = attachNetworkObserver(electronApp);
  attachMainLogs(electronApp);
  let page = await electronApp.firstWindow();
  await waitForFirstDocument(networkObserver);
  await page.waitForLoadState('domcontentloaded');
  const firstStatus = await readStatus(page);
  assert.equal(firstStatus.status, 200);
  assert.equal(firstStatus.payload.data.state, 'READY');
  assert.equal(firstStatus.payload.data.schemaVersion, 4);
  assert.match(firstStatus.payload.data.activeProfileId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(firstStatus.payload.meta.dataRevision, 0);
  const firstOrigin = new URL(page.url()).origin;
  assertLoopbackNetwork(networkObserver, firstOrigin);
  const firstPort = Number(new URL(page.url()).port);

  const firstEvents = mainEvents.slice(firstStart);
  const firstNames = firstEvents.map((event) => event.event);
  for (const eventName of ['desktop.start', 'backend.spawned', 'backend.listening', 'backend.status_checked', 'renderer.loaded']) {
    assert.ok(firstNames.includes(eventName), `missing ${eventName}: ${JSON.stringify(firstNames)}`);
  }
  assert.ok(firstNames.indexOf('desktop.start') < firstNames.indexOf('backend.spawned'));
  assert.ok(firstNames.indexOf('backend.spawned') < firstNames.indexOf('backend.listening'));
  assert.ok(firstNames.indexOf('backend.listening') < firstNames.indexOf('backend.status_checked'));
  assert.ok(firstNames.indexOf('backend.status_checked') < firstNames.indexOf('renderer.loaded'));
  assert.equal(mainLog.includes(localAppData), false);
  assert.equal(mainLog.includes(userPath), false);
  assert.equal(mainLog.includes(inheritedDataDir), false);

  const catalogPath = path.join(canonicalDataDir, 'profiles.db');
  assert.equal(fs.existsSync(catalogPath), true);
  assert.equal(fs.existsSync(path.join(inheritedDataDir, 'profiles.db')), false);
  const profileRoot = path.join(canonicalDataDir, 'Profiles');
  const profileDirectories = fs.readdirSync(profileRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory());
  assert.equal(profileDirectories.length, 1);
  assert.equal(fs.existsSync(path.join(profileRoot, profileDirectories[0].name, 'ledger.db')), true);
  const firstProfileId = firstStatus.payload.data.activeProfileId;
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  assertLoopbackNetwork(networkObserver, firstOrigin);
  const observedRequestCount = networkObserver.requests.length;

  await closeDesktop();
  electronApp = await electron.launch({
    cwd: electronRoot,
    args: [path.join(electronRoot, 'main.js'), `--user-data-dir=${userPath}`],
    env: environmentForDefault(localAppData, inheritedDataDir)
  });
  attachMainLogs(electronApp);
  page = await electronApp.firstWindow();
  await page.waitForLoadState('domcontentloaded');
  const reopenedStatus = await readStatus(page);
  assert.equal(reopenedStatus.status, 200);
  assert.equal(reopenedStatus.payload.data.state, 'READY');
  assert.equal(reopenedStatus.payload.data.activeProfileId, firstProfileId);
  assert.equal(reopenedStatus.payload.meta.dataRevision, 0);
  assert.equal(fs.existsSync(catalogPath), true);
  assert.equal(fs.existsSync(path.join(profileRoot, profileDirectories[0].name, 'ledger.db')), true);
  await closeDesktop();
  console.log(`[BUG-004 evidence] default isolated firstPort=${firstPort} rendererRequests=${observedRequestCount} profileReopened=true`);
});
