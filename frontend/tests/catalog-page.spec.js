import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ready = JSON.parse(fs.readFileSync(path.join(root, 'docs/contracts/api-v1/system/status-ready.json'), 'utf8'));
const category = { id: 'c4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b', name: '日常', parentId: null, status: 'ACTIVE', isSystem: false, isLegacyCustom: false, canUseForRecords: true, recordTypes: [], sortOrder: 0, defaultRecognitionMethod: 'IMMEDIATE', recommendedDepreciationMethod: null, revision: 0 };
const targetCategory = { ...category, id: 'c6b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b', name: '目标分类', sortOrder: 1 };
const account = { id: 'f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901', name: '现金储备', kind: 'CASH', balanceSide: 'ASSET', openingOn: '2026-01-01', openingBalance: '0.00', balance: '0.00', currency: 'CNY', includeInAvailableCash: true, isSystem: true, status: 'ACTIVE', revision: 0 };

test('catalog page reads both resources and sends a minimal category create body', async ({ page }) => {
  const requests = [];
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ready) }));
  await page.route('**/api/v1/categories**', async (route) => {
    if (route.request().method() === 'POST') {
      requests.push(route.request());
      await route.fulfill({ status: 201, headers: { etag: '"0"' }, contentType: 'application/json', body: JSON.stringify({ data: { category }, meta: { dataRevision: 1 } }) });
      return;
    }
    await route.fulfill({ status: 200, headers: { etag: '"0"' }, contentType: 'application/json', body: JSON.stringify({ data: { items: [category], page: { nextCursor: null, hasMore: false, limit: 200 } }, meta: { dataRevision: 0 } }) });
  });
  await page.route('**/api/v1/accounts**', async (route) => route.fulfill({ status: 200, headers: { etag: '"0"' }, contentType: 'application/json', body: JSON.stringify({ data: { items: [account], page: { nextCursor: null, hasMore: false, limit: 200 } }, meta: { dataRevision: 0 } }) }));
  await page.goto('/');
  await page.getByRole('button', { name: '分类与账户' }).click();
  await expect(page.getByRole('heading', { name: '分类与账户' })).toBeFocused();
  await expect(page.getByText('现金储备')).toBeVisible();
  await page.getByRole('button', { name: '新增分类' }).click();
  await page.getByLabel('名称').fill('测试分类');
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('分类已创建。')).toBeVisible();
  expect(requests).toHaveLength(1);
  const body = JSON.parse(requests[0].postData());
  expect(body.name).toBe('测试分类');
  expect(body.parentId).toBeNull();
  expect(body).not.toHaveProperty('sortOrder');
  expect(body).not.toHaveProperty('recordTypes');
});

test('catalog page requires merge confirmation and sends optimistic concurrency fields', async ({ page }) => {
  const requests = [];
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ready) }));
  await page.route('**/api/v1/categories**', async (route) => {
    if (route.request().method() === 'POST' && route.request().url().includes('/merge')) {
      requests.push(route.request());
      await route.fulfill({ status: 200, headers: { etag: '"1"' }, contentType: 'application/json', body: JSON.stringify({ data: { targetId: targetCategory.id, mergedIds: [category.id], updatedRecordCount: 2 }, meta: { dataRevision: 1 } }) });
      return;
    }
    await route.fulfill({ status: 200, headers: { etag: '"0"' }, contentType: 'application/json', body: JSON.stringify({ data: { items: [category, targetCategory], page: { nextCursor: null, hasMore: false, limit: 200 } }, meta: { dataRevision: 0 } }) });
  });
  await page.route('**/api/v1/accounts**', async (route) => route.fulfill({ status: 200, headers: { etag: '"0"' }, contentType: 'application/json', body: JSON.stringify({ data: { items: [account], page: { nextCursor: null, hasMore: false, limit: 200 } }, meta: { dataRevision: 0 } }) }));
  await page.goto('/');
  await page.getByRole('button', { name: '分类与账户' }).click();
  await page.getByRole('button', { name: '合并来源' }).click();
  await page.getByLabel('来源分类').selectOption(category.id);
  await page.locator('select').nth(1).selectOption(targetCategory.id);
  await page.getByRole('button', { name: '继续' }).click();
  await expect(page.getByText('确认后来源分类会被归档。')).toBeVisible();
  await page.getByRole('button', { name: '确认合并' }).click();
  await expect(page.getByText('分类已合并。')).toBeVisible();
  expect(requests).toHaveLength(1);
  expect(requests[0].headers()['if-match']).toBe('"0"');
  expect(JSON.parse(requests[0].postData())).toEqual({ sources: [{ id: category.id, expectedRevision: 0 }] });
});
