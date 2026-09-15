import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { _electron as electron, expect, test } from '@playwright/test';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const electronRoot = path.join(projectRoot, 'electron');
const javaClassPath = [
  path.join(projectRoot, 'target', 'classes'),
  fs.readFileSync(path.join(projectRoot, 'target', 'cp.txt'), 'utf8').trim()
].join(path.delimiter);

let electronApp;
let dataDir;
let userDataDir;

function requestWithoutToken(port) {
  return new Promise((resolve, reject) => {
    const request = http.get({
      hostname: '127.0.0.1',
      port,
      path: '/api/v1/system/status',
      headers: { Host: `127.0.0.1:${port}`, 'X-Request-Id': '7b3e8c1e-0f4f-4fb7-b1c0-13a9558db3fd' }
    }, (response) => {
      response.resume();
      response.once('end', () => resolve(response.statusCode));
    });
    request.once('error', reject);
  });
}

test.beforeAll(async () => {
  const javaExecutable = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : (process.platform === 'win32' ? 'java.exe' : 'java');
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-electron-e2e-'));
  userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-electron-userdata-'));
  electronApp = await electron.launch({
    cwd: electronRoot,
    args: [path.join(electronRoot, 'main.js')],
    env: {
      ...process.env,
      LEDGERX_TEST_JAVA_EXECUTABLE: javaExecutable,
      LEDGERX_TEST_JAVA_ARGS_JSON: JSON.stringify(['-cp', javaClassPath, 'com.ledgerx.http.HttpServerMain']),
      LEDGERX_TEST_DATA_DIR: dataDir,
      LEDGERX_TEST_WEB_ROOT: path.join(projectRoot, 'frontend', 'dist'),
      LEDGERX_TEST_USER_DATA_DIR: userDataDir
    }
  });
});

test.afterAll(async () => {
  if (electronApp) {
    await electronApp.close();
  }
  if (dataDir && fs.existsSync(dataDir)) {
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
  if (userDataDir && fs.existsSync(userDataDir)) {
    fs.rmSync(userDataDir, { recursive: true, force: true });
  }
});

test('real Electron renderer loads Vue through Java same-origin REST', async () => {
  const page = await electronApp.firstWindow();
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  await expect(page.getByText('0.1.0-SNAPSHOT')).toBeVisible();
  await expect(page.getByText('READY')).toBeVisible();

  const globals = await page.evaluate(() => ({
    require: typeof window.require,
    process: typeof window.process,
    ipcRenderer: typeof window.ipcRenderer,
    token: typeof window.token,
    desktop: typeof window.desktop
  }));
  assert.deepEqual(globals, {
    require: 'undefined',
    process: 'undefined',
    ipcRenderer: 'undefined',
    token: 'undefined',
    desktop: 'object'
  });

  const port = Number(new URL(page.url()).port);
  assert.ok(port > 0);
  assert.equal(await requestWithoutToken(port), 401);
});

test('renderer refresh keeps the same backend origin', async () => {
  const page = await electronApp.firstWindow();
  const firstOrigin = new URL(page.url()).origin;
  for (let index = 0; index < 10; index += 1) {
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('本地服务已连接')).toBeVisible();
    assert.equal(new URL(page.url()).origin, firstOrigin);
  }
});
