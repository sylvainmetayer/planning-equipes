// The espace animateur and the foire au planning flow (issue #165), end to
// end: token boundary, planning view, submission with prevalidation, admin
// decision, and the outcome back on the animateur's side.

import { APIRequestContext, expect, test } from '@playwright/test';
import { SEED, contexteAdmin, dernierCodeMailpit, jetonDe, ouvrirSessionEspace, pageAdmin, seedPlanning } from './support';

const EMAIL_ALICE = `${SEED.demandeur}@example.org`;

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

  test("sans session, le lien mène à l'écran du code d'accès — pas au planning", async ({ page }) => {
    await page.goto(`/animateur/${jeton}`);
    await expect(page.getByText('Accès à votre espace')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Recevoir mon code par e-mail' })).toBeVisible();
    await expect(page.getByText('Stand E2E un')).toHaveCount(0);
    // The API itself refuses: the screen is not a mere curtain.
    const reponse = await page.request.get(`/api/espace-animateur/${jeton}`);
    expect(reponse.status()).toBe(401);
  });

  test("le code reçu par e-mail ouvre l'espace depuis l'écran d'accès", async ({ page }) => {
    await page.goto(`/animateur/${jeton}`);
    await page.getByRole('button', { name: 'Recevoir mon code par e-mail' }).click();
    await expect(page.getByText('Code envoyé à E•••@example.org', { exact: false })).toBeVisible();
    // The code lands in Mailpit — typed here as the animateur would type it.
    const code = await dernierCodeMailpit(page.request, EMAIL_ALICE);
    await page.getByLabel('Code reçu').fill(code);
    await page.getByRole('button', { name: 'Ouvrir mon espace' }).click();
    await expect(page.getByText('Alice E2E')).toBeVisible();
    await expect(page.getByText('Stand E2E un')).toBeVisible();
  });

  test('le jeton ouvre le planning personnel, sans navigation admin', async ({ page }) => {
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
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
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
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
    // The score delta renders as a real score, never as a raw object dump.
    await expect(pageAdmin.locator('.echanges-impact code')).toHaveText(
      /^[+-]?\d+hard \/ [+-]?\d+medium \/ [+-]?\d+soft$/
    );
    await expect(pageAdmin.getByText('[object Object]')).toHaveCount(0);
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

  test("refuser une demande transmet le motif à l'animateur", async ({ page, browser }) => {
    test.slow();
    // After the accepted swap, Alice proposes another one from her new seat.
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}/echanges`);
    await page.getByLabel('Créneau concerné').click();
    await page.getByRole('option').first().click();
    await page.getByLabel('Échanger avec').click();
    await page.getByRole('option', { name: 'Bruno E2E' }).click();
    await page.getByRole('button', { name: 'Ajouter à la liste' }).click();
    await page.getByRole('button', { name: 'Soumettre mes demandes' }).click();
    await expect(page.getByText('En attente').first()).toBeVisible();

    // The admin refuses, with a reason.
    const pageAdminEchanges = await pageAdmin(browser, admin);
    await pageAdminEchanges.goto('/echanges');
    await pageAdminEchanges.getByRole('button', { name: 'Refuser' }).first().click();
    await pageAdminEchanges
      .getByRole('dialog')
      .getByLabel("Motif du refus (transmis à l'animateur)")
      .fill('Bruno doit rester sur ce stand');
    await pageAdminEchanges.getByRole('dialog').getByRole('button', { name: 'Refuser' }).click();
    await expect(pageAdminEchanges.getByText('Demande refusée', { exact: false })).toBeVisible();
    await pageAdminEchanges.context().close();

    // The animateur sees the outcome and the admin's comment.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Refusée').first()).toBeVisible();
    await expect(page.getByText('Bruno doit rester sur ce stand')).toBeVisible();
  });

  test("annuler une demande en attente depuis l'espace", async ({ page }) => {
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}/echanges`);
    await page.getByLabel('Créneau concerné').click();
    await page.getByRole('option').first().click();
    await page.getByLabel('Échanger avec').click();
    await page.getByRole('option', { name: 'Bruno E2E' }).click();
    await page.getByRole('button', { name: 'Ajouter à la liste' }).click();
    await page.getByRole('button', { name: 'Soumettre mes demandes' }).click();
    await page.getByRole('button', { name: 'Annuler cette demande' }).first().click();
    await expect(page.getByText('Demande annulée.')).toBeVisible();
    await expect(page.getByText('Annulée').first()).toBeVisible();
  });

  test("le périmètre du jeton : l'espace ne donne aucune session admin", async ({ page }) => {
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);
    const reponse = await page.request.get('/api/constraints', { maxRedirects: 0 });
    expect(reponse.status()).toBe(401);
  });
});
