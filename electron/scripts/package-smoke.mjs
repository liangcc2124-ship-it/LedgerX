import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { _electron as electron, expect } from '@playwright/test';

const electronRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packagedExecutable = path.join(electronRoot, 'out', 'LedgerX-win32-x64', 'LedgerX.exe');
const javaExecutable = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : (process.platform === 'win32' ? 'java.exe' : 'java');

if (!fs.statSync(packagedExecutable, { throwIfNoEntry: false })?.isFile()) {
  throw new Error(`packaged executable is missing: ${packagedExecutable}`);
}

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-package-smoke-data-'));
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-package-smoke-userdata-'));
let app;
try {
  app = await electron.launch({
    executablePath: packagedExecutable,
    args: [],
    env: {
      ...process.env,
      LEDGERX_TEST_JAVA_EXECUTABLE: javaExecutable,
      LEDGERX_TEST_DATA_DIR: dataDir,
      LEDGERX_TEST_USER_DATA_DIR: userDataDir,
      ELECTRON_DISABLE_GPU: '1',
      ELECTRON_DISABLE_SANDBOX: '1',
    },
  });
  const page = await app.firstWindow();
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  await page.getByRole('button', { name: '收支记录' }).click();
  await expect(page.getByRole('heading', { name: '收支记录' })).toBeFocused();
  assert.equal(fs.existsSync(path.join(dataDir, 'profiles.db')), true);
  console.log('Packaged Electron smoke passed: startup, Java backend, Vue route, isolated SQLite root.');
} finally {
  if (app) {
    await app.close();
  }
  fs.rmSync(dataDir, { recursive: true, force: true });
  fs.rmSync(userDataDir, { recursive: true, force: true });
}
