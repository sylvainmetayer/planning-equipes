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
  // The old address lands on its tab of « Consignes au solveur » (issue #719).
  await page.goto('/verrouillages');
  await expect(page).toHaveURL(/\/consignes-solveur\?onglet=verrouillages/);
  // The work-in-progress banner is gone: the lock is honoured by every solve.
  await expect(page.locator('#contenu')).not.toContainText('en cours de développement');

  // « Animateur » is the default type, the frequent case; a day is one choice away.
  await expect(page.getByLabel('Animateur', { exact: true })).toBeVisible();
  await page.getByLabel('Type').click();
  await page.getByRole('option', { name: 'Journée' }).click();
  await page.getByLabel('Journée').click();
  await page.getByRole('option', { name: SEED.jour }).click();
  // The preview counts the already-assigned seats the lock would freeze, and
  // leads to the day that shows them.
  await expect(page.locator('#contenu')).toContainText(/affectation\(s\) déjà enregistrée\(s\)/);
  await expect(
    page.getByRole('link', { name: 'Voir ce qui sera figé dans Journée' }),
  ).toHaveAttribute('href', `/journee?date=${SEED.jour}`);
  await page.getByRole('button', { name: 'Verrouiller', exact: true }).click();
  await expect(page.getByText('Verrouillage enregistré.')).toBeVisible();
  // Something the next solve must respect was written: relaunching is offered in place.
  await expect(page.getByText('Le prochain calcul en tiendra compte.')).toBeVisible();

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
