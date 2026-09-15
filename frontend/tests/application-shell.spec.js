import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contractsRoot = path.join(projectRoot, 'docs/contracts/api-v1/system');
const readyFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'status-ready.json'), 'utf8'));
const startingFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'status-starting.json'), 'utf8'));
const profilesFixture = JSON.parse(fs.readFileSync(path.join(projectRoot, 'docs/contracts/api-v1/profiles/list-profiles.json'), 'utf8'));

async function routeStatus(page, fixture) {
  const businessRequests = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/') && !request.url().includes('/api/v1/system/status')) {
      businessRequests.push(request.url());
    }
  });
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fixture) });
  });
  return businessRequests;
}

test('READY exposes keyboard-accessible in-memory navigation and mounted pages', async ({ page }) => {
  const businessRequests = await routeStatus(page, readyFixture);
  await page.route('**/api/v1/profiles?**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(profilesFixture) });
  });
  await page.goto('/');

  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
  await expect(page.getByRole('button', { name: '首页' })).toHaveAttribute('aria-current', 'page');

  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page.getByRole('heading', { name: '用户空间' })).toBeFocused();
  await expect(page.getByText('默认空间')).toBeVisible();
  await expect(page.getByRole('button', { name: '用户空间' })).toHaveAttribute('aria-current', 'page');

  await page.getByRole('button', { name: '设置' }).click();
  await expect(page.getByRole('heading', { name: '设置' })).toBeFocused();
  await expect(page.getByText('手工备份')).toBeVisible();
  expect(businessRequests).toHaveLength(1);
  expect(businessRequests[0]).toContain('/api/v1/profiles?includeArchived=false&limit=50');

  await page.reload();
  await expect(page.getByRole('button', { name: '首页' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('heading', { name: '首页' })).toBeVisible();
});

test('non-ready status keeps business navigation unavailable', async ({ page }) => {
  await routeStatus(page, startingFixture);
  await page.goto('/');

  await expect(page.getByText('正在初始化本地数据…')).toBeVisible();
  await expect(page.getByRole('navigation', { name: '主导航' })).toHaveCount(0);
});

test('shell reflows at 320px and keeps navigation buttons reachable', async ({ page }) => {
  await routeStatus(page, readyFixture);
  await page.setViewportSize({ width: 320, height: 720 });
  await page.goto('/');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setPageScaleFactor', { pageScaleFactor: 2 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
  await page.getByRole('button', { name: '用户空间' }).focus();
  await expect(page.getByRole('button', { name: '用户空间' })).toBeFocused();
});

test('shell has no accidental overflow at supported viewport widths', async ({ page }) => {
  await routeStatus(page, readyFixture);
  for (const width of [320, 375, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 720 });
    await page.goto('/');
    await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
  }
});
