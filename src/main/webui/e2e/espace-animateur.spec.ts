// The espace animateur and the foire au planning flow (issue #165), end to
// end: token boundary, planning view, submission with prevalidation, admin
// decision, and the outcome back on the animateur's side.

import { APIRequestContext, expect, test } from '@playwright/test';
import { SEED, contexteAdmin, jetonDe, seedPlanning } from './support';

let admin: APIRequestContext;
let jeton: string;

test.beforeAll(async ({ playwright }, testInfo) => {
  const baseURL = testInfo.project.use.baseURL as string;
  admin = await contexteAdmin(playwright, baseURL);
  await seedPlanning(admin);
  jeton = await jetonDe(admin, SEED.demandeur);
});

test.afterAll(async () => {
  await admin.dispose();
});

test.describe('espace animateur', () => {
  test('un jeton inconnu montre une impasse propre, sans chrome admin', async ({ page }) => {
    await page.goto('/animateur/jeton-invente');
    await expect(page.getByText("Ce lien n'est pas (ou plus) valide", { exact: false })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Mon planning' })).toHaveCount(0);
  });

  test('le jeton ouvre le planning personnel, sans navigation admin', async ({ page }) => {
    await page.goto(`/animateur/${jeton}`);
    await expect(page.getByText('Alice E2E')).toBeVisible();
    await expect(page.getByText('Stand E2E un')).toBeVisible();
    // The admin drawer and its pages are absent from this layout.
    await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toHaveCount(0);
  });

  test("soumettre un échange, le voir accepté par l'admin, retrouver le résultat", async ({ page, browser }) => {
    // Two browser contexts and a decision round trip: triple the budget.
    test.slow();
    // --- Animateur side: build then submit one demande. ---
    await page.goto(`/animateur/${jeton}/echanges`);
    await page.getByLabel('Créneau concerné').click();
    await page.getByRole('option').first().click();
    await page.getByLabel('Échanger avec').click();
    await page.getByRole('option', { name: 'Bruno E2E' }).click();
    await page.getByLabel('Motif').fill('rendez-vous médical');
    await page.getByRole('button', { name: 'Ajouter à la liste' }).click();
    await page.getByRole('button', { name: 'Soumettre mes demandes' }).click();
    await expect(page.getByText('En attente').first()).toBeVisible();

    // --- Admin side: the demande shows up and gets accepted. ---
    const contexteAdminNavigateur = await browser.newContext({
      storageState: await admin.storageState()
    });
    const pageAdmin = await contexteAdminNavigateur.newPage();
    await pageAdmin.goto('/echanges');
    await expect(pageAdmin.getByText('Alice E2E').first()).toBeVisible();
    await pageAdmin.getByRole('button', { name: "Voir l'impact sur le planning" }).first().click();
    await expect(pageAdmin.getByText('Échange croisé', { exact: false })).toBeVisible();
    await pageAdmin.getByRole('button', { name: 'Accepter', exact: true }).first().click();
    // Confirmation dialog.
    await pageAdmin.getByRole('dialog').getByRole('button', { name: 'Accepter' }).click();
    await expect(pageAdmin.getByText('Acceptée').first()).toBeVisible();
    await contexteAdminNavigateur.close();

    // --- Back on the animateur side: outcome visible, swap applied. ---
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Acceptée').first()).toBeVisible();
    await page.getByRole('link', { name: 'Mon planning' }).click();
    await expect(page.getByText('Stand E2E deux')).toBeVisible();
  });

  test("le périmètre du jeton : l'espace ne donne aucune session admin", async ({ page }) => {
    await page.goto(`/animateur/${jeton}`);
    const reponse = await page.request.get('/api/constraints', { maxRedirects: 0 });
    expect(reponse.status()).toBe(401);
  });
});
