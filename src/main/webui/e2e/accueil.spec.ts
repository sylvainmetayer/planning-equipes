// The home page (issue #485): « État de l'édition » lists the twelve steps of
// the cycle, each with a state and a link to the screen that moves it — the
// coherence of the referential leading to the feed of alerts and messages. The
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
  'phase',
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

test('the home page lists the twelve steps of the cycle', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/');

  await expect(page.getByRole('heading', { name: "État de l'édition" })).toBeVisible();
  const lignes = page.locator('li.accueil-ligne');
  await expect(lignes).toHaveCount(LIGNES.length);
  for (const id of LIGNES) {
    await expect(page.locator(`li[data-ligne="${id}"]`), `ligne ${id}`).toBeVisible();
  }
  // Every line carries its state and a link: nothing is a dead end. The
  // coherence has none while it holds no anomaly, and the phase unfolds its
  // own control in place, so it has no link out either.
  expect(await page.locator('li.accueil-ligne a.accueil-lien').count()).toBeGreaterThanOrEqual(
    LIGNES.length - 2,
  );
  await expect(page.locator('li[data-ligne="phase"] app-phase-edition-control')).toBeVisible();
  // Never the raw score: the reading is in sentences.
  await expect(page.locator('#contenu')).not.toContainText(/\dhard\//);
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

  // The links land where they say: the staffing tab of the Diagnostic, then « Calculer ».
  await page.locator('li[data-ligne="besoin"] a.accueil-lien').click();
  await expect(page).toHaveURL(/\/diagnostic\?onglet=besoin$/);
  await expect(page.locator('#contenu')).toContainText('Minimum retenu');

  await page.goto('/');
  await page.locator('li[data-ligne="resolution"] a.accueil-lien').click();
  await expect(page).toHaveURL(/\/journee\?onglet=calculer$/);
  await expect(page.getByRole('button', { name: 'Calculer le planning' })).toBeVisible();

  // The old addresses of the solver still land on it.
  for (const ancienne of ['/solver', '/solveur']) {
    await page.goto(ancienne);
    await expect(page, ancienne).toHaveURL(/\/journee\?onglet=calculer$/);
  }
  await page.context().close();
});

/**
 * The bell of the toolbar opens « À traiter aujourd'hui » on the home screen,
 * and the former Notifications page lands there too: the plan's alerts, the
 * anomalies of the referential and the history of the messages are one feed,
 * which the type kept in the address narrows.
 */
test("la cloche et l'ancienne page Notifications mènent au fil d'alertes et de messages", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/stands');
  await page.locator('a.notifications-toggle').click();
  await expect(page).toHaveURL(/\/#a-traiter$/);
  await expect(page.locator('#a-traiter')).toBeVisible();
  await expect(page.locator('#fil-accueil')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tout marquer comme lu' })).toBeVisible();

  // The former address opens the feed on its messages, the filter in the URL.
  await page.goto('/notifications');
  await expect(page).toHaveURL(/\/\?type=messages#a-traiter$/);
  await expect(page.locator('#fil-accueil a[aria-current="true"]')).toContainText('Messages');

  // Another type: the address follows, and "Tout" drops the filter.
  await page.locator('#fil-accueil nav a', { hasText: 'Alertes du plan' }).click();
  await expect(page).toHaveURL(/\/\?type=alertes#a-traiter$/);
  await page.locator('#fil-accueil nav a', { hasText: 'Tout' }).click();
  await expect(page).toHaveURL(/\/#a-traiter$/);
  await page.context().close();
});
