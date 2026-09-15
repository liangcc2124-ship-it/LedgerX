import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contractsRoot = path.join(projectRoot, 'docs/contracts/api-v1/system');
const startingFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'status-starting.json'), 'utf8'));
const readyFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'status-ready.json'), 'utf8'));
const recoveryFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'status-recovery-required.json'), 'utf8'));
const requestIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function errorFixture(code, message, retryable = false) {
  return {
    error: {
      code,
      message,
      requestId: 'b4d2f8b2-9a2b-4a4e-8d6c-7d5b6c4e3f21',
      fieldErrors: {},
      retryable,
      recoverySuggestion: '',
      details: {},
    },
  };
}

async function routeStatus(page, behavior) {
  const requests = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/system/status')) {
      requests.push({ url: request.url(), headers: request.headers() });
    }
  });
  await page.route('**/api/v1/system/status', async (route) => {
    if (behavior === 'network') {
      await route.abort('failed');
      return;
    }
    if (behavior === 'malformed') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{' });
      return;
    }
    if (behavior === 'unauthorized') {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify(errorFixture('AUTHENTICATION_REQUIRED', '需要有效的本地会话认证。')),
      });
      return;
    }
    if (behavior === 'unavailable') {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify(errorFixture('SERVICE_UNAVAILABLE', '本地服务暂时不可用。', true)),
      });
      return;
    }
    const fixture = behavior === 'recovery' ? recoveryFixture : behavior === 'ready' ? readyFixture : startingFixture;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'LedgerX-Api-Version': '1.0' },
      body: JSON.stringify(fixture),
    });
  });
  return requests;
}

test('READY is the only status rendered as connected', async ({ page }) => {
  const requests = await routeStatus(page, 'ready');
  await page.goto('/');

  await expect(page.getByText('本地服务已连接')).toBeVisible();
  await expect(page.getByText('版本 0.1.0-SNAPSHOT · 状态 READY')).toBeVisible();
  await page.waitForTimeout(700);
  expect(requests).toHaveLength(1);
  expect(requests[0].url).toContain('/api/v1/system/status');
  expect(requests[0].headers['x-request-id']).toMatch(requestIdPattern);
  expect(requests[0].headers.authorization).toBeUndefined();
});

test('STARTING renders initialization, polls sequentially, then stops at READY', async ({ page }) => {
  let attempts = 0;
  let inFlight = 0;
  let maxInFlight = 0;
  await page.route('**/api/v1/system/status', async (route) => {
    attempts += 1;
    inFlight += 1;
    maxInFlight = Math.max(maxInFlight, inFlight);
    try {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(attempts === 1 ? startingFixture : readyFixture),
      });
    } finally {
      inFlight -= 1;
    }
  });
  await page.goto('/');

  await expect(page.getByText('正在初始化本地数据…')).toBeVisible();
  await expect(page.getByText('本地服务已连接')).toBeVisible({ timeout: 3000 });
  expect(attempts).toBe(2);
  expect(maxInFlight).toBe(1);
  await page.waitForTimeout(700);
  expect(attempts).toBe(2);
});

test('continuous STARTING stops at the 15 second deadline without concurrent polling', async ({ page }) => {
  let attempts = 0;
  let inFlight = 0;
  let maxInFlight = 0;
  await page.route('**/api/v1/system/status', async (route) => {
    attempts += 1;
    inFlight += 1;
    maxInFlight = Math.max(maxInFlight, inFlight);
    try {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(startingFixture) });
    } finally {
      inFlight -= 1;
    }
  });
  await page.goto('/');

  await expect(page.getByText('正在初始化本地数据…')).toBeVisible();
  await expect(page.getByText('初始化本地数据超时', { exact: true })).toBeVisible({ timeout: 17_000 });
  await expect(page.getByText('本地服务已连接')).toHaveCount(0);
  expect(attempts).toBeGreaterThanOrEqual(29);
  expect(maxInFlight).toBe(1);
  const attemptsAtDeadline = attempts;
  await page.waitForTimeout(700);
  expect(attempts).toBe(attemptsAtDeadline);
});

test('RECOVERY_REQUIRED shows recovery guidance and only manual refresh', async ({ page }) => {
  const requests = await routeStatus(page, 'recovery');
  await page.goto('/');

  await expect(page.getByText('本地数据需要恢复')).toBeVisible();
  await expect(page.getByText('本地服务已连接')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '刷新状态' })).toBeVisible();
  await page.waitForTimeout(1000);
  expect(requests).toHaveLength(1);
  await page.getByRole('button', { name: '刷新状态' }).click();
  await expect(page.getByText('本地数据需要恢复')).toBeVisible();
  expect(requests).toHaveLength(2);
  await page.waitForTimeout(700);
  expect(requests).toHaveLength(2);
  await expect(page.getByRole('button', { name: '打开日志目录' })).toHaveCount(0);
});

test('recovery folder bridge failures show generic feedback without requiring a bridge', async ({ page }) => {
  await page.addInitScript(() => {
    window.desktop = {
      openLogsFolder: async () => ({ ok: false, error: { code: 'OPEN_FOLDER_FAILED' } }),
      openDataFolder: async () => ({ ok: false, error: { code: 'OPEN_FOLDER_FAILED' } }),
    };
  });
  await routeStatus(page, 'recovery');
  await page.goto('/');

  await expect(page.getByRole('button', { name: '打开日志目录' })).toBeVisible();
  await page.getByRole('button', { name: '打开日志目录' }).click();
  await expect(page.getByText('无法打开目录，请检查权限后重试。')).toBeVisible();
});

test('retries latest status round and ignores an aborted stale response', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/v1/system/status', async (route) => {
    attempts += 1;
    if (attempts === 1) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(startingFixture) });
      return;
    }
    if (attempts === 2) {
      await new Promise((resolve) => setTimeout(resolve, 1000));
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(startingFixture) }).catch(() => {});
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyFixture) });
  });
  await page.goto('/');
  await expect(page.getByText('正在初始化本地数据…')).toBeVisible();
  await page.waitForTimeout(650);
  await page.getByRole('button', { name: '重试连接' }).click();
  await expect(page.getByText('本地服务已连接')).toBeVisible({ timeout: 3000 });
  expect(attempts).toBeGreaterThanOrEqual(3);
  await page.waitForTimeout(700);
  await expect(page.getByText('本地服务已连接')).toBeVisible();
});

test('shows an authentication error without treating 401 as success', async ({ page }) => {
  await routeStatus(page, 'unauthorized');
  await page.goto('/');

  await expect(page.getByText('桌面会话无效')).toBeVisible();
  await expect(page.getByText('本地服务已连接')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '重试连接' })).toHaveCount(0);
});

test('shows a retryable server error and recovers on retry', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/v1/system/status', async (route) => {
    attempts += 1;
    await route.fulfill({
      status: attempts === 1 ? 503 : 200,
      contentType: 'application/json',
      body: JSON.stringify(attempts === 1
        ? errorFixture('SERVICE_UNAVAILABLE', '本地服务暂时不可用。', true)
        : readyFixture),
    });
  });
  await page.goto('/');
  await expect(page.getByText('本地服务暂时不可用', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '重试连接' }).click();
  await expect(page.getByText('本地服务已连接')).toBeVisible();
  expect(attempts).toBe(2);
});

test('rejects malformed, unknown-state, invalid-ready and wrong-major responses', async ({ page }) => {
  const invalidPayloads = [
    '{',
    JSON.stringify({ ...startingFixture, data: { ...startingFixture.data, state: 'UNKNOWN' } }),
    JSON.stringify({ ...readyFixture, data: { ...readyFixture.data, activeProfileId: null } }),
    JSON.stringify({ ...readyFixture, data: { ...readyFixture.data, apiVersion: '2.0' } }),
  ];
  for (const body of invalidPayloads) {
    await page.route('**/api/v1/system/status', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body });
    });
    await page.goto('/');
    await expect(page.getByText('本地服务暂时不可用')).toBeVisible();
    await expect(page.getByText('本地服务已连接')).toHaveCount(0);
    await page.unroute('**/api/v1/system/status');
  }
});

test('shows a network error as a retryable error', async ({ page }) => {
  await routeStatus(page, 'network');
  await page.goto('/');

  await expect(page.getByText('本地服务暂时不可用')).toBeVisible();
  await expect(page.getByText('无法连接本地服务，请重试。')).toBeVisible();
});

test('reflows at 320px and keeps retry keyboard reachable at 200 percent zoom', async ({ page }) => {
  await routeStatus(page, 'network');
  await page.setViewportSize({ width: 320, height: 720 });
  await page.goto('/');
  await expect(page.getByRole('button', { name: '重试连接' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setPageScaleFactor', { pageScaleFactor: 2 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: '重试连接' })).toBeFocused();
});
