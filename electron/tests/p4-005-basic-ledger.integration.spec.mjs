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

function environment() {
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
  electronApp = await electron.launch({ cwd: electronRoot, args: [path.join(electronRoot, 'main.js')], env: environment() });
  const page = await electronApp.firstWindow();
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  return page;
}

test('P4-005 basic record survives balance edits, trash, restore and desktop restart', async () => {
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-p4-005-data-'));
  userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ledgerx-p4-005-userdata-'));
  let page = await startDesktop();

  await page.getByRole('button', { name: '分类与账户' }).click();
  await page.getByRole('button', { name: '新增分类' }).click();
  await page.getByLabel('名称').fill('记账验收分类');
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('记账验收分类')).toBeVisible();

  await page.getByRole('button', { name: '新增账户' }).click();
  await page.getByLabel('名称').last().fill('日常卡');
  await page.getByLabel('类型').last().selectOption('BANK');
  await page.getByLabel('期初余额').fill('1000.00');
  await page.getByRole('button', { name: '保存' }).last().click();
  await expect(page.getByText('账户已创建。')).toBeVisible();
  await page.getByRole('button', { name: '新增账户' }).click();
  await page.getByLabel('名称').last().fill('信用卡');
  await page.getByLabel('类型').last().selectOption('CREDIT');
  await page.getByLabel('期初余额').fill('0.00');
  await page.getByRole('button', { name: '保存' }).last().click();
  await expect(page.getByText('账户已创建。')).toBeVisible();

  await page.getByRole('button', { name: '收支记录' }).click();
  await expect(page.getByRole('heading', { name: '收支记录' })).toBeFocused();
  async function createRecord(type, amount, account, note) {
    await page.getByRole('button', { name: '新增记录' }).click();
    await page.getByLabel('类型').selectOption(type);
    await page.getByLabel('金额').fill(amount);
    await page.getByLabel('分类').selectOption({ label: '记账验收分类' });
    await page.getByLabel('结算账户').selectOption({ label: account });
    await page.getByLabel('备注').fill(note);
    await page.getByRole('button', { name: '保存' }).click();
    await expect(page.getByText('记录已保存。')).toBeVisible();
  }
  await createRecord('INCOME', '100.25', '日常卡', '银行收入');
  await createRecord('FIXED_COST', '20.10', '日常卡', '银行固定支出');
  await createRecord('VARIABLE_COST', '30.05', '日常卡', '银行弹性支出');
  await createRecord('VARIABLE_COST', '40.00', '信用卡', '信用卡支出');
  await expect(page.getByText('1050.10 CNY')).toBeVisible();
  await expect(page.getByRole('article').filter({ hasText: '信用卡' }).getByText('40.00 CNY', { exact: true })).toBeVisible();

  const fixed = page.locator('.record-list li').filter({ hasText: '银行固定支出' });
  await fixed.getByRole('button', { name: '编辑' }).click();
  await page.getByLabel('金额').fill('25.10');
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('记录已更新。')).toBeVisible();
  await expect(page.getByText('1045.10 CNY')).toBeVisible();

  const variable = page.locator('.record-list li').filter({ hasText: '银行弹性支出' });
  page.once('dialog', (dialog) => dialog.dismiss());
  await variable.getByRole('button', { name: '删除' }).click();
  await expect(variable).toBeVisible();
  page.once('dialog', (dialog) => dialog.accept());
  await variable.getByRole('button', { name: '删除' }).click();
  await expect(page.getByText('记录已移入回收站。')).toBeVisible();
  await page.getByRole('button', { name: '回收站' }).click();
  await expect(page.getByText('银行弹性支出')).toBeVisible();
  page.once('dialog', (dialog) => dialog.accept());
  await page.locator('.record-list li').filter({ hasText: '银行弹性支出' }).getByRole('button', { name: '恢复' }).click();
  await expect(page.getByText('记录已恢复。')).toBeVisible();
  await page.getByRole('button', { name: '当前记录' }).click();
  await expect(page.getByText('银行弹性支出')).toBeVisible();
  await expect(page.getByText('1045.10 CNY')).toBeVisible();

  await electronApp.close();
  electronApp = null;
  page = await startDesktop();
  await page.getByRole('button', { name: '收支记录' }).click();
  await expect(page.getByText('银行收入')).toBeVisible();
  await expect(page.getByText('银行固定支出')).toBeVisible();
  await expect(page.getByText('银行弹性支出')).toBeVisible();
  await expect(page.getByText('1045.10 CNY')).toBeVisible();
  await page.getByRole('button', { name: '用户空间' }).click();
  await page.getByLabel('新建用户空间').fill('隔离验收空间');
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('创建用户空间成功。')).toBeVisible();
  await page.getByRole('button', { name: '收支记录' }).click();
  await expect(page.getByText('还没有记录。')).toBeVisible();
  await page.getByRole('button', { name: '用户空间' }).click();
  const originalProfile = page.locator('.profile-card').filter({ hasNotText: '隔离验收空间' });
  await originalProfile.getByRole('button', { name: '切换' }).click();
  await expect(page.getByText('切换用户空间成功。')).toBeVisible();
  await page.getByRole('button', { name: '收支记录' }).click();
  await expect(page.getByText('银行收入')).toBeVisible();
  await expect(page.getByText('1045.10 CNY')).toBeVisible();
  assert.equal(fs.existsSync(path.join(dataDir, 'profiles.db')), true);
});

test.afterEach(async () => {
  if (electronApp) {
    await electronApp.close();
    electronApp = null;
  }
  if (dataDir && fs.existsSync(dataDir)) fs.rmSync(dataDir, { recursive: true, force: true });
  if (userDataDir && fs.existsSync(userDataDir)) fs.rmSync(userDataDir, { recursive: true, force: true });
});
