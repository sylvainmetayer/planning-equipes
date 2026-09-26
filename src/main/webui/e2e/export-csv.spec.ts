// L'export CSV vu du navigateur : l'archive part, elle tient ce qui est coché,
// et ce qu'elle tient se réimporte par les onglets d'à côté.

import { readFileSync } from 'node:fs';
import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

test("l'archive ne tient que les référentiels cochés, et part au clic", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/fichiers?onglet=exporter');

  // Six référentiels, tous cochés d'entrée : recopier une édition entière
  // est le cas courant.
  await expect(page.locator('.export-csv-liste li')).toHaveCount(6);
  await expect(page.locator('.export-csv-liste input:checked')).toHaveCount(6);

  // Décoché, le référentiel quitte le compte du bouton — exactement de ses
  // lignes à lui, que sa propre ligne affiche (le jeu de référence peut n'en
  // avoir aucune : l'arithmétique reste vérifiable).
  const bouton = page.getByRole('button', { name: /Télécharger l'archive/ });
  // Le bouton écrit son compte entre parenthèses, une ligne le pose nu.
  const compte = (texte: string | null) => Number(/(\d{1,9}) ligne/.exec(texte ?? '')?.[1] ?? -1);
  const ligneAnimateurs = page.locator('.export-csv-liste li').filter({ hasText: 'Animateurs' });
  const totalAvant = compte(await bouton.textContent());
  const lignesAnimateurs = compte(await ligneAnimateurs.textContent());
  expect(totalAvant).toBeGreaterThanOrEqual(0);
  await ligneAnimateurs.locator('input').uncheck();
  await expect
    .poll(async () => compte(await bouton.textContent()))
    .toBe(totalAvant - lignesAnimateurs);

  // Tout décocher n'offre rien à télécharger.
  for (const libelle of ['Typologies', 'Emplacements', 'Stands', 'Créneaux', 'Journées types']) {
    await page
      .locator('.export-csv-liste li')
      .filter({ hasText: libelle })
      .locator('input')
      .uncheck();
  }
  await expect(bouton).toBeDisabled();

  await page.locator('.export-csv-liste li').filter({ hasText: 'Stands' }).locator('input').check();
  await expect(bouton).toBeEnabled();

  const telechargement = page.waitForEvent('download');
  await bouton.click();
  const fichier = await telechargement;
  expect(fichier.suggestedFilename()).toBe('referentiels-csv.zip');

  // Ce que seul un navigateur prouve : le fichier est bien arrivé, et c'est une
  // archive. Ce qu'elle contient est tenu par `ReferentielCsvExportServiceTest`,
  // qui repasse chaque entrée par son propre import.
  const octets = readFileSync(await fichier.path());
  expect(octets.subarray(0, 2).toString('latin1')).toBe('PK');
  expect(octets.length).toBeGreaterThan(0);
  // Le nom de l'entrée voyage en clair dans l'en-tête local du zip.
  expect(octets.toString('latin1')).toContain('stands.csv');
  expect(octets.toString('latin1')).not.toContain('animateurs.csv');
  // Décochés comme les autres, les deux fichiers du calendrier restent dehors.
  expect(octets.toString('latin1')).not.toContain('creneaux.csv');
  expect(octets.toString('latin1')).not.toContain('journees-types.csv');

  await page.context().close();
});
