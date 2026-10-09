import { expect, test as base } from '@playwright/test';

export const test = base.extend({
  page: async ({ page }, use) => {
    await page.route('**/api/v1/system/session', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { authMode: 'browser', csrfToken: 'A'.repeat(43) } }),
      });
    });
    await use(page);
  },
});

export { expect };
