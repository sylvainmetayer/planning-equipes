// The Planning page per person, where the Équité screen went, on the seeded
// planning: one line per animateur with the Équité columns, the evening read
// on the legal parameters, a sort in the URL that survives a reload, the CSV
// export, and a name leading to the person's fiche — where the screen's
// former « Fiche » reading, radar included, moved.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, SEED, seedPlanning } from './support';
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

const NOMS = '.planning-grille tbody th a.planning-grille-lien';

test("l'écran Équité mène au planning par personne, avec ses colonnes et sa soirée", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/equite');
  await expect(page).toHaveURL(/\/journee\?.*axe=personne/);
  await expect(page.locator(NOMS)).toHaveText([/Alice E2E/, /Bruno E2E/]);
  await expect(page.locator('.planning-grille thead')).toContainText('Écart méd.');
  // The one evening of the application, read on the legal parameters.
  await expect(page.locator('#contenu')).toContainText(/soirée, dès \d\d:\d\d/);
  await page.context().close();
});

test("le tri d'une colonne d'équité se pose dans l'URL et survit au rechargement", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  // The Équité screen's own sort key, carried over by the redirect.
  await page.goto('/equite?sort=heuresTotal&dir=desc');
  await expect(page).toHaveURL(/[?&]sort=heuresTotal/);
  const entete = page.getByRole('button', { name: 'Heures', exact: true });
  await expect(entete.locator('xpath=..')).toHaveAttribute('aria-sort', 'descending');

  await entete.click();
  await expect(page).not.toHaveURL(/[?&]sort=/);

  await entete.click();
  await expect(page).toHaveURL(/[?&]dir=asc/);
  await page.reload();
  await expect(entete.locator('xpath=..')).toHaveAttribute('aria-sort', 'ascending');
  await page.context().close();
});

test('le filtre par nom se restaure au rechargement et se vide en une action', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?axe=personne');
  await page.getByLabel('Filtrer par nom').fill('Alice');
  const noms = page.locator(NOMS);
  await expect(noms).toHaveText([/Alice E2E/]);
  await expect(page).toHaveURL(/[?&]q=Alice/);

  await page.reload();
  await expect(page.getByLabel('Filtrer par nom')).toHaveValue('Alice');
  await expect(noms).toHaveText([/Alice E2E/]);

  await page.getByRole('button', { name: 'Réinitialiser la vue' }).click();
  await expect(page).not.toHaveURL(/[?&]q=/);
  await expect(noms).toHaveCount(2);
  await page.context().close();
});

test("l'export CSV d'équité répond avec une ligne par animateur", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?axe=personne');
  await expect(page.locator(NOMS)).toHaveCount(2);

  const telechargement = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Équité (CSV)' }).click();
  const fichier = await telechargement;
  expect(fichier.suggestedFilename()).toBe('equite-planning.csv');

  // Le même fichier par l'API : un en-tête, puis une ligne par personne.
  const reponse = await admin.get('/api/planning/equite/export');
  expect(reponse.status()).toBe(200);
  const csv = await reponse.text();
  expect(csv).toContain('animateur;heuresTotal;');
  expect(csv).toContain('Alice E2E;');
  expect(csv).toContain('Bruno E2E;');
  await page.context().close();
});

test("l'ancienne lecture « Fiche » mène à la section équité de la fiche, radar affiché", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto(`/equite?vue=fiche&animateur=${SEED.demandeur}&axes=heuresJourFerie`);

  await expect(page).toHaveURL(
    new RegExp(`/animateurs/${SEED.demandeur}\\?section=equite&axes=heuresJourFerie$`),
  );
  await expect(page.locator('#contenu')).toContainText('Identité et contact');
  await expect(page.locator('#fiche-section-equite')).toHaveAttribute('open', '');
  await expect(page.locator('app-equite-radar')).toBeVisible();
  await page.context().close();
});

test('un clic sur un animateur ouvre sa fiche, son planning déplié et son radar à côté', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?axe=personne');

  await page
    .locator(NOMS)
    .filter({ hasText: /Alice E2E/ })
    .click();

  await expect(page).toHaveURL(new RegExp(`/animateurs/${SEED.demandeur}$`));
  await expect(page.locator('#contenu')).toContainText('Identité et contact');
  await expect(page.locator('#fiche-section-timeline')).toHaveAttribute('open', '');

  await page.getByRole('heading', { name: 'Charge et équité' }).click();
  await expect(page.locator('#contenu')).toContainText('Écart à la médiane');
  await expect(page.locator('app-equite-radar')).toBeVisible();
  await page.context().close();
});
