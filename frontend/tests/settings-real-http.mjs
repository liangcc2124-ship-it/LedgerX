import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { chromium } from '@playwright/test';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const frontendRoot = path.join(projectRoot, 'frontend');
const javaClassPath = [
  path.join(projectRoot, 'target', 'classes'),
  fs.readFileSync(path.join(projectRoot, 'target', 'cp.txt'), 'utf8').trim(),
].join(path.delimiter);
const javaExecutable = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : (process.platform === 'win32' ? 'java.exe' : 'java');
const viteExecutable = path.join(frontendRoot, 'node_modules', 'vite', 'bin', 'vite.js');
const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-p2-003-real-'));
const dataDir = path.join(temporaryRoot, 'data');
fs.mkdirSync(dataDir);
const tokenBytes = new Uint8Array(32);
globalThis.crypto?.getRandomValues?.(tokenBytes);
const token = Buffer.from(tokenBytes.some((value) => value !== 0)
  ? tokenBytes
  : Uint8Array.from({ length: 32 }, () => Math.floor(Math.random() * 256))).toString('base64url');
const vitePort = 4318;
let javaProcess;
let viteProcess;
let browser;

function waitForOutput(child, pattern, timeoutMs = 15_000) {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`timed out waiting for process output: ${pattern}`));
    }, timeoutMs);
    const onData = (chunk) => {
      buffer += chunk.toString();
      const match = buffer.match(pattern);
      if (match) {
        cleanup();
        resolve(match);
      }
    };
    const onExit = (code, signal) => {
      cleanup();
      reject(new Error(`process exited before readiness: code=${code} signal=${signal}`));
    };
    const cleanup = () => {
      clearTimeout(timer);
      child.stdout?.off('data', onData);
      child.stderr?.off('data', onData);
      child.off('exit', onExit);
    };
    child.stdout?.on('data', onData);
    child.stderr?.on('data', onData);
    child.once('exit', onExit);
  });
}

async function waitForHttp(url, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.status >= 200 && response.status < 500) return;
    } catch {
      // The development server is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`timed out waiting for HTTP server: ${url}`);
}

async function stopProcess(child) {
  if (!child || child.exitCode !== null) return;
  child.kill();
  await new Promise((resolve) => child.once('exit', resolve));
}

try {
  javaProcess = spawn(javaExecutable, ['-cp', javaClassPath, 'com.ledgerx.http.HttpServerMain'], {
    cwd: projectRoot,
    env: { ...process.env, LEDGERX_DATA_DIR: dataDir, LEDGERX_SESSION_TOKEN: token },
    windowsHide: true,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const ready = await waitForOutput(javaProcess, /LEDGERX_READY (\{"port":(\d+),"protocol":"1"\})/);
  const javaPort = Number(ready[2]);
  assert.ok(javaPort > 0 && javaPort <= 65535);

  viteProcess = spawn(process.execPath, [viteExecutable, '--host', '127.0.0.1', '--port', String(vitePort)], {
    cwd: frontendRoot,
    env: {
      ...process.env,
      LEDGERX_DEV_API_ORIGIN: `http://127.0.0.1:${javaPort}`,
      LEDGERX_DEV_API_TOKEN: token,
    },
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  await waitForHttp(`http://127.0.0.1:${vitePort}/`);

  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  const patchRequests = [];
  page.on('request', (request) => {
    if (request.url().endsWith('/api/v1/settings') && request.method() === 'PATCH') patchRequests.push(request);
  });
  await page.goto(`http://127.0.0.1:${vitePort}/`);
  await page.getByText('本地服务已连接').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByRole('button', { name: '设置' }).click();
  await page.getByText('服务端信息（只读）').waitFor({ state: 'visible', timeout: 15_000 });
  assert.equal(await page.getByLabel(/启用通知策略/).isChecked(), false);

  await page.getByLabel(/启用通知策略/).check();
  await page.getByLabel('隐藏所有金额').check();
  await page.getByRole('button', { name: '保存设置' }).click();
  await page.getByText('设置已保存。').waitFor({ state: 'visible', timeout: 15_000 });
  assert.equal(patchRequests.length, 1);
  const patch = patchRequests[0];
  const patchBody = JSON.parse(patch.postData());
  assert.deepEqual(patchBody, { notificationsEnabled: true, hideAllAmounts: true });
  assert.match(patch.headers()['if-match'], /^"0"$/);
  assert.match(patch.headers()['idempotency-key'], /^[0-9a-f-]{36}$/);
  assert.equal(patch.headers().authorization, undefined);

  await page.reload();
  await page.getByText('本地服务已连接').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByRole('button', { name: '设置' }).click();
  await page.getByText('服务端信息（只读）').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByLabel(/启用通知策略/).waitFor({ state: 'visible' });
  assert.equal(await page.getByLabel(/启用通知策略/).isChecked(), true);
  assert.equal(await page.getByLabel('隐藏所有金额').isChecked(), true);
  assert.equal(fs.existsSync(path.join(dataDir, 'profiles.db')), true);
  console.log(`[P2-003 real HTTP] isolatedData=${dataDir} javaPort=${javaPort} vitePort=${vitePort} patch-reload=PASS`);
} finally {
  await browser?.close();
  await stopProcess(viteProcess);
  if (javaProcess && javaProcess.exitCode === null) {
    javaProcess.stdin.write('LEDGERX_STOP\n');
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  await stopProcess(javaProcess);
  if (fs.existsSync(temporaryRoot)) fs.rmSync(temporaryRoot, { recursive: true, force: true });
}
