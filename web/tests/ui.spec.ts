import { test, expect } from '@playwright/test';
import path from 'node:path';
import os from 'node:os';

async function createExpense(page: import('@playwright/test').Page, amount='88') {
  await page.getByRole('button', { name: /新增记录/ }).click();
  await page.getByRole('radio', { name: '日常支出' }).check();
  await page.getByLabel('金额 / 数值').fill(amount);
  await page.getByLabel('分类').selectOption('dining');
  await page.getByRole('button', { name: '保存记录' }).click();
}

test('v3 record form uses managed options, settlement choices and decimal text input', async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 860 });
  await page.goto('/');
  await page.getByRole('button', { name: /新增记录/ }).click();
  const amount = page.getByLabel('金额 / 数值');
  await expect(amount).toHaveAttribute('type', 'text');
  await amount.fill('123.45');
  await amount.dispatchEvent('wheel', { deltaY: -100 });
  await expect(amount).toHaveValue('123.45');
  await page.getByLabel('分类').selectOption('software');
  await page.getByText('已包含在期初余额', { exact: true }).click();
  await expect(page.getByRole('combobox', { name: '资金账户', exact: true })).toHaveCount(0);
  await page.getByRole('radio', { name: '周期成本' }).check();
  await expect(page.getByText('服务期间与分摊')).toBeVisible();
  await page.getByLabel('服务开始').fill('2026-09-15');
  await page.getByLabel('服务结束（不含当日）').fill('2026-10-15');
  await page.getByRole('button', { name: '保存记录' }).click();
  await expect(page.getByText('软件订阅')).toBeVisible();
});

test('v3 metric templates, structured formula builder and dashboard editing are reachable', async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 860 });
  await page.goto('/');
  await page.getByRole('button', { name: /指标中心/ }).click();
  await page.getByRole('button', { name: /新增指标/ }).click();
  await page.locator('.template-grid').getByRole('button', { name: /本期订阅成本/ }).click();
  await page.getByRole('button', { name: '添加模板指标' }).click();
  await expect(page.getByText('本期订阅成本', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: /新增指标/ }).click();
  await page.getByRole('button', { name: '从空白自定义' }).click();
  await page.getByLabel('指标名称').fill('创作工具成本');
  await page.getByRole('button', { name: '记录金额合计' }).click();
  await page.getByRole('button', { name: '试算' }).click();
  await expect(page.getByText(/保存时会交给本机公式引擎/)).toBeVisible();
  await page.getByRole('button', { name: '保存自定义指标' }).click();
  await page.getByRole('button', { name: /财务总览/ }).click();
  await page.getByRole('button', { name: '编辑布局' }).click();
  await page.getByRole('button', { name: '可用现金减宽' }).click();
  expect(await page.locator('.metric-card').first().evaluate(card => card.getBoundingClientRect().width)).toBeGreaterThan(200);
  await expect(page.getByText(/布局编辑已开启/)).toBeVisible();
  await page.getByRole('button', { name: '可用现金增宽' }).click();
  await page.getByRole('button', { name: '恢复默认布局' }).click();
  await expect(page.locator('.metric-card strong').first()).not.toHaveCSS('text-overflow', 'ellipsis');
});

test('v3 management lists offer batch actions and independent profiles', async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 860 });
  await page.goto('/');
  await createExpense(page);
  await page.getByRole('button', { name: /全部记录/ }).click();
  await page.getByLabel('选择当前筛选记录').check();
  await expect(page.getByText('已选择 1 条')).toBeVisible();
  await expect(page.getByLabel('批量资金账户')).toHaveCount(0);
  await page.getByRole('button', { name: '移入回收站' }).click();
  await expect(page.getByText('选择记录后可批量处理')).toBeVisible();
  await page.getByRole('button', { name: '设置' }).click();
  await page.getByRole('tab', { name: '分类' }).click();
  await page.getByLabel('分类名称').fill('工作室');
  await page.getByLabel('分类大类').selectOption('software');
  await page.getByRole('button', { name: '新增分类' }).click();
  await expect(page.getByText('软件订阅 · 用户分类')).toBeVisible();
  await page.getByLabel('选择 工作室').check();
  await page.getByRole('button', { name: '批量归档' }).click();
  await page.getByRole('tab', { name: '资金账户' }).click();
  await page.getByLabel('资金账户名称').fill('工作银行卡');
  await page.getByRole('button', { name: '新增账户' }).click();
  await expect(page.getByText('工作银行卡')).toBeVisible();
  await page.getByRole('tab', { name: '用户空间' }).click();
  await page.getByLabel('用户空间名称').fill('家庭账本');
  await page.getByRole('button', { name: '新建空间' }).click();
  await expect(page.getByRole('main').getByText('家庭账本', { exact: true })).toBeVisible();
});

test('responsive layouts keep actions and content in bounds', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  for (const viewport of [{ width: 320, height: 760 }, { width: 375, height: 844 }, { width: 768, height: 900 }, { width: 1024, height: 900 }, { width: 1440, height: 900 }]) {
    await page.setViewportSize(viewport); await page.goto('/');
    await expect(page.getByRole('heading', { name: '财务总览' })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    expect(overflow).toBeFalsy();
    const cardWidths = await page.locator('.metric-card').evaluateAll(cards => cards.map(card => card.getBoundingClientRect().width));
    expect(Math.min(...cardWidths)).toBeGreaterThan(180);
    const verticalText = await page.locator('.metric-card').evaluateAll(cards => cards.some(card => getComputedStyle(card).writingMode !== 'horizontal-tb'));
    expect(verticalText).toBeFalsy();
  }
  await page.setViewportSize({ width: 1024, height: 900 });
  await page.goto('/');
  await page.screenshot({ path: path.join(os.tmpdir(), 'ledgerx-v3-resized-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(os.tmpdir(), 'ledgerx-v3-mobile.png'), fullPage: true });
  expect(errors).toEqual([]);
});
