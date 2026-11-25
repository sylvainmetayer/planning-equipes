// The responsable de stand, end to end: a local account holding the right on
// one stand reads « Mon stand » — the shifts and the empty seats, no name
// while the edition's setting says « horaires seuls », first and last names
// once an administrator turns them on in Paramètres › Édition — and prints the
// stand's sheet, which follows the same setting. Another stand never shows.

import { APIRequestContext, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import {
  EDITION_REFERENCE,
  SEED,
  connecterCompteLocal,
  contexteAdmin,
  creerCompteLocal,
  pageAdmin,
  seedPlanning,
  type CompteLocal,
} from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;
let compte: CompteLocal;

test.describe.configure({ mode: 'serial' });

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
  // Only the active edition is open to a responsable (ADR 0072).
  const activation = await admin.put(`/api/editions/${EDITION_REFERENCE}/active`);
  expect(activation.ok(), await activation.text()).toBe(true);
  const anonyme = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL });
  compte = await creerCompteLocal(admin, anonyme, 'Responsable E2E', {
    role: 'RESPONSABLE_STAND',
    editionId: EDITION_REFERENCE,
    standIds: [SEED.standDemandeur],
  });
  await anonyme.dispose();
});

test.afterAll(async () => {
  await admin.post(`/api/comptes/${compte.id}/desactivation`);
  await admin.put('/api/reglage-responsables', {
    data: { parDefaut: { nomsVisibles: false, ouvertureLe: null }, parStand: {} },
  });
  await admin.dispose();
});

test('horaires seuls : aucun nom, un seul stand, puis les noms une fois activés', async ({
  browser,
  page,
}) => {
  await connecterCompteLocal(page, compte);
  await expect(page).toHaveURL(/\/responsable$/);
  await expect(page.getByRole('heading', { level: 2, name: 'Stand E2E un' })).toBeVisible();
  await expect(page.getByText(/10:00\s*–\s*12:00/)).toBeVisible();
  await expect(page.getByText('1 place')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Affichette à imprimer' })).toBeVisible();

  const sansNoms = async () => {
    // Neither the screen nor the JSON the screen reads carries a name, nor the other stand.
    const texte = await page.locator('body').innerText();
    const json = await (await page.request.get('/api/responsable/stands')).text();
    for (const interdit of ['Alice', 'Bruno', 'Stand E2E deux']) {
      expect(texte, `écran : ${interdit}`).not.toContain(interdit);
      expect(json, `API : ${interdit}`).not.toContain(interdit);
    }
  };
  await sansNoms();

  // The administration is closed to this account, and so is the other stand's sheet.
  expect((await page.request.get('/api/animateurs')).status()).toBe(403);
  const autre = await page.request.get(`/api/responsable/stands/${SEED.standCible}/affichette.pdf`);
  expect(autre.ok()).toBe(false);

  // The administrator turns the names on, in Paramètres › Édition.
  const pageDeAdmin = await pageAdmin(browser, admin);
  await pageDeAdmin.goto('/parametres?onglet=edition');
  const carte = pageDeAdmin.locator('#responsables');
  await carte.getByRole('switch', { name: 'Afficher les prénoms et noms par défaut' }).click();
  await carte.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(
    pageDeAdmin.getByText('Réglage des responsables de stand enregistré.'),
  ).toBeVisible();
  await pageDeAdmin.context().close();

  await page.reload();
  await expect(page.getByText('Alice E2E')).toBeVisible();
  const json = await (await page.request.get('/api/responsable/stands')).text();
  expect(json).toContain('Alice');
  for (const interdit of ['Bruno', 'Stand E2E deux']) {
    expect(await page.locator('body').innerText()).not.toContain(interdit);
    expect(json).not.toContain(interdit);
  }
  // Names and nothing else: no birth date, no address.
  expect(json).not.toContain('1990-01-01');
  expect(json).not.toContain('@example.org');
});

test("l'affichette du stand se télécharge", async ({ page }) => {
  await connecterCompteLocal(page, compte);
  await expect(page.getByRole('heading', { level: 2, name: 'Stand E2E un' })).toBeVisible();
  const prevu = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Affichette à imprimer' }).click();
  const fichier = await prevu;
  expect(fichier.suggestedFilename()).toBe('affichette-stand.pdf');
  const octets = readFileSync(await fichier.path());
  expect(octets.subarray(0, 4).toString('latin1')).toBe('%PDF');
  expect(octets.length).toBeGreaterThan(500);
});
