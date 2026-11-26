// « Versions du plan » (issue #702) and « Comparer deux versions » (issue #272):
// the versions and the finished solves in one table, the comparator on its own
// screen. What only a browser proves: two ticked rows open that screen, the
// address keeps the pair, and the former screens land on their successor.

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
    const capture = await admin.post('/api/planning/versions', { data: { libelle } });
    expect(capture.ok(), await capture.text()).toBe(true);
  }
});

test.afterAll(async () => {
  await admin.dispose();
});

test('deux lignes cochées ouvrent « Comparer deux versions », et l’adresse garde la paire', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/solveur?onglet=versions');

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

  // « Comparer deux versions » is a screen of its own, the pair in its address.
  await expect(page).toHaveURL(/\/comparer\?vue=versions&comparer=\d+(%2C|,)\d+/);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Comparer deux versions' }),
  ).toBeVisible();
  const panneau = page.getByRole('complementary', { name: 'Comparaison' });
  await expect(panneau).toBeVisible();
  await expect(panneau).toContainText(LIBELLES[0]);
  await expect(panneau).toContainText(LIBELLES[1]);

  // A reload reopens the same comparison.
  await page.reload();
  await expect(page.getByRole('complementary', { name: 'Comparaison' })).toBeVisible();

  await page
    .getByRole('complementary', { name: 'Comparaison' })
    .getByRole('button', { name: 'Fermer' })
    .click();
  await expect(page.getByRole('complementary', { name: 'Comparaison' })).toHaveCount(0);
  await expect(page).not.toHaveURL(/comparer=/);

  // The editions are the other view of the same screen.
  await page
    .getByRole('radiogroup', { name: "Ce que l'on compare" })
    .getByRole('radio', { name: 'Éditions' })
    .click();
  await expect(page).toHaveURL(/vue=editions/);
  await expect(page.locator('.delta-choix')).toBeVisible();
  await page.context().close();
});

test('les anciennes adresses des versions mènent à la nouvelle page', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  for (const ancienne of ['/instantanes', '/comparateur', '/kpi?rang=2&dosage=x']) {
    await page.goto(ancienne);
    await expect(page, ancienne).toHaveURL(/\/solveur\?onglet=versions$/);
    await expect(page.getByRole('heading', { level: 2, name: 'Versions du plan' })).toBeVisible();
  }
  // The editions' comparison, now a view of « Comparer deux versions », keeps its pair.
  await page.goto('/editions/comparer?a=E1&b=E2');
  await expect(page).toHaveURL(/\/comparer\?vue=editions&a=E1&b=E2/);
  await page.context().close();
});
