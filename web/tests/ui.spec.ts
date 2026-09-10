import { test, expect } from '@playwright/test';
import path from 'node:path';
import os from 'node:os';

test('core ledger workflow, metrics, privacy and report', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
  await page.setViewportSize({ width: 1360, height: 860 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '财务总览' })).toBeVisible();
  await page.getByRole('button', { name: /新增记录/ }).click();
  await page.getByLabel('金额 / 数值').fill('5000');
  await page.getByLabel('分类').fill('父母生活费');
  await page.getByLabel('收入来源').selectOption({ label: '父母支持' });
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
  await expect(page.getByRole('heading', { name: '安全垫' })).toBeVisible();
  await expect(page.getByText('构成此指标的记录')).toBeVisible();
  await page.getByRole('button', { name: '添加记录' }).click();
  await page.getByLabel('金额 / 数值').fill('1200');
  await page.getByRole('button', { name: '保存记录' }).click();
  await expect(page.getByText('¥ 1200.00',{exact:true}).first()).toBeVisible();
  await page.getByRole('button', { name: /隐藏金额/ }).click();
  await expect(page.getByText('••••••').first()).toBeVisible();
  await page.getByRole('button', { name: /显示金额/ }).click();
  await page.getByRole('button', { name: '返回' }).click();
  await page.getByRole('button', { name: /财务分析/ }).click();
  await expect(page.getByRole('heading', { name: '财务分析' })).toBeVisible();
  await page.getByRole('button', { name: '查看数据表' }).click();
  await expect(page.getByText('报告数据表')).toBeVisible();
  await page.screenshot({ path: path.join(os.tmpdir(), 'ledgerx-webview-desktop.png'), fullPage: true });
  expect(consoleErrors).toEqual([]);
});

test('records can be edited, recycled, restored and undone', async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 860 }); await page.goto('/');
  await page.getByRole('button', { name: /新增记录/ }).click(); await page.getByLabel('金额 / 数值').fill('88'); await page.getByLabel('分类').fill('测试交通'); await page.getByRole('button', { name: '保存记录' }).click();
  await page.getByRole('button', { name: /全部记录/ }).click(); await page.getByRole('button', { name: '编辑' }).first().click(); await expect(page.getByText('修改预览')).toBeVisible(); await page.getByLabel('金额 / 数值').fill('99'); await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByText('¥ 99.00')).toBeVisible();
  page.once('dialog', dialog => dialog.accept()); await page.getByRole('button', { name: '删除' }).first().click(); await expect(page.getByText('记录已移入回收站')).toBeVisible(); await page.getByRole('button', { name: '撤销' }).click(); await expect(page.getByText('¥ 99.00')).toBeVisible();
});

test('warning rules support preview, AND and lifecycle', async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 860 }); await page.goto('/'); await page.getByRole('button', { name: /预警中心/ }).click();
  await expect(page.getByRole('heading', { name: '预警中心' })).toBeVisible(); await page.getByRole('button', { name: '+ 添加 AND 条件' }).click(); await expect(page.getByText('并且')).toBeVisible(); await page.getByRole('button', { name: '试算规则' }).click(); await expect(page.getByText(/按当前账本/)).toBeVisible(); await page.getByRole('button', { name: '保存并启用' }).click(); await expect(page.getByText('1 条启用')).toBeVisible();
});

test('settings data lifecycle controls are reachable', async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 860 }); await page.goto('/'); await page.getByRole('button', { name: '设置' }).click(); await expect(page.getByRole('heading', { name: '设置' })).toBeVisible(); await expect(page.getByText('数据与备份')).toBeVisible(); await page.getByRole('button', { name: '清空账本' }).click(); await expect(page.getByRole('heading',{name:'清空账本'})).toBeVisible(); await page.getByLabel('危险操作确认').fill('清空 LedgerX'); await page.getByRole('button', { name: '确认执行' }).click(); await expect(page.getByText('保护快照已经创建')).toBeVisible();
});

test('mobile layout remains usable', async ({ page }) => { await page.setViewportSize({ width: 390, height: 844 }); await page.goto('/'); await expect(page.locator('.brand-mini')).toBeVisible(); await expect(page.getByRole('heading', { name: '财务总览' })).toBeVisible(); await page.screenshot({ path: path.join(os.tmpdir(), 'ledgerx-webview-mobile.png'), fullPage: true }); });