import { expect, test } from './test-fixture.js';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ready = JSON.parse(fs.readFileSync(path.join(root, 'docs/contracts/api-v1/system/status-ready.json'), 'utf8'));
const category = { id: 'c4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b', name: '日常', status: 'ACTIVE', recordTypes: [], canUseForRecords: true };
const account = { id: 'f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901', name: '现金', status: 'ACTIVE', balance: '0.00', currency: 'CNY', balanceSide: 'ASSET' };
function recordItem(id, revision = 0) {
  return { id, revision, recordType: 'VARIABLE_COST', amount: '1.00', currency: 'CNY', occurredOn: '2026-09-01', note: id, category: { id: category.id, name: category.name }, settlement: { account: { id: account.id, name: account.name }, settlementOn: '2026-09-01' } };
}

async function openRecords(page, requests) {
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ready) }));
  await page.route('**/api/v1/categories?limit=200', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [category] }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/accounts?limit=200', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [account] }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/records?status=ACTIVE&limit=50', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [] }, meta: { dataRevision: 0 } }) }));
  page.on('request', (request) => { if (request.url().includes('/api/v1/records') && request.method() === 'POST') requests.push(request); });
  await page.goto('/');
  await page.getByRole('button', { name: '收支记录' }).click();
  await expect(page.getByRole('heading', { name: '收支记录' })).toBeFocused();
}

test('loads server balances and submits a closed basic record draft', async ({ page }) => {
  const requests = [];
  await openRecords(page, requests);
  await expect(page.getByText('现金')).toBeVisible();
  await page.getByRole('button', { name: '新增记录' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('heading', { name: '新增记录' })).toBeVisible();
  await page.getByLabel('金额').fill('12.30');
  await page.getByLabel('分类').selectOption(category.id);
  await page.getByLabel('结算账户').selectOption(account.id);
  await page.route('**/api/v1/records', async (route) => {
    if (route.request().method() === 'POST') await route.fulfill({ status: 201, headers: { etag: '"0"' }, contentType: 'application/json', body: JSON.stringify({ data: { record: { id: 'd4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b' } }, meta: { dataRevision: 1 } }) });
  });
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('记录已保存。')).toBeVisible();
  expect(requests).toHaveLength(1);
  const body = JSON.parse(requests[0].postData());
  expect(body.amount).toBe('12.30');
  expect(body.currency).toBe('CNY');
  expect(body.settlement.mode).toBe('PAID_FROM_ACCOUNT');
  expect(body).not.toHaveProperty('balance');
});

test('opens the record form in a modal and closes it with Escape', async ({ page }) => {
  const requests = [];
  await openRecords(page, requests);

  const createButton = page.getByRole('button', { name: '新增记录' });
  await createButton.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByLabel('金额')).toBeFocused();

  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(createButton).toBeFocused();
  expect(requests).toHaveLength(0);
});

test('does not send delete when confirmation is cancelled', async ({ page }) => {
  const requests = [];
  await openRecords(page, requests);
  await page.evaluate(() => { window.confirm = () => false; });
  expect(await page.getByRole('button', { name: '删除' }).count()).toBe(0);
  expect(requests).toHaveLength(0);
});

test('loads the 51st record for current and trash cursors without duplicates', async ({ page }) => {
  const activeFirst = Array.from({ length: 50 }, (_, index) => recordItem(`active-${index}`));
  const trashFirst = Array.from({ length: 50 }, (_, index) => recordItem(`trash-${index}`));
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ready) }));
  await page.route('**/api/v1/categories?limit=200', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [category] }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/accounts?limit=200', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [account] }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/records?status=ACTIVE&limit=50', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: activeFirst, page: { nextCursor: 'active-next', hasMore: true } }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/records?status=ACTIVE&limit=50&cursor=active-next', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [recordItem('active-50')], page: { nextCursor: null, hasMore: false } }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/records?status=TRASHED&limit=50', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: trashFirst, page: { nextCursor: 'trash-next', hasMore: true } }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/records?status=TRASHED&limit=50&cursor=trash-next', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [recordItem('trash-50')], page: { nextCursor: null, hasMore: false } }, meta: { dataRevision: 0 } }) }));
  await page.goto('/');
  await page.getByRole('button', { name: '收支记录' }).click();
  await expect(page.getByText('active-49')).toBeVisible();
  await page.getByRole('button', { name: '加载更多记录' }).click();
  await expect(page.getByText('active-50')).toBeVisible();
  await page.getByRole('button', { name: '回收站' }).click();
  await expect(page.getByText('trash-49')).toBeVisible();
  await page.getByRole('button', { name: '加载更多记录' }).click();
  await expect(page.getByText('trash-50')).toBeVisible();
  await expect(page.getByText('trash-50')).toHaveCount(1);
});

test('switching to trash prevents a delayed active response from replacing the list', async ({ page }) => {
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ready) }));
  await page.route('**/api/v1/categories?limit=200', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [category] }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/accounts?limit=200', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [account] }, meta: { dataRevision: 0 } }) }));
  await page.route('**/api/v1/records?status=ACTIVE&limit=50', async (route) => { await new Promise((resolve) => setTimeout(resolve, 250)); await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [recordItem('active-late')], page: { nextCursor: null, hasMore: false } }, meta: { dataRevision: 0 } }) }); });
  await page.route('**/api/v1/records?status=TRASHED&limit=50', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [recordItem('trash-fast')], page: { nextCursor: null, hasMore: false } }, meta: { dataRevision: 0 } }) }));
  await page.goto('/');
  await page.getByRole('button', { name: '收支记录' }).click();
  await page.getByRole('button', { name: '回收站' }).click();
  await expect(page.getByText('trash-fast')).toBeVisible();
  await page.waitForTimeout(300);
  await expect(page.getByText('active-late')).toHaveCount(0);
});

test('keeps the record form open on a validation or revision conflict', async ({ page }) => {
  const requests = [];
  await openRecords(page, requests);
  await page.getByRole('button', { name: '新增记录' }).click();
  await page.getByLabel('金额').fill('12.30');
  await page.getByLabel('分类').selectOption(category.id);
  await page.getByLabel('结算账户').selectOption(account.id);
  await page.route('**/api/v1/records', async (route) => route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: { code: 'REVISION_CONFLICT', message: '记录已被其他操作更新。', fieldErrors: { amount: '请刷新后重试。' } } }) }));
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByText('记录已被其他操作更新。')).toBeVisible();
  await expect(page.getByLabel('金额')).toHaveAttribute('aria-invalid', 'true');
  expect(requests).toHaveLength(1);
});
