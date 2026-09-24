// Two smaller screens end to end: posing/removing a planning lock through the
// form (issue #87 UI, extended by #165's ANIMATEUR_CRENEAU rows), and the
// in-app guide with its search — including the new foire au planning section.

import { APIRequestContext, expect, test } from '@playwright/test';
import { SEED, contexteAdmin, pageAdmin, seedPlanning } from './support';
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

test('poser un verrouillage de journée, mesurer son impact, le retirer', async ({ browser }) => {
  test.slow();
  const page = await pageAdmin(browser, admin);
  await page.goto('/verrouillages');

  // Type "Journée" is the default; pick the seeded day.
  await page.getByLabel('Journée').click();
  await page.getByRole('option', { name: SEED.jour }).click();
  // The preview counts the already-assigned seats the lock would freeze.
  await expect(page.locator('#contenu')).toContainText(/affectation\(s\) déjà enregistrée\(s\)/);
  await page.getByRole('button', { name: 'Verrouiller', exact: true }).click();
  await expect(page.getByText('Verrouillage enregistré.')).toBeVisible();

  const ligne = page.getByRole('row', { name: new RegExp(SEED.jour) }).first();
  await expect(ligne).toBeVisible();
  await ligne.getByRole('button').last().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Déverrouiller' }).click();
  await expect(page.getByText('Verrouillage supprimé.')).toBeVisible();
  await page.context().close();
});

test("l'aide se recherche et documente la foire au planning", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/aide');
  await expect(page.locator('#contenu')).toContainText("Aide à l'utilisation");
  await expect(page.locator('#contenu')).toContainText('Foire au planning (échanges de créneaux)');

  // The filter narrows the guide to the matching sections. « Prise en main »
  // is not one of the sections it drops: the cycle it describes ends on
  // opening and closing that very fair.
  await page.getByLabel("Rechercher dans l'aide").fill('foire');
  await expect(page.locator('#contenu')).toContainText('Foire au planning (échanges de créneaux)');
  await expect(page.locator('#contenu')).toContainText('Prise en main');
  await expect(page.locator('#contenu')).not.toContainText('Raccourcis clavier');

  await page.getByLabel("Rechercher dans l'aide").fill('');
  await expect(page.locator('#contenu')).toContainText('Raccourcis clavier');
  await page.context().close();
});
