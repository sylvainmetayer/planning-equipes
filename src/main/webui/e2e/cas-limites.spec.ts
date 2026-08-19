// Edge cases of the foire au planning perimeter (issue #165): token
// revocation seen from the browser, the prevalidation verdict travelling to
// both sides, and admin deep links without a session.

import { APIRequestContext, expect, test } from '@playwright/test';
import { SEED, contexteAdmin, jetonDe, pageAdmin, seedPlanning } from './support';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  const baseURL = testInfo.project.use.baseURL as string;
  admin = await contexteAdmin(playwright, baseURL);
});

test.afterAll(async () => {
  await admin.dispose();
});

test.describe('cas limites', () => {
  test("régénérer le jeton tue l'ancien lien, le nouveau prend le relais", async ({ page }) => {
    await seedPlanning(admin);
    const ancienJeton = await jetonDe(admin, SEED.demandeur);

    // The old link works…
    await page.goto(`/animateur/${ancienJeton}`);
    await expect(page.getByText('Alice E2E')).toBeVisible();

    // …until the admin rotates the token.
    const rotation = await admin.post(`/api/animateurs/${SEED.demandeur}/jeton`, {
      headers: { 'Content-Type': 'application/json' }
    });
    expect(rotation.ok(), await rotation.text()).toBe(true);
    const { jeton: nouveauJeton } = (await rotation.json()) as { jeton: string };
    expect(nouveauJeton).not.toBe(ancienJeton);

    // The already-open page dies on reload: clean dead end, no leak.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText("Ce lien n'est pas (ou plus) valide", { exact: false })).toBeVisible();
    await expect(page.getByText('Alice E2E')).toHaveCount(0);

    // The API answers the same 404 as a never-issued token.
    const reponse = await page.request.get(`/api/espace-animateur/${ancienJeton}`);
    expect(reponse.status()).toBe(404);

    // The regenerated link opens the same espace.
    await page.goto(`/animateur/${nouveauJeton}`);
    await expect(page.getByText('Alice E2E')).toBeVisible();
  });

  test('une demande infaisable est signalée à l’animateur et à l’admin', async ({ page, browser }) => {
    test.slow();
    await seedPlanning(admin, { avecCollegueIndisponible: true });
    const jeton = await jetonDe(admin, SEED.demandeur);

    // Alice asks to swap with Chloé, who declared the day off.
    await page.goto(`/animateur/${jeton}/echanges`);
    await page.getByLabel('Créneau concerné').click();
    await page.getByRole('option').first().click();
    await page.getByLabel('Échanger avec').click();
    await page.getByRole('option', { name: 'Chloé E2E' }).click();
    await page.getByRole('button', { name: 'Ajouter à la liste' }).click();
    await page.getByRole('button', { name: 'Soumettre mes demandes' }).click();

    // Stored anyway, but flagged with the business description of the break.
    await expect(page.getByText('En attente').first()).toBeVisible();
    await expect(page.getByText('poserait un problème', { exact: false })).toBeVisible();
    await expect(page.getByText('indisponible', { exact: false }).first()).toBeVisible();

    // The admin sees the same warning on the demande.
    const pageEchanges = await pageAdmin(browser, admin);
    await pageEchanges.goto('/echanges');
    await expect(pageEchanges.getByText('Signalée infaisable à la soumission', { exact: false })).toBeVisible();
    await pageEchanges.context().close();
  });

  test('un lien profond admin sans session passe par la connexion', async ({ browser }) => {
    const contexteAnonyme = await browser.newContext();
    const page = await contexteAnonyme.newPage();
    await page.goto('/animateurs');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
    await contexteAnonyme.close();
  });
});
