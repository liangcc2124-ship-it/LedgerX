import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
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
const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-p2-002-real-'));
const dataDir = path.join(temporaryRoot, 'data');
fs.mkdirSync(dataDir);
const token = Buffer.from(cryptoRandomBytes(32)).toString('base64url');
const vitePort = 4317;
let javaProcess;
let viteProcess;
let browser;

function cryptoRandomBytes(size) {
  const bytes = new Uint8Array(size);
  globalThis.crypto?.getRandomValues?.(bytes);
  if (bytes.some((value) => value !== 0)) return bytes;
  return Uint8Array.from({ length: size }, () => Math.floor(Math.random() * 256));
}

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
    env: {
      ...process.env,
      LEDGERX_DATA_DIR: dataDir,
      LEDGERX_SESSION_TOKEN: token,
      LEDGERX_WEB_ROOT: path.join(frontendRoot, 'dist'),
    },
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
  await page.goto(`http://127.0.0.1:${vitePort}/`);
  await page.getByText('本地服务已连接').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByRole('button', { name: '用户空间' }).click();
  await page.getByText('默认空间').waitFor({ state: 'visible', timeout: 15_000 });

  const initialRows = page.getByRole('listitem');
  assert.equal(await initialRows.count(), 1);
  const defaultRow = initialRows.filter({ hasText: '默认空间' });
  await defaultRow.getByText('当前空间').waitFor({ state: 'visible' });

  const createdName = `真实隔离空间-${Date.now()}`;
  await page.getByLabel('新建用户空间').fill(createdName);
  await page.getByRole('button', { name: '创建' }).click();
  await page.getByText('创建用户空间成功。').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByText(createdName).waitFor({ state: 'visible', timeout: 15_000 });
  const createdRow = page.getByRole('listitem').filter({ hasText: createdName });
  await createdRow.getByText('当前空间').waitFor({ state: 'visible' });

  await page.reload();
  await page.getByText('本地服务已连接').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByRole('button', { name: '用户空间' }).click();
  await page.getByText(createdName).waitFor({ state: 'visible', timeout: 15_000 });
  const reloadedCreatedRow = page.getByRole('listitem').filter({ hasText: createdName });
  await reloadedCreatedRow.getByText('当前空间').waitFor({ state: 'visible' });

  await page.getByRole('button', { name: '切换' }).click();
  await page.getByText('切换用户空间成功。').waitFor({ state: 'visible', timeout: 15_000 });
  const reactivatedDefaultRow = page.getByRole('listitem').filter({ hasText: '默认空间' });
  await reactivatedDefaultRow.getByText('当前空间').waitFor({ state: 'visible' });

  assert.equal(fs.existsSync(path.join(dataDir, 'profiles.db')), true);
  console.log(`[P2-002 real HTTP] isolatedData=${dataDir} javaPort=${javaPort} vitePort=${vitePort} create-reload-activate=PASS`);
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
