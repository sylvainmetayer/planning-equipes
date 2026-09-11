// L'écran Équité sur le planning ensemencé : le tableau liste les animateurs
// affectés, le tri se pose dans l'URL et survit au rechargement, l'export CSV
// répond, et un nom mène à la timeline de la personne.

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

test("le tableau d'équité liste les animateurs du planning enregistré, avec sa synthèse", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/equite');
  await expect(page.locator('#contenu')).toContainText('Équité par animateur');
  // `tbody` explicitement : la ligne de pied « Synthèse » porte la même classe
  // de colonne, et compterait comme une ligne de plus.
  const noms = page.locator('table tbody td.mat-column-animateur');
  await expect(noms).toHaveText([/Alice E2E/, /Bruno E2E/]);
  await expect(page.locator('.equite-synthese-row')).toContainText('Synthèse');
  await expect(page.locator('.equite-synthese-row')).toContainText('méd.');
  // La soirée lue sur les paramètres légaux, dite dans le sous-titre.
  await expect(page.locator('#contenu')).toContainText(/la soirée commence à \d\d:\d\d/);
  await page.context().close();
});

test("le tri d'une colonne se pose dans l'URL et survit au rechargement", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/equite');
  const noms = page.locator('table tbody td.mat-column-animateur');
  const entete = page.getByRole('button', { name: 'Animateur' });

  await entete.click();
  await expect(noms).toHaveText([/Alice E2E/, /Bruno E2E/]);
  await expect(page).toHaveURL(/[?&]sort=animateur/);
  await expect(page).toHaveURL(/[?&]dir=asc/);

  await entete.click();
  await expect(noms).toHaveText([/Bruno E2E/, /Alice E2E/]);
  await expect(page).toHaveURL(/[?&]dir=desc/);

  await page.reload();

  await expect(noms).toHaveText([/Bruno E2E/, /Alice E2E/]);
  await expect(page.getByRole('columnheader', { name: 'Animateur' })).toHaveAttribute(
    'aria-sort',
    'descending',
  );
  await page.context().close();
});

test('le filtre par nom se restaure au rechargement et se vide en une action', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/equite');
  await page.getByLabel('Filtrer par nom').fill('Alice');
  const noms = page.locator('table tbody td.mat-column-animateur');
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

test("l'export CSV répond avec une ligne par animateur", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/equite');
  await expect(page.locator('table tbody td.mat-column-animateur')).toHaveCount(2);

  const telechargement = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Exporter en CSV' }).click();
  const fichier = await telechargement;
  expect(fichier.suggestedFilename()).toBe('equite-planning.csv');
  await expect(page.locator('#contenu')).toContainText('equite-planning.csv');

  // Le même fichier par l'API : un en-tête, puis une ligne par personne.
  const reponse = await admin.get('/api/planning/equite/export');
  expect(reponse.status()).toBe(200);
  const csv = await reponse.text();
  expect(csv).toContain('animateur;heuresTotal;');
  expect(csv).toContain('Alice E2E;');
  expect(csv).toContain('Bruno E2E;');
  await page.context().close();
});

test('un clic sur un animateur ouvre sa timeline', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/equite');

  await page.getByRole('link', { name: /Alice E2E/ }).click();

  await expect(page).toHaveURL(new RegExp(`/timeline\\?animateur=${SEED.demandeur}`));
  await expect(page.locator('#contenu')).toContainText('Timeline animateur');
  await expect(page.locator('#contenu')).toContainText('Stands à couvrir');
  await page.context().close();
});
