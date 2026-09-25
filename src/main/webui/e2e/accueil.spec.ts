// The home page (issue #485): « État de l'édition » lists the eleven steps of
// the cycle, each with a state and a link to the screen that moves it — or,
// for the coherence of the referential, a detail unfolded in place. The
// states are the server's; what a browser adds is that the lines render,
// that a seeded edition reads as filled and published, and that a link
// actually lands on the screen it names.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

const LIGNES = [
  'referentiels',
  'coherence',
  'collecte',
  'ouvertures',
  'besoin',
  'resolution',
  'problemes',
  'relecture',
  'publication',
  'confirmations',
  'foire',
];

test("la page d'accueil liste les onze étapes du cycle", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/');

  await expect(page.getByRole('heading', { name: "État de l'édition" })).toBeVisible();
  const lignes = page.locator('li.accueil-ligne');
  await expect(lignes).toHaveCount(LIGNES.length);
  for (const id of LIGNES) {
    await expect(page.locator(`li[data-ligne="${id}"]`), `ligne ${id}`).toBeVisible();
  }
  // Every line carries its state and a link: nothing is a dead end. The
  // coherence line alone unfolds in place, each of its anomalies linking on.
  await expect(page.locator('li.accueil-ligne a.accueil-lien')).toHaveCount(LIGNES.length - 1);
  await expect(page.locator('.accueil-bilan')).toContainText('à faire');
  await page.context().close();
});

test('une édition amorcée lit ses référentiels et sa publication, et chaque lien mène au bon écran', async ({
  browser,
}) => {
  await seedPlanning(admin);
  const page = await pageAdmin(browser, admin);
  await page.goto('/');

  const referentiels = page.locator('li[data-ligne="referentiels"]');
  // Filled, but not done: the seed's stands offer game categories nobody
  // among its two animateurs masters, and an orphan category is worth a look.
  await expect(referentiels).toContainText('À vérifier');
  await expect(referentiels).toContainText(/typologie\(s\) orpheline\(s\)/);
  // The reference database carries stands of its own; the seed adds its two animateurs.
  await expect(referentiels).toContainText(/\d{1,6} stands · 2 animateurs/);
  // seedPlanning publishes what it seeds: nobody is left to warn.
  const publication = page.locator('li[data-ligne="publication"]');
  await expect(publication).toContainText('À jour');
  // Nothing was ever solved on this fixture — the seats were written straight in.
  await expect(page.locator('li[data-ligne="resolution"]')).toContainText('Aucune résolution');

  // The links land where they say: the staffing tab of the Diagnostic, then the solver.
  await page.locator('li[data-ligne="besoin"] a.accueil-lien').click();
  await expect(page).toHaveURL(/\/diagnostic\?onglet=besoin$/);
  await expect(page.locator('#contenu')).toContainText('Minimum retenu');

  await page.goto('/');
  await page.locator('li[data-ligne="resolution"] a.accueil-lien').click();
  await expect(page).toHaveURL(/\/solveur$/);
  await expect(page.getByRole('button', { name: 'Calculer le planning' })).toBeVisible();

  // The old address of the solver still lands on it.
  await page.goto('/solver');
  await expect(page).toHaveURL(/\/solveur$/);
  await page.context().close();
});
