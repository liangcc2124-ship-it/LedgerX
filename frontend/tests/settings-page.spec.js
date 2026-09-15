import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const readyFixture = JSON.parse(fs.readFileSync(path.join(projectRoot, 'docs/contracts/api-v1/system/status-ready.json'), 'utf8'));

async function openSettings(page) {
  const requests = [];
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyFixture) }));
  page.on('request', (request) => { if (request.url().includes('/api/v1/') && !request.url().includes('/system/status')) requests.push(request); });
  await page.goto('/');
  await page.getByRole('button', { name: '设置' }).click();
  await expect(page.getByRole('heading', { name: '设置与备份说明' })).toBeFocused();
  return requests;
}

test('shows only accurate manual backup guidance without settings API writes', async ({ page }) => {
  const requests = await openSettings(page);
  await expect(page.getByText('完全退出应用')).toBeVisible();
  await expect(page.getByText(/复制整个 LedgerX 数据目录/)).toBeVisible();
  await expect(page.getByText(/不要在应用运行中只复制 ledger.db/)).toBeVisible();
  await expect(page.getByText(/通知|自动备份|安全缓冲|自定义主题/)).toHaveCount(0);
  expect(requests).toHaveLength(0);
});

test('uses existing openDataFolder bridge and reports failures', async ({ page }) => {
  await page.addInitScript(() => { window.desktop = { openDataFolder: async () => ({ ok: false }) }; });
  await openSettings(page);
  await page.getByRole('button', { name: '查看数据目录' }).click();
  await expect(page.getByText('无法打开数据目录，请检查权限后重试。')).toBeVisible();
});

test('stays readable at 320px and hides bridge action when unavailable', async ({ page }) => {
  const requests = await openSettings(page);
  await page.setViewportSize({ width: 320, height: 720 });
  await expect(page.getByRole('button', { name: '查看数据目录' })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
  expect(requests).toHaveLength(0);
});
