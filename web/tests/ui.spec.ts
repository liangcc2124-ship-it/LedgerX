import { test, expect } from '@playwright/test';
import path from 'node:path';
import os from 'node:os';

test('core ledger workflow and desktop render', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
  await page.setViewportSize({ width: 1360, height: 860 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '财务总览' })).toBeVisible();

  await page.getByRole('button', { name: /新增记录/ }).click();
  await page.getByLabel('金额 / 数值').fill('5000');
  await page.getByLabel('分类').fill('父母生活费');
  await page.getByRole('button', { name: '保存记录' }).click();
  await expect(page.getByText('¥ 5000.00').first()).toBeVisible();

  await page.getByRole('button', { name: /指标中心/ }).click();
  await page.getByRole('button', { name: /新增指标/ }).click();
  await page.getByLabel('指标名称').fill('安全垫');
  await page.getByLabel('说明').fill('意外情况预留资金');
  await page.getByRole('button', { name: '创建指标' }).click();
  await expect(page.getByText('安全垫', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: /财务总览/ }).click();
  await page.getByText('安全垫', { exact: true }).click();
  await page.getByLabel('金额 / 数值').fill('1200');
  await page.getByRole('button', { name: '保存记录' }).click();
  await expect(page.getByText('¥ 1200.00')).toBeVisible();

  await page.getByRole('button', { name: /隐藏金额/ }).click();
  await expect(page.getByText('••••••').first()).toBeVisible();
  await page.getByRole('button', { name: /显示金额/ }).click();

  await page.screenshot({ path: path.join(os.tmpdir(), 'ledgerx-webview-desktop.png'), fullPage: true });
  expect(consoleErrors).toEqual([]);
});

test('mobile layout remains usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.locator('.brand-mini')).toBeVisible();
  await expect(page.getByRole('heading', { name: '财务总览' })).toBeVisible();
  await page.screenshot({ path: path.join(os.tmpdir(), 'ledgerx-webview-mobile.png'), fullPage: true });
});
