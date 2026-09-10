// The white-label promise, checked in a browser rather than on an endpoint.
//
// `/api/branding` answering the right JSON proves the configuration is read.
// It does not prove the application *uses* it: the title could still be
// hard-coded in thirty-five routes, and a customer's logo could still be baked
// into six templates. What follows is the part only a rendered page can say.
//
// This suite targets an instance configured with NO branding variable, which
// is the demanding case: a deployment that set nothing must look like nobody
// in particular, not like the festival the code was written for.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

test("sans variable de marque, l'endpoint annonce une identité neutre et sans logo", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);

  const marque = await (await admin.get('/api/branding')).json();
  expect(marque.productName).toBe('Planning Équipes');
  // Empty means "show no logo", not "show the default one".
  expect(marque.logoUrl).toBe('');

  await page.close();
});

test('le titre de la page est construit à partir de la marque, pas codé dans la route', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);

  await page.goto('/stands');
  await expect(page).toHaveTitle('Stands — Planning Équipes');

  await page.goto('/animateurs');
  await expect(page).toHaveTitle('Animateurs — Planning Équipes');

  // Two different routes, one suffix: this is what makes renaming a
  // deployment possible without touching app.routes.ts.
  await page.close();
});

test("aucun logo n'est rendu quand aucun n'est configuré", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/stands');
  await expect(page.getByRole('heading').first()).toBeVisible();

  // The shared component renders nothing at all rather than a broken image
  // or another customer's mark.
  await expect(page.locator('img.app-logo')).toHaveCount(0);

  await page.close();
});

test('les pages publiques portent la même identité, sans authentification', async ({ browser }) => {
  // An animateur whose link expired lands here with no session: the brand
  // must hold on that side too, otherwise a visitor's landing page is the one
  // page that cannot say which product it belongs to.
  const contexte = await browser.newContext();
  const page = await contexte.newPage();

  await page.goto('/mentions-legales');
  await expect(page).toHaveTitle('Mentions légales — Planning Équipes');
  await expect(page.locator('img.app-logo')).toHaveCount(0);

  await page.goto('/login');
  await expect(page.getByText('Planning Équipes — administration')).toBeVisible();

  await contexte.close();
});
