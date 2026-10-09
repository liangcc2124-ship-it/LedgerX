import { expect, test } from './test-fixture.js';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ready = JSON.parse(fs.readFileSync(path.join(projectRoot, 'docs/contracts/api-v1/system/status-ready.json'), 'utf8'));

function readyWithMetrics() {
  return { ...ready, data: { ...ready.data, capabilities: [...ready.data.capabilities, 'metrics.read', 'metrics.write', 'formulas.validate', 'formulas.write', 'dashboard.read', 'dashboard.layout.write'] } };
}

function readyWithoutLayoutWrite() {
  return { ...ready, data: { ...ready.data, capabilities: [...ready.data.capabilities, 'dashboard.read'] } };
}

function dashboardPayload() {
  const items = [
    { widgetId: 'metric:income', x: 0, y: 0, w: 3, h: 2, minW: 3, minH: 2, maxW: 12, maxH: 6, persisted: true },
    { widgetId: 'metric:total-expense', x: 3, y: 0, w: 3, h: 2, minW: 3, minH: 2, maxW: 12, maxH: 6, persisted: true },
  ];
  const cards = items.map((item, index) => ({
    widgetId: item.widgetId,
    metric: { id: item.widgetId.slice(7), name: index ? '总支出' : '收入', description: '服务端计算', displayFormat: 'CURRENCY', precision: 2 },
    presentation: { hidden: false },
    value: { value: index ? '0.00' : '1200.00', displayFormat: 'CURRENCY', precision: 2, dataStatus: 'READY' },
    previousValue: { value: '0.00', displayFormat: 'CURRENCY', precision: 2, dataStatus: 'EMPTY' },
    change: { absolute: null, percent: null, dataStatus: 'EMPTY' },
    trend: [], breakdown: [],
  }));
  return { data: { period: { granularity: 'MONTH', anchor: '2026-09-15', start: '2026-09-01', endExclusive: '2026-10-01', label: '2026年9月', status: 'HISTORICAL' }, previousPeriod: { start: '2026-08-01' }, comparisonWindow: {}, layout: { viewKey: 'financial-overview', breakpoint: 'desktop', revision: 3, items }, cards }, meta: { dataRevision: 4 } };
}

test('dashboard reads four periods and saves a keyboard layout draft', async ({ page }) => {
  const consoleErrors = [];
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyWithMetrics()) }));
  const queries = [];
  await page.route('**/api/v1/dashboard?**', async (route) => {
    queries.push(new URL(route.request().url()).search);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dashboardPayload()) });
  });
  await page.route('**/api/v1/dashboard/layout', async (route) => {
    if (route.request().method() === 'PUT') {
      expect(route.request().headers()['if-match']).toBe('"3"');
      expect(route.request().headers()['idempotency-key']).toBeTruthy();
      const body = route.request().postDataJSON();
      expect(body.items).toHaveLength(2);
      await route.fulfill({ status: 200, headers: { ETag: '4' }, contentType: 'application/json', body: JSON.stringify({ data: { layout: { ...dashboardPayload().data.layout, revision: 4, items: body.items } }, meta: { dataRevision: 5 } }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { layout: dashboardPayload().data.layout }, meta: { dataRevision: 4 } }) });
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '财务总览' })).toBeVisible();
  await page.getByRole('group', { name: '总览时间粒度' }).getByRole('button', { name: '日' }).click();
  await expect.poll(() => queries.at(-1)).toContain('granularity=DAY');
  await page.getByRole('button', { name: '编辑布局' }).click();
  await page.getByRole('button', { name: '收入右移' }).click();
  await page.getByRole('button', { name: '保存布局' }).click();
  await expect(page.getByRole('status')).toContainText('布局已保存');
  expect(consoleErrors.filter((message) => message.includes('GridStack.resizeToContent'))).toEqual([]);
  await page.screenshot({ path: 'test-results/dashboard-overview.png', fullPage: true });
});

test('metrics page shows Chinese formula hints and sends a closed custom metric body', async ({ page }) => {
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyWithMetrics()) }));
  await page.route('**/api/v1/metrics?**', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [{ id: 'income', name: '收入', description: '系统', displayFormat: 'CURRENCY', precision: 2, isSystem: true, status: 'ACTIVE', visibility: { dashboardEnabled: true } }] }, meta: { dataRevision: 4 } }) }));
  await page.route('**/api/v1/metrics', async (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON();
      expect(body.id).toMatch(/^custom-[0-9a-f-]{36}$/);
      expect(body.formula.ast.root.kind).toBe('CONSTANT');
      expect(body.formula.tokens).toBeUndefined();
      expect(route.request().headers()['idempotency-key']).toBeTruthy();
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ data: { metric: { ...body, status: 'ACTIVE', revision: 0 } }, meta: { dataRevision: 5 } }) });
      return;
    }
    await route.fulfill({ status: 405, contentType: 'application/json', body: '{}' });
  });
  await page.route('**/api/v1/formulas/validate', async (route) => {
    expect(route.request().method()).toBe('POST');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { valid: true, formula: {}, dependencies: [], periodBehavior: 'PERIOD', period: {} }, meta: { dataRevision: 4 } }) });
  });
  await page.route('**/api/v1/formulas/preview', async (route) => {
    expect(route.request().method()).toBe('POST');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { value: { value: '0', dataStatus: 'READY', displayFormat: 'CURRENCY', precision: 2 }, components: [] }, meta: { dataRevision: 4 } }) });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '指标与公式' }).click();
  await expect(page.getByRole('heading', { name: '指标与公式' })).toBeFocused();
  const createButton = page.getByRole('button', { name: '新建自定义指标' });
  await createButton.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.locator('#metric-name')).toBeFocused();
  await expect.poll(() => page.evaluate(() => ({ inert: document.querySelector('#app')?.inert, overflow: document.body.style.overflow }))).toEqual({ inert: true, overflow: 'hidden' });
  const editorDialog = page.getByRole('dialog');
  const closeButton = editorDialog.getByRole('button', { name: '关闭' });
  const cancelButton = editorDialog.getByRole('button', { name: '取消' });
  await cancelButton.focus();
  await page.keyboard.press('Tab');
  await expect(closeButton).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(cancelButton).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(editorDialog).toHaveCount(0);
  await expect(createButton).toBeFocused();
  await createButton.click();
  await expect(page.getByText('安全除法（分母为零时返回不可计算）')).toBeVisible();
  await page.getByLabel('名称').fill('月度测试指标');
  await page.getByRole('button', { name: '校验公式' }).click();
  await expect(page.locator('.field-error')).toContainText('公式校验通过');
  await page.getByRole('button', { name: '预览结果' }).click();
  await expect(page.getByText(/预览：0/)).toBeVisible();
  await page.getByRole('button', { name: '保存指标' }).click();
  await expect(page.locator('.dashboard-message')).toContainText('指标已保存');
});

test('metric editor rehydrates recursive AST and loads history independently', async ({ page }) => {
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyWithMetrics()) }));
  await page.route('**/api/v1/metrics?**', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [{ id: 'custom-recursive', name: '递归指标', description: 'nested', displayFormat: 'NUMBER', precision: 2, isSystem: false, status: 'ACTIVE', revision: 7, visibility: { dashboardEnabled: true }, formula: { formulaId: 'formula-recursive' } }] }, meta: { dataRevision: 4 } }) }));
  await page.route('**/api/v1/metrics/custom-recursive', async (route) => route.fulfill({ status: 200, headers: { ETag: '"7"' }, contentType: 'application/json', body: JSON.stringify({ data: { metric: { id: 'custom-recursive', name: '递归指标', description: 'nested', displayFormat: 'NUMBER', precision: 2, isSystem: false, status: 'ACTIVE', revision: 7, visibility: { dashboardEnabled: true }, formula: { formulaId: 'formula-recursive', ast: { schemaVersion: 1, root: { kind: 'ADD', children: [{ kind: 'CONSTANT', value: '100' }, { kind: 'SAFE_DIVIDE', children: [{ kind: 'REF', referenceKind: 'METRIC', key: 'income' }, { kind: 'CONSTANT', value: '2' }] }] } } } } }, meta: { dataRevision: 4 } }) }));
  await page.route('**/api/v1/formulas/formula-recursive/versions?limit=50', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [{ id: 'version-2', version: 2, createdAt: '2026-09-21T00:00:00Z' }], page: { nextCursor: 'cursor-1', hasMore: true, limit: 50 } }, meta: { dataRevision: 4 } }) }));
  await page.route('**/api/v1/formulas/formula-recursive/versions?limit=50&cursor=cursor-1', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [{ id: 'version-1', version: 1, createdAt: '2026-09-20T00:00:00Z' }], page: { nextCursor: null, hasMore: false, limit: 50 } }, meta: { dataRevision: 4 } }) }));
  await page.route('**/api/v1/categories?**', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [] }, meta: { dataRevision: 4 } }) }));
  await page.route('**/api/v1/accounts?**', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [] }, meta: { dataRevision: 4 } }) }));

  await page.goto('/');
  await page.getByRole('button', { name: '指标与公式' }).click();
  await page.getByRole('button', { name: '编辑' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('group', { name: '公式节点 formula.ast.root.children[1]', exact: true })).toBeVisible();
  await expect(page.getByText('安全除法：2 个参数', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '公式版本历史' })).toBeVisible();
  await expect(page.getByText('版本 2')).toBeVisible();
  await page.getByRole('button', { name: '加载更早版本' }).click();
  await expect(page.getByText('版本 1')).toBeVisible();
});

test('hides layout writes without capability and navigates anchors from server periods', async ({ page }) => {
  const queries = [];
  await page.route('**/api/v1/system/status', async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyWithoutLayoutWrite()) }));
  await page.route('**/api/v1/dashboard?**', async (route) => { queries.push(new URL(route.request().url()).searchParams); await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dashboardPayload()) }); });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '财务总览' })).toBeVisible();
  await expect(page.getByRole('button', { name: '编辑布局' })).toHaveCount(0);
  const navigation = page.getByRole('group', { name: '期间导航' });
  await navigation.getByRole('button', { name: '上一期' }).click();
  await expect.poll(() => queries.at(-1)?.get('anchor')).toBe('2026-08-01');
  await navigation.getByRole('button', { name: '下一期' }).click();
  await expect.poll(() => queries.at(-1)?.get('anchor')).toBe('2026-10-01');
});
