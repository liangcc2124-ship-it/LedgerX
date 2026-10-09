import { expect, test } from './test-fixture.js';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contractsRoot = path.join(projectRoot, 'docs/contracts/api-v1/system');
const readyFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'status-ready.json'), 'utf8'));
const startingFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'status-starting.json'), 'utf8'));
const pendingFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'status-pending.json'), 'utf8'));
const profilesFixture = JSON.parse(fs.readFileSync(path.join(projectRoot, 'docs/contracts/api-v1/profiles/list-profiles.json'), 'utf8'));

async function routeStatus(page, fixture) {
  const businessRequests = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/') && !request.url().includes('/api/v1/system/status')
        && !request.url().includes('/api/v1/system/session')) {
      businessRequests.push(request.url());
    }
  });
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fixture) });
  });
  return businessRequests;
}

test('READY exposes keyboard-accessible in-memory navigation and mounted pages', async ({ page }) => {
  const readyWithBackup = structuredClone(readyFixture);
  readyWithBackup.data.capabilities = [...new Set([...readyWithBackup.data.capabilities,
    'backups.create', 'backups.list', 'backups.verify', 'backups.download'])];
  const businessRequests = await routeStatus(page, readyWithBackup);
  await page.route('**/api/v1/profiles?**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(profilesFixture) });
  });
  await page.route('**/api/v1/backups**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      data: { items: [], page: { nextCursor: null, hasMore: false, limit: 25 } }, meta: { dataRevision: 0 },
    }) });
  });
  await page.goto('/');

  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
  await expect(page.getByRole('button', { name: '首页' })).toHaveAttribute('aria-current', 'page');

  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page.getByRole('heading', { name: '用户空间' })).toBeFocused();
  await expect(page.getByText('默认空间')).toBeVisible();
  await expect(page.getByRole('button', { name: '用户空间' })).toHaveAttribute('aria-current', 'page');

  await page.getByRole('button', { name: '设置' }).click();
  await expect(page.getByRole('heading', { name: '设置与备份' })).toBeFocused();
  await expect(page.getByText('还没有备份。')).toBeVisible();
  expect(businessRequests.some((url) => url.includes('/api/v1/profiles?includeArchived=false&limit=50'))).toBeTruthy();
  expect(businessRequests.some((url) => url.includes('/api/v1/backups?limit=25'))).toBeTruthy();

  await page.reload();
  await expect(page.getByRole('button', { name: '设置' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('heading', { name: '设置与备份' })).toBeVisible();
});

test('non-ready status keeps business navigation unavailable', async ({ page }) => {
  await routeStatus(page, startingFixture);
  await page.goto('/');

  await expect(page.getByText('正在初始化本地数据…')).toBeVisible();
  await expect(page.getByRole('navigation', { name: '主导航' })).toHaveCount(0);
});

test('pending ledger opens a first-run flow and only enables navigation after initialization', async ({ page }) => {
  let initialized = false;
  let submittedBody;
  let submittedKey;
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(initialized ? readyFixture : pendingFixture),
    });
  });
  await page.route('**/api/v1/ledger-initialization', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        data: {
          setupState: 'PENDING', today: '2026-09-23', ledgerStartOn: null, suggestedLedgerStartOn: null,
          defaultAccount: {
            id: 'f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901', name: '现金储备', openingOn: '2026-09-23',
            openingBalance: '0.00', earliestSettlementOn: null, status: 'ACTIVE',
          },
          accountsNeedingOpeningDateReview: [],
        },
        meta: { dataRevision: 0 },
      }) });
      return;
    }
    submittedBody = route.request().postDataJSON();
    submittedKey = route.request().headers()['idempotency-key'];
    initialized = true;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      data: { setupState: 'COMPLETED', ledgerStartOn: submittedBody.ledgerStartOn },
      meta: { dataRevision: 1 },
    }) });
  });

  await page.goto('/#records');
  await expect(page.getByRole('heading', { name: '开始使用 LedgerX' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: '主导航' })).toHaveCount(0);
  await expect(page).toHaveURL(/#setup$/);
  await page.getByLabel('账本起始日').fill('2026-08-01');
  await page.getByLabel('账户名称').fill('日常现金');
  await page.getByLabel('该账户在开户日的期初余额（元）').fill('2500.00');
  await page.getByRole('button', { name: '保存并开始记账' }).click();

  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
  await expect(page.getByRole('button', { name: '首页' })).toHaveAttribute('aria-current', 'page');
  expect(submittedBody).toEqual({
    ledgerStartOn: '2026-08-01',
    defaultAccountOpeningOn: '2026-08-01',
    openingBalance: '2500.00',
    accountName: '日常现金',
    confirmExistingData: false,
    accountOpeningDates: [],
  });
  expect(submittedKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

test('review flow preserves historical values until the user explicitly corrects dates', async ({ page }) => {
  let initialized = false;
  let submittedBody;
  const reviewStatus = { ...pendingFixture, data: { ...pendingFixture.data, setupState: 'REVIEW_REQUIRED' } };
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(initialized ? readyFixture : reviewStatus),
    });
  });
  await page.route('**/api/v1/ledger-initialization', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        data: {
          setupState: 'REVIEW_REQUIRED', today: '2026-09-23', ledgerStartOn: null,
          suggestedLedgerStartOn: '2025-01-01',
          defaultAccount: {
            id: 'f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901', name: '现金储备', openingOn: '2025-03-01',
            openingBalance: '125.00', earliestSettlementOn: '2025-02-01', status: 'ACTIVE',
          },
          accountsNeedingOpeningDateReview: [{
            accountId: 'a4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b', name: '历史银行卡',
            openingOn: '2025-03-01', earliestSettlementOn: '2025-02-01', status: 'ACTIVE',
          }],
        },
        meta: { dataRevision: 4 },
      }) });
      return;
    }
    submittedBody = route.request().postDataJSON();
    initialized = true;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      data: { setupState: 'COMPLETED', ledgerStartOn: submittedBody.ledgerStartOn },
      meta: { dataRevision: 5 },
    }) });
  });

  await page.goto('/#records');
  await expect(page.getByRole('heading', { name: '确认账本起始信息' })).toBeVisible();
  await expect(page.getByText('原开户日晚于已有结算日，请明确调整到最早结算日或更早。')).toBeVisible();
  const defaultOpening = page.getByLabel('默认账户开户日');
  const historicalOpening = page.getByLabel('历史银行卡的新开户日');
  await expect(defaultOpening).toHaveValue('2025-03-01');
  await expect(historicalOpening).toHaveValue('2025-03-01');

  await page.getByLabel('账本起始日').fill('2025-01-01');
  await defaultOpening.fill('2025-01-01');
  await historicalOpening.fill('2025-01-15');
  await page.getByRole('button', { name: '确认并启用账本' }).click();

  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
  expect(submittedBody).toEqual({
    ledgerStartOn: '2025-01-01',
    defaultAccountOpeningOn: '2025-01-01',
    openingBalance: '125.00',
    accountName: '现金储备',
    confirmExistingData: true,
    accountOpeningDates: [{
      accountId: 'a4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b', openingOn: '2025-01-15',
    }],
  });
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

test('restores whitelisted hash pages and replaces unsupported deep links', async ({ page }) => {
  await routeStatus(page, readyFixture);
  await page.goto('/#metrics');
  await expect(page.locator('#home-page-title')).toBeVisible();
  await expect(page.getByRole('status')).toContainText('当前空间不支持该页面');
  await expect(page).toHaveURL(/#home$/);
  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page).toHaveURL(/#profiles$/);
  await page.goBack();
  await expect(page.getByRole('button', { name: '首页' })).toHaveAttribute('aria-current', 'page');
});
