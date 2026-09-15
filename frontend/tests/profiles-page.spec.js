import { expect, test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contractsRoot = path.join(projectRoot, 'docs/contracts/api-v1');
const readyFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'system/status-ready.json'), 'utf8'));
const listFixture = JSON.parse(fs.readFileSync(path.join(contractsRoot, 'profiles/list-profiles.json'), 'utf8'));
const defaultProfile = listFixture.data.items[0];
const inactiveProfile = listFixture.data.items[1];

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

async function openProfiles(page, listResponse = listFixture) {
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyFixture) });
  });
  await page.route('**/api/v1/profiles?**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(listResponse) });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page.getByRole('heading', { name: '用户空间' })).toBeFocused();
  await expect(page.getByText(defaultProfile.name)).toBeVisible();
}

function operationSuccess(profile = inactiveProfile) {
  const activatedProfile = { ...profile, status: 'ACTIVE' };
  return {
    data: {
      profile: activatedProfile,
      status: 'COMPLETED',
    },
    meta: { dataRevision: 1, catalogRevision: 2 },
  };
}

test('loads profiles and creates a trimmed profile with UUID and idempotency key', async ({ page }) => {
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyFixture) });
  });
  const postRequests = [];
  let currentList = clone(listFixture);
  await page.route('**/api/v1/profiles?**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentList) });
  });
  await page.route('**/api/v1/profiles', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    postRequests.push(route.request());
    const body = JSON.parse(route.request().postData());
    const createdProfile = { ...inactiveProfile, id: body.id, name: body.name, status: 'ACTIVE', revision: 0 };
    currentList = {
      ...clone(listFixture),
      data: {
        ...clone(listFixture.data),
        items: [createdProfile, ...listFixture.data.items],
        activeProfileId: body.id,
      },
    };
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      headers: { Location: `/api/v1/profiles/${body.id}` },
      body: JSON.stringify({ data: { profile: createdProfile, previousActiveProfileId: defaultProfile.id }, meta: { dataRevision: 1, catalogRevision: 3 } }),
    });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page.getByText(defaultProfile.name)).toBeVisible();

  await page.getByLabel('新建用户空间').fill('  家庭账本  ');
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('创建用户空间成功。')).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: '家庭账本' }).getByText('当前空间')).toBeVisible();
  expect(postRequests).toHaveLength(1);
  const request = postRequests[0];
  const body = JSON.parse(request.postData());
  expect(body.name).toBe('家庭账本');
  expect(body.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  expect(request.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  expect(request.headers().authorization).toBeUndefined();
  expect(request.headers().origin).toBe('http://127.0.0.1:4173');
});

test('activates only an inactive profile with its revision and empty object body', async ({ page }) => {
  await openProfiles(page);
  let activationRequest;
  let currentList = clone(listFixture);
  await page.unroute('**/api/v1/profiles?**');
  await page.route('**/api/v1/profiles?**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentList) });
  });
  await page.route(`**/api/v1/profiles/${inactiveProfile.id}/activate`, async (route) => {
    activationRequest = route.request();
    const activated = { ...inactiveProfile, status: 'ACTIVE', revision: 3 };
    currentList = {
      ...clone(listFixture),
      data: {
        ...clone(listFixture.data),
        items: [{ ...defaultProfile, status: 'INACTIVE', revision: 2 }, activated],
        activeProfileId: inactiveProfile.id,
      },
    };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(operationSuccess(inactiveProfile)) });
  });
  await page.getByRole('button', { name: '切换' }).click();
  await expect(page.getByText('切换用户空间成功。')).toBeVisible();
  expect(JSON.parse(activationRequest.postData())).toEqual({});
  expect(activationRequest.headers()['if-match']).toBe('"2"');
  expect(activationRequest.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByRole('listitem').filter({ hasText: inactiveProfile.name }).getByText('当前空间')).toBeVisible();
});

test('requires inline archive confirmation and sends no request for first click or cancel', async ({ page }) => {
  await openProfiles(page);
  let deleteCount = 0;
  let currentList = clone(listFixture);
  await page.unroute('**/api/v1/profiles?**');
  await page.route('**/api/v1/profiles?**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentList) });
  });
  await page.route(`**/api/v1/profiles/${inactiveProfile.id}`, async (route) => {
    deleteCount += 1;
    currentList = {
      ...clone(listFixture),
      data: { ...clone(listFixture.data), items: [defaultProfile], activeProfileId: defaultProfile.id },
    };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { profile: { ...inactiveProfile, status: 'ARCHIVED' } }, meta: {} }) });
  });
  await page.getByRole('button', { name: '归档' }).click();
  expect(deleteCount).toBe(0);
  await expect(page.getByText('归档后不会删除账本或备份。')).toBeVisible();
  await page.getByRole('button', { name: '取消' }).click();
  expect(deleteCount).toBe(0);
  await page.getByRole('button', { name: '归档' }).click();
  await page.getByRole('button', { name: '确认归档' }).click();
  await expect(page.getByText('归档用户空间成功。')).toBeVisible();
  expect(deleteCount).toBe(1);
  await expect(page.getByRole('listitem').filter({ hasText: inactiveProfile.name })).toHaveCount(0);
});

test('shows name field errors without announcing false success', async ({ page }) => {
  await openProfiles(page);
  await page.route('**/api/v1/profiles', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    await route.fulfill({
      status: 400,
      contentType: 'application/json',
      body: JSON.stringify({ error: { code: 'VALIDATION_ERROR', message: '请求无效。', fieldErrors: { name: '名称已存在。' } } }),
    });
  });
  await page.getByLabel('新建用户空间').fill('重复空间');
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('名称已存在。')).toBeVisible();
  await expect(page.getByText('创建用户空间成功。')).toHaveCount(0);
  await expect(page.getByLabel('新建用户空间')).toHaveValue('重复空间');
});

test('validates trimmed Unicode name length locally before sending a mutation', async ({ page }) => {
  await openProfiles(page);
  let postCount = 0;
  await page.route('**/api/v1/profiles', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    postCount += 1;
    await route.fallback();
  });
  await page.getByLabel('新建用户空间').fill('a'.repeat(101));
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('用户空间名称不能超过 100 个字符。')).toBeVisible();
  expect(postCount).toBe(0);
});

test('maps authentication and recovery errors without performing a write after recovery lock', async ({ page }) => {
  await openProfiles(page);
  await page.route('**/api/v1/profiles', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ error: { code: 'AUTHENTICATION_REQUIRED', message: '需要重新启动。' } }) });
  });
  await page.getByLabel('新建用户空间').fill('认证失败空间');
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('登录状态已失效，请重新启动应用。')).toBeVisible();

  await page.unroute('**/api/v1/profiles');
  await page.route('**/api/v1/profiles', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    await route.fulfill({ status: 423, contentType: 'application/json', body: JSON.stringify({ error: { code: 'RECOVERY_REQUIRED', message: '正在恢复。' } }) });
  });
  await page.getByRole('button', { name: '重试' }).click();
  await expect(page.getByText('数据正在恢复，请稍后重试。')).toBeVisible();
  await expect(page.getByRole('button', { name: '创建' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '切换' })).toBeDisabled();
});

test('reports revision conflicts and offers refresh without silent overwrite', async ({ page }) => {
  await openProfiles(page);
  await page.route(`**/api/v1/profiles/${inactiveProfile.id}/activate`, async (route) => {
    await route.fulfill({ status: 428, contentType: 'application/json', body: JSON.stringify({ error: { code: 'PRECONDITION_REQUIRED', message: '版本已变化。' } }) });
  });
  await page.getByRole('button', { name: '切换' }).click();
  await expect(page.getByText('数据已变化，请刷新后重试。')).toBeVisible();
  await expect(page.getByRole('button', { name: '刷新' }).last()).toBeVisible();
  await expect(page.getByText('切换用户空间成功。')).toHaveCount(0);
});

test('shows a retryable network error for the profile read', async ({ page }) => {
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyFixture) });
  });
  await page.route('**/api/v1/profiles?**', async (route) => {
    await route.abort('failed');
  });
  await page.goto('/');
  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page.getByText('无法连接本地服务，请重试。')).toBeVisible();
  await expect(page.getByRole('button', { name: '重新加载' })).toBeVisible();
});

test('turns an unresponsive profile read into a bounded timeout state', async ({ page }) => {
  await page.clock.install();
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyFixture) });
  });
  await page.route('**/api/v1/profiles?**', async () => {
    await new Promise(() => {});
  });
  await page.goto('/');
  await page.getByRole('button', { name: '用户空间' }).click();
  await page.clock.fastForward(15000);
  await expect(page.getByText('读取用户空间超时，请重试。')).toBeVisible();
});

test('resets cursor on archive filter and appends only unique next page results', async ({ page }) => {
  const firstPage = JSON.parse(JSON.stringify(listFixture));
  firstPage.data.items = [defaultProfile];
  firstPage.data.page = { nextCursor: 'cursor-1', hasMore: true, limit: 50 };
  const secondPage = JSON.parse(JSON.stringify(listFixture));
  secondPage.data.items = [inactiveProfile, defaultProfile];
  secondPage.data.page = { nextCursor: null, hasMore: false, limit: 50 };
  const requests = [];
  await page.route('**/api/v1/system/status', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(readyFixture) });
  });
  await page.route('**/api/v1/profiles?**', async (route) => {
    requests.push(new URL(route.request().url()));
    const url = new URL(route.request().url());
    const response = url.searchParams.get('cursor') === 'cursor-1' ? secondPage : firstPage;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(response) });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page.getByText(defaultProfile.name)).toBeVisible();
  expect(requests[0].searchParams.get('includeArchived')).toBe('false');
  expect(requests[0].searchParams.get('cursor')).toBeNull();
  await page.getByRole('button', { name: '加载更多' }).click();
  await expect(page.getByText(inactiveProfile.name)).toBeVisible();
  expect(requests[1].searchParams.get('cursor')).toBe('cursor-1');
  await page.getByLabel('显示已归档').check();
  await expect.poll(() => requests.length).toBe(3);
  expect(requests[2].searchParams.get('includeArchived')).toBe('true');
  expect(requests[2].searchParams.get('cursor')).toBeNull();
  await expect(page.getByText(defaultProfile.name)).toHaveCount(1);
});

test('keeps timed-out operation pending and can query a completed result', async ({ page }) => {
  await openProfiles(page);
  let operationKey;
  await page.route('**/api/v1/profiles', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    operationKey = route.request().headers()['idempotency-key'];
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'SERVICE_UNAVAILABLE', message: '稍后重试。' } }) });
  });
  await page.route('**/api/v1/operations/**', async (route) => {
    expect(route.request().url()).toContain(`/api/v1/operations/${operationKey}`);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { status: 'COMPLETED' }, meta: {} }) });
  });
  await page.getByLabel('新建用户空间').fill('待确认空间');
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByText('稍后重试。')).toBeVisible();
  await page.getByRole('button', { name: '查询结果' }).click();
  await expect(page.getByText('创建用户空间成功。')).toBeVisible();
});

test('does not treat a missing operation record as a failed mutation', async ({ page }) => {
  await openProfiles(page);
  let operationKey;
  await page.route('**/api/v1/profiles', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    operationKey = route.request().headers()['idempotency-key'];
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'SERVICE_UNAVAILABLE', message: '稍后重试。' } }) });
  });
  await page.route('**/api/v1/operations/**', async (route) => {
    expect(route.request().url()).toContain(`/api/v1/operations/${operationKey}`);
    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: { code: 'NOT_FOUND', message: '没有找到。' } }) });
  });
  await page.getByLabel('新建用户空间').fill('未找到操作空间');
  await page.getByRole('button', { name: '创建' }).click();
  await expect(page.getByRole('button', { name: '查询结果' })).toBeVisible();
  await page.getByRole('button', { name: '查询结果' }).click();
  await expect(page.getByText('暂未找到操作结果，请稍后查询或重试。')).toBeVisible();
  await expect(page.getByText('创建用户空间成功。')).toHaveCount(0);
});

test('stays usable at 320px and 200% scale with keyboard focus', async ({ page }) => {
  await openProfiles(page);
  await page.setViewportSize({ width: 320, height: 720 });
  await page.reload();
  await page.getByRole('button', { name: '用户空间' }).click();
  await expect(page.getByText(defaultProfile.name)).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setPageScaleFactor', { pageScaleFactor: 2 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
  await page.getByLabel('新建用户空间').focus();
  await expect(page.getByLabel('新建用户空间')).toBeFocused();
});
