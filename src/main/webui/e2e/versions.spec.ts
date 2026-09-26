// « Versions du plan » (issue #702): the snapshots and the finished solves in
// one table, the comparator as a panel beside it. What only a browser proves:
// two ticked rows open the panel, the address keeps the pair, and the three
// former screens land here.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;
const LIBELLES = ['E2E versions A', 'E2E versions B'];

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
  for (const libelle of LIBELLES) {
    const capture = await admin.post('/api/planning/snapshots', { data: { libelle } });
    expect(capture.ok(), await capture.text()).toBe(true);
  }
});

test.afterAll(async () => {
  await admin.dispose();
});

test('deux lignes cochées ouvrent la comparaison à côté du tableau, et l’adresse la garde', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/versions');

  const ligne = (libelle: string) => page.getByRole('row', { name: new RegExp(libelle) });
  await expect(ligne(LIBELLES[0])).toBeVisible();
  // The plan in place heads the table, tickable like any snapshot.
  await expect(page.locator('table.versions-table tbody tr').first()).toContainText(
    'Plan en place',
  );

  const comparer = page.locator('.versions-toolbar').getByRole('button', { name: /Comparer/ });
  await expect(comparer).toBeDisabled();
  await ligne(LIBELLES[0]).getByRole('checkbox').check();
  await ligne(LIBELLES[1]).getByRole('checkbox').check();
  await comparer.click();

  const panneau = page.getByRole('complementary', { name: 'Comparaison' });
  await expect(panneau).toBeVisible();
  await expect(panneau).toContainText(LIBELLES[0]);
  await expect(panneau).toContainText(LIBELLES[1]);
  await expect(page).toHaveURL(/comparer=\d+%2C\d+|comparer=\d+,\d+/);

  // A reload reopens the same comparison.
  await page.reload();
  await expect(page.getByRole('complementary', { name: 'Comparaison' })).toBeVisible();

  await page
    .getByRole('complementary', { name: 'Comparaison' })
    .getByRole('button', { name: 'Fermer' })
    .click();
  await expect(page.getByRole('complementary', { name: 'Comparaison' })).toHaveCount(0);
  await expect(page).not.toHaveURL(/comparer=/);
  await page.context().close();
});

test('les anciennes adresses des versions mènent à la nouvelle page', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  for (const ancienne of ['/instantanes', '/comparateur', '/kpi?rang=2&dosage=x']) {
    await page.goto(ancienne);
    await expect(page, ancienne).toHaveURL(/\/versions$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Versions du plan' })).toBeVisible();
  }
  await page.context().close();
});
