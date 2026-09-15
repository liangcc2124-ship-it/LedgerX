import assert from 'node:assert/strict';
import fs from 'node:fs';
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

function launchEnvironment() {
  const javaExecutable = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : (process.platform === 'win32' ? 'java.exe' : 'java');
  return {
    ...process.env,
    LEDGERX_TEST_JAVA_EXECUTABLE: javaExecutable,
    LEDGERX_TEST_JAVA_ARGS_JSON: JSON.stringify(['-cp', javaClassPath, 'com.ledgerx.http.HttpServerMain']),
    LEDGERX_TEST_DATA_DIR: dataDir,
    LEDGERX_TEST_WEB_ROOT: path.join(projectRoot, 'frontend', 'dist'),
    LEDGERX_TEST_USER_DATA_DIR: userDataDir,
  };
}

async function startDesktop() {
  electronApp = await electron.launch({
    cwd: electronRoot,
    args: [path.join(electronRoot, 'main.js')],
    env: launchEnvironment(),
  });
  const page = await electronApp.firstWindow();
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  return page;
}

test('P3-005 persists custom category and account across a real desktop restart', async () => {
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-p3-005-data-'));
  userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-p3-005-userdata-'));
  let page = await startDesktop();

  await page.getByRole('button', { name: '分类与账户' }).click();
  await expect(page.getByRole('heading', { name: '分类与账户' })).toBeFocused();
  await expect(page.getByText('现金储备')).toBeVisible();

  await page.getByRole('button', { name: '新增分类' }).click();
  await page.getByLabel('名称').fill('桌面验收分类');
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('分类已创建。')).toBeVisible();
  await expect(page.getByText('桌面验收分类')).toBeVisible();

  await page.getByRole('button', { name: '新增账户' }).click();
  await page.getByLabel('名称').last().fill('桌面验收账户');
  await page.getByLabel('期初余额').fill('12.30');
  await page.getByRole('button', { name: '保存' }).last().click();
  await expect(page.getByText('账户已创建。')).toBeVisible();
  await expect(page.getByText('桌面验收账户')).toBeVisible();

  await electronApp.close();
  electronApp = null;
  page = await startDesktop();
  await page.getByRole('button', { name: '分类与账户' }).click();
  await expect(page.getByText('桌面验收分类')).toBeVisible();
  await expect(page.getByText('桌面验收账户')).toBeVisible();

  assert.equal(fs.existsSync(path.join(dataDir, 'profiles.db')), true);
});

test.afterEach(async () => {
  if (electronApp) {
    await electronApp.close();
    electronApp = null;
  }
  if (dataDir && fs.existsSync(dataDir)) {
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
  if (userDataDir && fs.existsSync(userDataDir)) {
    fs.rmSync(userDataDir, { recursive: true, force: true });
  }
});
