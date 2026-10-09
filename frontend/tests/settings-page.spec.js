import { expect, test } from './test-fixture.js';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const readyFixture = JSON.parse(fs.readFileSync(path.join(projectRoot, 'docs/contracts/api-v1/system/status-ready.json'), 'utf8'));
const backupCapabilities = ['backups.create', 'backups.list', 'backups.verify', 'backups.download'];
const backupId = '9fc1df09-f1d3-4c9b-97be-f592f65d0c29';
const backup = {
  id: backupId,
  profileId: '2cb615ab-5118-4479-836d-5c27c5beada7',
  createdAt: '2026-09-24T04:30:00Z',
  formatVersion: 3,
  schemaVersion: 6,
  applicationVersion: '0.1.0-SNAPSHOT',
  dataRevision: 42,
  counts: { records: 12, categories: 8, accounts: 3, metrics: 2 },
  sizeBytes: 98304,
  integrityStatus: 'VALID',
  encrypted: false,
  fileName: `${backupId}.ledgerx-backup`,
};

async function openSettings(page, { capabilities = backupCapabilities, listItems = [], requests = [] } = {}) {
  const status = structuredClone(readyFixture);
  status.data.capabilities = [...new Set([...status.data.capabilities, ...capabilities])];
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(status),
  }));
  await page.route('**/api/v1/backups**', async (route) => {
    const request = route.request();
    requests.push({ method: request.method(), url: new URL(request.url()).pathname + new URL(request.url()).search, headers: request.headers() });
    if (request.method() === 'GET' && request.url().includes('/verify')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { backup, verification: { status: 'VALID', verifiedAt: '2026-09-24T05:00:00Z' } }, meta: { dataRevision: 42 } }),
      });
    }
    if (request.method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { items: listItems, page: { nextCursor: null, hasMore: false, limit: 25 } }, meta: { dataRevision: 42 } }),
      });
    }
    return route.fulfill({ status: 201, headers: { Location: `/api/v1/backups/${backupId}/download` },
      contentType: 'application/json', body: JSON.stringify({ data: { backup }, meta: { dataRevision: 42 } }) });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '设置' }).click();
  await expect(page.getByRole('heading', { name: '设置与备份' })).toBeFocused();
}

test('creates a backup once with stable retry key, then lists and verifies the result', async ({ page }) => {
  const requests = [];
  let createCount = 0;
  const status = structuredClone(readyFixture);
  status.data.capabilities = [...new Set([...status.data.capabilities, ...backupCapabilities])];
  await page.route('**/api/v1/system/status', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(status) }));
  await page.route('**/api/v1/backups**', async (route) => {
    const request = route.request();
    requests.push({ method: request.method(), url: request.url(), headers: request.headers(), body: request.postDataJSON() });
    if (request.method() === 'POST') {
      createCount += 1;
      if (createCount === 1) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'SERVICE_UNAVAILABLE', message: '服务暂时不可用。' } }) });
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ data: { backup }, meta: { dataRevision: 42 } }) });
    }
    if (request.url().includes('/verify')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { backup, verification: { status: 'VALID', verifiedAt: '2026-09-24T05:00:00Z' } }, meta: { dataRevision: 42 } }) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: createCount > 1 ? [{ ...backup, integrityStatus: 'NOT_VERIFIED' }] : [], page: { nextCursor: null, hasMore: false, limit: 25 } }, meta: { dataRevision: 42 } }) });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '设置' }).click();
  await page.getByRole('button', { name: '立即备份' }).click();
  await expect(page.getByText('服务暂时不可用。')).toBeVisible();
  await page.getByRole('button', { name: '使用同一请求重试' }).click();
  await expect(page.getByText('备份已创建并通过完整性校验。').first()).toBeVisible();
  await expect(page.getByText('12 条记录')).toBeVisible();
  const createRequests = requests.filter((request) => request.method === 'POST');
  expect(createRequests).toHaveLength(2);
  expect(createRequests[0].headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  expect(createRequests[1].headers['idempotency-key']).toBe(createRequests[0].headers['idempotency-key']);
  expect(createRequests[0].headers['x-ledgerx-csrf']).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(createRequests[0].body).toEqual({});
  await page.getByRole('button', { name: '校验' }).click();
  await expect(page.getByText('完整性已验证')).toBeVisible();
  expect(requests.some((request) => request.url.includes(`/api/v1/backups/${backupId}/verify`))).toBeTruthy();
});

test('downloads a validated package using its generated filename', async ({ page }) => {
  const requests = [];
  await openSettings(page, { listItems: [backup], requests });
  await expect(page.getByText('12 条记录')).toBeVisible();
  await page.route(`**/api/v1/backups/${backupId}/download`, (route) => {
    requests.push({ method: route.request().method(), url: new URL(route.request().url()).pathname });
    return route.fulfill({ status: 200, contentType: 'application/vnd.ledgerx.backup+zip', body: 'PK\u0003\u0004sample-backup' });
  });
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe(backup.fileName);
  expect(requests.some((request) => request.url.endsWith(`/api/v1/backups/${backupId}/download`))).toBeTruthy();
});

test('loads older backup history by the opaque cursor without duplicating items', async ({ page }) => {
  const olderBackup = { ...backup, id: '391b34c3-677f-4f91-86e3-aacff30c3a1b', fileName: '391b34c3-677f-4f91-86e3-aacff30c3a1b.ledgerx-backup' };
  const requests = [];
  const status = structuredClone(readyFixture);
  status.data.capabilities = [...new Set([...status.data.capabilities, ...backupCapabilities])];
  await page.route('**/api/v1/system/status', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(status) }));
  await page.route('**/api/v1/backups**', async (route) => {
    const url = new URL(route.request().url());
    requests.push(url.searchParams.get('cursor'));
    const olderPage = url.searchParams.has('cursor');
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      data: {
        items: olderPage ? [backup, olderBackup] : [backup],
        page: olderPage ? { nextCursor: null, hasMore: false, limit: 25 }
          : { nextCursor: 'opaque-cursor', hasMore: true, limit: 25 },
      },
      meta: { dataRevision: 42 },
    }) });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '设置' }).click();
  await page.getByRole('button', { name: '加载更多' }).click();
  await expect(page.locator('.backup-item')).toHaveCount(2);
  expect(requests).toEqual([null, 'opaque-cursor']);
});

test('keeps an unreadable backup visible without pretending its metadata is trustworthy', async ({ page }) => {
  const invalidBackup = {
    ...backup,
    profileId: null,
    createdAt: null,
    schemaVersion: null,
    applicationVersion: null,
    counts: {},
    integrityStatus: 'INVALID',
  };
  await openSettings(page, { listItems: [invalidBackup] });
  const row = page.locator('.backup-item');
  await expect(row.getByText('校验失败')).toBeVisible();
  await expect(row.getByText('日期信息不可用')).toBeVisible();
  await expect(row.getByText('备份元数据不可读取')).toBeVisible();
  await expect(row.getByText(/条记录/)).toHaveCount(0);
});

test('explains backup readiness when ledger setup has not completed', async ({ page }) => {
  await openSettings(page, { capabilities: [] });
  await expect(page.getByText('账本初始化完成后，备份功能会在这里显示。')).toBeVisible();
  await expect(page.getByRole('button', { name: '立即备份' })).toHaveCount(0);
});

test('stays readable at 320px and clearly warns that backup files are not encrypted', async ({ page }) => {
  await openSettings(page, { listItems: [backup] });
  await expect(page.getByText(/未加密/)).toBeVisible();
  await page.setViewportSize({ width: 320, height: 720 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
});
