// Edge cases of the foire au planning perimeter (issue #165): token
// revocation seen from the browser, the prevalidation verdict travelling to
// both sides, and admin deep links without a session.

import { APIRequestContext, expect, test } from '@playwright/test';
import { SEED, contexteAdmin, jetonDe, ouvrirSessionEspace, pageAdmin, seedPlanning, ouvrirSelect } from './support';

const EMAIL_ALICE = `${SEED.demandeur}@example.org`;

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
    await ouvrirSessionEspace(page.request, ancienJeton, EMAIL_ALICE);

    // The old link works…
    await page.goto(`/animateur/${ancienJeton}`);
    await expect(page.getByText('Alice E2E')).toBeVisible();

    // …until the admin rotates the token.
    const rotation = await admin.post(`/api/animateurs/${SEED.demandeur}/token`, {
      headers: { 'Content-Type': 'application/json' }
    });
    expect(rotation.ok(), await rotation.text()).toBe(true);
    const { token: nouveauJeton } = (await rotation.json()) as { token: string };
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
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);

    // Alice asks to swap with Chloé, who declared the day off.
    await page.goto(`/animateur/${jeton}/echanges`);
    await ouvrirSelect(page, 'Créneau concerné');
    await page.getByRole('option').first().click();
    await ouvrirSelect(page, 'Échanger avec');
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

  test("fermer la foire rend l'espace consultable seulement, téléchargements compris", async ({ page, browser }) => {
    test.slow();
    await seedPlanning(admin);
    const jeton = await jetonDe(admin, SEED.demandeur);
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);

    // The admin closes the foire from the Échanges screen.
    const pageEchanges = await pageAdmin(browser, admin);
    await pageEchanges.goto('/echanges');
    const interrupteur = pageEchanges.getByRole('switch');
    await expect(interrupteur).toBeVisible();
    if ((await interrupteur.getAttribute('aria-checked')) === 'true') {
      await interrupteur.click();
    }
    // The status line, not the transient snack bar carrying the same words.
    await expect(pageEchanges.locator('.echanges-foire-etat')).toContainText('Fermée');

    // The animateur can still browse — but not submit: no form, a clear banner.
    await page.goto(`/animateur/${jeton}/echanges`);
    await expect(page.getByText('La foire au planning est fermée', { exact: false })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Ajouter à la liste' })).toHaveCount(0);

    // The API refuses too: closing is enforced server-side.
    const refus = await page.request.post(`/api/espace-animateur/${jeton}/demandes`, {
      data: [{ creneauId: SEED.creneauId, standId: SEED.standDemandeur, cibleId: SEED.cible, motif: null }]
    });
    expect(refus.status()).toBe(400);

    // The planning stays consultable and downloadable (PDF + ICS).
    await page.getByRole('link', { name: 'Mon planning' }).click();
    await expect(page.getByRole('link', { name: 'Télécharger en PDF' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Ajouter à mon agenda (ICS)' })).toBeVisible();
    const pdf = await page.request.get(`/api/espace-animateur/${jeton}/planning.pdf`);
    expect(pdf.status()).toBe(200);
    expect((await pdf.body()).subarray(0, 5).toString()).toBe('%PDF-');
    const ics = await page.request.get(`/api/espace-animateur/${jeton}/planning.ics`);
    expect(ics.status()).toBe(200);
    expect(await ics.text()).toContain('BEGIN:VCALENDAR');

    // Reopening from the same switch restores the submission form.
    await interrupteur.click();
    await expect(pageEchanges.locator('.echanges-foire-etat')).toContainText('Ouverte');
    await pageEchanges.context().close();
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('link', { name: 'Mes échanges' }).click();
    await expect(page.getByRole('button', { name: 'Ajouter à la liste' })).toBeVisible();
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
