// The espace animateur and the foire au planning flow (issue #165), end to
// end: token boundary, planning view, submission with prevalidation, admin
// decision, and the outcome back on the animateur's side.
//
// Depuis #245 l'espace montre le plan PUBLIÉ : une décision d'admin est
// visible aussitôt dans l'onglet Échanges, mais le planning lui-même n'a
// bougé qu'une fois publié. Les deux temps sont vérifiés ici.

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  SEED,
  contexteAdmin,
  dernierCodeMailpit,
  jetonDe,
  ouvrirSelect,
  ouvrirSessionEspace,
  pageAdmin,
  publierPlanning,
  seedPlanning,
} from './support';
import { repartirDeLaReference } from './reference';

const EMAIL_ALICE = `${SEED.demandeur}@example.org`;
const EMAIL_BRUNO = `${SEED.cible}@example.org`;

let admin: APIRequestContext;
let jeton: string;

test.beforeAll(async ({ playwright }, testInfo) => {
  const baseURL = testInfo.project.use.baseURL as string;
  admin = await contexteAdmin(playwright, baseURL);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
  jeton = await jetonDe(admin, SEED.demandeur);
});

test.afterAll(async () => {
  await admin.dispose();
});

test.describe('espace animateur', () => {
  test('un jeton inconnu montre une impasse propre, sans chrome admin', async ({ page }) => {
    await page.goto('/animateur/jeton-invente');
    await expect(
      page.getByText("Ce lien n'est pas (ou plus) valide", { exact: false }),
    ).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Mon planning' })).toHaveCount(0);
  });

  test("sans session, le lien mène à l'écran du code d'accès — pas au planning", async ({
    page,
  }) => {
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

  test('le pied de page indique la version en cours', async ({ page }) => {
    // An animateur reporting a problem has no Débogage screen to quote: the
    // version has to be readable from the espace itself, on every state of it
    // — including the dead end of an unknown token.
    await page.goto('/animateur/jeton-invente');
    const pied = page.locator('.app-version-footer');
    await expect(pied).toContainText('Version');
    await expect(pied.getByRole('link')).toHaveText(/\S/);
  });

  /**
   * The targeted colleague must agree before the admin sees the demande: this
   * opens Bruno's espace (he gets an e-mail address here only — the shared
   * seed deliberately leaves him without one) and clicks his agreement.
   */
  async function accordDeBruno(browser: import('@playwright/test').Browser): Promise<void> {
    const animateurs = (await (await admin.get('/api/animateurs')).json()) as { id: string }[];
    const bruno = animateurs.find((animateur) => animateur.id === SEED.cible);
    await admin.put(`/api/animateurs/${SEED.cible}`, { data: { ...bruno, email: EMAIL_BRUNO } });
    const jetonBruno = await jetonDe(admin, SEED.cible);
    const contexteBruno = await browser.newContext();
    const pageBruno = await contexteBruno.newPage();
    await ouvrirSessionEspace(pageBruno.request, jetonBruno, EMAIL_BRUNO);
    await pageBruno.goto(`/animateur/${jetonBruno}/echanges`);
    await pageBruno.getByRole('button', { name: "Je suis d'accord" }).first().click();
    await expect(pageBruno.getByText('Votre accord est transmis', { exact: false })).toBeVisible();
    await contexteBruno.close();
  }

  test("soumettre un échange, le voir accepté par l'admin, retrouver le résultat", async ({
    page,
    browser,
  }) => {
    // Two browser contexts and a decision round trip: triple the budget.
    test.slow();
    // --- Animateur side: build then submit one demande. ---
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}/echanges`);
    await ouvrirSelect(page, 'Créneau concerné');
    await page.getByRole('option').first().click();
    await ouvrirSelect(page, 'Échanger avec');
    await page.getByRole('option', { name: 'Bruno E2E' }).click();
    await page.getByLabel('Motif').fill('rendez-vous médical');
    await page.getByRole('button', { name: 'Ajouter à la liste' }).click();
    await page.getByRole('button', { name: 'Soumettre mes demandes' }).click();
    await expect(page.getByText('En attente du collègue').first()).toBeVisible();

    // --- Colleague side: Bruno agrees, the demande enters the admin queue. ---
    await accordDeBruno(browser);

    // --- Admin side: the demande shows up and gets accepted. ---
    const contexteAdminNavigateur = await browser.newContext({
      storageState: await admin.storageState(),
    });
    const pageAdmin = await contexteAdminNavigateur.newPage();
    await pageAdmin.goto('/echanges');
    await expect(pageAdmin.getByText('Alice E2E').first()).toBeVisible();
    await pageAdmin.getByRole('button', { name: "Voir l'impact sur le planning" }).first().click();
    await expect(pageAdmin.getByText('Échange croisé', { exact: false })).toBeVisible();
    // The score delta renders as a real score, never as a raw object dump.
    await expect(pageAdmin.locator('.echanges-impact code')).toHaveText(
      /^[+-]?\d+hard \/ [+-]?\d+medium \/ [+-]?\d+soft$/,
    );
    await expect(pageAdmin.getByText('[object Object]')).toHaveCount(0);
    await pageAdmin.getByRole('button', { name: 'Accepter', exact: true }).first().click();
    // Confirmation dialog.
    await pageAdmin.getByRole('dialog').getByRole('button', { name: 'Accepter' }).click();
    await expect(pageAdmin.getByText('Acceptée').first()).toBeVisible();
    await contexteAdminNavigateur.close();

    // --- Côté animateur : la décision est visible, le planning pas encore. ---
    // L'échange a changé le plan de travail, pas celui qu'Alice a reçu
    // (issue #245) : son espace montre toujours son stand d'origine.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Acceptée').first()).toBeVisible();
    await page.getByRole('link', { name: 'Mon planning' }).click();
    await expect(page.getByText('Stand E2E un')).toBeVisible();
    await expect(page.getByText('Stand E2E deux')).toHaveCount(0);

    // --- Publier : c'est là, et seulement là, que l'espace bouge. ---
    await publierPlanning(admin);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Stand E2E deux')).toBeVisible();
  });

  test("refuser une demande transmet le motif à l'animateur", async ({ page, browser }) => {
    test.slow();
    // After the accepted swap, Alice proposes another one from her new seat.
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}/echanges`);
    await ouvrirSelect(page, 'Créneau concerné');
    await page.getByRole('option').first().click();
    await ouvrirSelect(page, 'Échanger avec');
    await page.getByRole('option', { name: 'Bruno E2E' }).click();
    await page.getByRole('button', { name: 'Ajouter à la liste' }).click();
    await page.getByRole('button', { name: 'Soumettre mes demandes' }).click();
    await expect(page.getByText('En attente du collègue').first()).toBeVisible();

    // Bruno agrees, then the admin refuses, with a reason.
    await accordDeBruno(browser);
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
    await ouvrirSelect(page, 'Créneau concerné');
    await page.getByRole('option').first().click();
    await ouvrirSelect(page, 'Échanger avec');
    await page.getByRole('option', { name: 'Bruno E2E' }).click();
    await page.getByRole('button', { name: 'Ajouter à la liste' }).click();
    await page.getByRole('button', { name: 'Soumettre mes demandes' }).click();
    await page.getByRole('button', { name: 'Annuler cette demande' }).first().click();
    await expect(page.getByText('Demande annulée.')).toBeVisible();
    await expect(page.getByText('Annulée').first()).toBeVisible();
  });

  /**
   * Le cœur de l'issue #324 : un client d'agenda n'est pas un navigateur
   * connecté. Le `GET` part donc d'un contexte neuf, sans le moindre cookie —
   * un test qui réutiliserait la session de la page ne prouverait rien.
   */
  test("l'adresse d'abonnement sert l'ICS depuis un contexte sans cookie", async ({
    page,
    browser,
  }) => {
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    const vue = await page.request.get(`/api/espace-animateur/${jeton}`);
    expect(vue.ok(), await vue.text()).toBe(true);
    const { abonnementToken } = (await vue.json()) as { abonnementToken: string };
    expect(abonnementToken).toBeTruthy();
    // Une clé distincte du jeton d'espace, et pas seulement un chemin distinct.
    expect(abonnementToken).not.toBe(jeton);

    const anonyme = await browser.newContext();
    try {
      const url = `/api/abonnements/${abonnementToken}/planning.ics`;
      // Deux fois : un abonnement revient tout seul, c'est ce qui le distingue
      // d'un téléchargement.
      for (let appel = 0; appel < 2; appel++) {
        const reponse = await anonyme.request.get(url);
        expect(reponse.status(), await reponse.text()).toBe(200);
        expect(reponse.headers()['content-type']).toContain('text/calendar');
        expect(await reponse.text()).toContain('BEGIN:VCALENDAR');
      }
      // Et ce jeton-là n'ouvre rien d'autre : l'espace le refuse.
      const espace = await anonyme.request.get(`/api/espace-animateur/${abonnementToken}`);
      expect(espace.status()).toBe(404);
    } finally {
      await anonyme.close();
    }
  });

  test("l'espace propose l'abonnement, et le téléchargement en second", async ({ page }) => {
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);
    await expect(page.getByRole('heading', { name: 'Emporter mon planning' })).toBeVisible();
    await expect(page.getByRole('link', { name: "S'abonner dans mon agenda" })).toBeVisible();
    // En complément, pas à la place : les deux fichiers ponctuels restent là.
    await expect(page.getByRole('link', { name: 'Télécharger en PDF' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Télécharger le fichier ICS' })).toBeVisible();

    // L'adresse elle-même est repliée : elle se règle une fois et occuperait,
    // dépliée, la place que le planning doit garder.
    await expect(page.getByText('/api/abonnements/')).toBeHidden();
    await page.getByRole('button', { name: "Copier l'adresse, ou la remplacer" }).click();
    await expect(page.getByText('/api/abonnements/')).toBeVisible();
  });

  /**
   * La demande d'origine, transformée en garde : le bloc d'abonnement était
   * sous TOUTES les cartes de journée, donc invisible sans dérouler l'écran
   * entier. Rien d'autre n'empêcherait qu'il y redescende un jour.
   *
   * Le test vaut surtout sur le projet `mobile` (viewport Pixel 7), mais il
   * tient aussi sur bureau — et il vérifie les deux moitiés du compromis :
   * l'abonnement est atteignable sans défiler, ET la première journée du
   * planning, ce que la personne vient lire, n'a pas été repoussée hors écran
   * pour lui faire de la place.
   */
  test("l'abonnement est visible sans défiler, sans chasser le planning", async ({ page }) => {
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);

    const abonnement = page.getByRole('link', { name: "S'abonner dans mon agenda" });
    await expect(abonnement).toBeVisible();
    const premiereJournee = page.locator('.espace-jour').first();
    await expect(premiereJournee).toBeVisible();

    // Aucun défilement n'a eu lieu, et rien n'en a provoqué.
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
    const hauteur = page.viewportSize()!.height;

    const bande = await abonnement.boundingBox();
    expect(bande, "le bouton d'abonnement n'a pas de boîte").not.toBeNull();
    expect(bande!.y + bande!.height).toBeLessThanOrEqual(hauteur);

    // Le haut de la première journée doit rester dans l'écran : remonter
    // l'abonnement ne doit pas revenir à cacher ce qu'on vient consulter.
    const journee = await premiereJournee.boundingBox();
    expect(journee, "la première journée n'a pas de boîte").not.toBeNull();
    expect(journee!.y).toBeLessThan(hauteur);
  });

  test("le périmètre du jeton : l'espace ne donne aucune session admin", async ({ page }) => {
    await ouvrirSessionEspace(page.request, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);
    const reponse = await page.request.get('/api/constraints', { maxRedirects: 0 });
    expect(reponse.status()).toBe(401);
  });
});
