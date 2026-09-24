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
  jetonDe,
  ongletEspace,
  ouvrirSelect,
  ouvrirSessionEspace,
  pageAdmin,
  publierPlanning,
  seedPlanning,
} from './support';
import { assurerCompteAnimateur, remplirFormulaireKeycloak } from './keycloak';
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
  await ouvrirSessionEspace(pageBruno, jetonBruno, EMAIL_BRUNO);
  await pageBruno.goto(`/animateur/${jetonBruno}/echanges`);
  await pageBruno.getByRole('button', { name: "Je suis d'accord" }).first().click();
  await expect(pageBruno.getByText('Votre accord est transmis', { exact: false })).toBeVisible();
  await contexteBruno.close();
}

test.describe('espace animateur', () => {
  test('un jeton inconnu montre une impasse propre, sans chrome admin', async ({ page }) => {
    await page.goto('/animateur/jeton-invente');
    await expect(
      page.getByText("Ce lien n'est pas (ou plus) valide", { exact: false }),
    ).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Mon planning' })).toHaveCount(0);
  });

  test("sans session, le lien mène à l'écran d'accès — pas au planning", async ({ page }) => {
    await page.goto(`/animateur/${jeton}`);
    await expect(page.getByText('Accès à votre espace')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Se connecter avec mon compte' })).toBeVisible();
    // The e-mail code is gone for good: no field to type anything into.
    await expect(page.getByRole('textbox')).toHaveCount(0);
    await expect(page.getByText('Stand E2E un')).toHaveCount(0);
    // The API itself refuses: the screen is not a mere curtain.
    const reponse = await page.request.get(`/api/espace-animateur/${jeton}`);
    expect(reponse.status()).toBe(401);
  });

  test("le compte Keycloak ouvre l'espace depuis l'écran d'accès, et y revient", async ({
    page,
  }) => {
    const compte = await assurerCompteAnimateur(page.request, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);
    await page.getByRole('button', { name: 'Se connecter avec mon compte' }).click();
    // Keycloak's own form, then back on this very espace — the token in the
    // URL survived the round trip through the authorization server.
    await remplirFormulaireKeycloak(page, compte);
    await expect(page).toHaveURL(new RegExp(`/animateur/${jeton}(\\?|$)`));
    await expect(page.getByText('Alice E2E')).toBeVisible();
    await expect(page.getByText('Stand E2E un')).toBeVisible();
  });

  /*
   * Le socle d'accessibilité de l'espace (RGAA 7.5, 12.7, 9.1). Joué aussi dans
   * le projet `mobile` : c'est le viewport réel de ceux qui ouvrent ce lien.
   */
  test("le lien d'évitement est le premier arrêt, et chaque onglet rend le focus à la page", async ({
    page,
  }) => {
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);
    await expect(page.getByText('Alice E2E')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Mon planning' })).toHaveCount(1);

    await page.keyboard.press('Tab');
    const evitement = page.getByRole('link', { name: 'Aller au contenu' });
    await expect(evitement).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('main#contenu')).toBeFocused();

    await page.getByRole('link', { name: 'Mes échanges' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Mes échanges' })).toBeVisible();
    await expect(page.locator('main#contenu')).toBeFocused();
  });

  test('le jeton ouvre le planning personnel, sans navigation admin', async ({ page }) => {
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
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
    // The version link is the one opening a new window: the footer also leads
    // to the accessibility statement.
    await expect(pied.locator('a[target="_blank"]')).toHaveText(/\S/);
    await expect(pied.getByRole('link', { name: 'Accessibilité' })).toHaveAttribute(
      'href',
      '/declaration-accessibilite',
    );
  });

  test("soumettre un échange, le voir accepté par l'admin, retrouver le résultat", async ({
    page,
    browser,
  }) => {
    // Two browser contexts and a decision round trip: triple the budget.
    test.slow();
    // --- Animateur side: build then submit one demande. ---
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
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
    // Confirmation dialog — and the decision itself, awaited on the wire: the
    // list already reads « Acceptée » for the colleague's own accord, so the
    // text alone would let the admin context close before the request left.
    const decision = pageAdmin.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/api\/echanges\/[^/]+\/acceptation$/.test(response.url()),
    );
    await pageAdmin.getByRole('dialog').getByRole('button', { name: 'Accepter' }).click();
    expect((await decision).ok()).toBe(true);
    await expect(pageAdmin.getByText('Acceptée').first()).toBeVisible();
    await contexteAdminNavigateur.close();

    // --- Côté animateur : la décision est visible, le planning pas encore. ---
    // L'échange a changé le plan de travail, pas celui qu'Alice a reçu
    // (issue #245) : son espace montre toujours son stand d'origine.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Acceptée').first()).toBeVisible();
    await page.getByRole('link', { name: 'Mon planning' }).click();
    // Sur les cartes de journée, et pas n'importe où sur la page : depuis
    // #532, le bandeau « Ce qui a changé pour vous » rejoue les phrases de la
    // publication, qui nomment le stand elles aussi. Ce qui se vérifie ici est
    // le planning, pas ce qu'on en a dit.
    // L'onglet Jour ouvre sur la première journée hors événement, celle qui
    // porte justement le poste d'Alice.
    const journees = page.locator('.espace-journee');
    await expect(journees.getByText('Stand E2E un')).toBeVisible();
    await expect(journees.getByText('Stand E2E deux')).toHaveCount(0);

    // --- Publier : c'est là, et seulement là, que l'espace bouge. ---
    await publierPlanning(admin);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(journees.getByText('Stand E2E deux')).toBeVisible();
  });

  test("refuser une demande transmet le motif à l'animateur", async ({ page, browser }) => {
    test.slow();
    // After the accepted swap, Alice proposes another one from her new seat.
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
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
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
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
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
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
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
    // La bande est portée sous les trois onglets : on la trouve là où l'on
    // arrive, sans changer d'onglet pour emporter son planning.
    await page.goto(`/animateur/${jeton}`);
    await expect(page.getByRole('heading', { name: 'Emporter mon planning' })).toBeVisible();
    await expect(page.getByRole('link', { name: "S'abonner dans mon agenda" })).toBeVisible();
    // En complément, pas à la place : le PDF sous ses deux mises en page.
    await expect(page.getByRole('link', { name: 'Livret PDF' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Feuille A4' })).toBeVisible();
    // Le fichier ICS n'est pas une quatrième sortie : il attend dans le
    // dépliant, avec ce qu'il faut savoir avant de le prendre.
    await expect(page.getByRole('link', { name: 'Fichier ICS' })).toBeHidden();

    // L'adresse elle-même est repliée : elle se règle une fois et occuperait,
    // dépliée, la place que le planning doit garder.
    await expect(page.getByText('/api/abonnements/')).toBeHidden();
    await page.getByRole('button', { name: "Copier l'adresse, ou la remplacer" }).click();
    await expect(page.getByText('/api/abonnements/')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Fichier ICS' })).toBeVisible();
    await expect(page.getByText('ne suivra aucune republication')).toBeVisible();
  });

  /**
   * La demande d'origine, transformée en garde : le bloc d'abonnement était
   * sous TOUTES les cartes de journée, donc invisible sans dérouler l'écran
   * entier. Depuis #615 ce n'est plus un problème de hauteur mais d'onglet, et
   * la garde le suit : chaque onglet doit tenir ce qu'il promet dès l'ouverture.
   *
   * Le test vaut surtout sur le projet `mobile` (viewport Pixel 7), mais il
   * tient aussi sur bureau — et il vérifie les deux moitiés du compromis :
   * la journée est là sans défiler, et l'abonnement l'est aussi, un onglet
   * plus loin, sans que l'un ait chassé l'autre.
   */
  test('chaque onglet tient sa promesse sans défiler', async ({ page }) => {
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);

    const hauteur = page.viewportSize()!.height;
    const journee = page.locator('.espace-journee').first();
    await expect(journee).toBeVisible();
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
    const boiteJournee = await journee.boundingBox();
    expect(boiteJournee, "la journée n'a pas de boîte").not.toBeNull();
    expect(boiteJournee!.y).toBeLessThan(hauteur);

    // Et la page elle-même ne défile pas latéralement : seule la bande le fait.
    const debordement = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(debordement).toBeLessThanOrEqual(0);

    await ongletEspace(page, 'Aperçu').click();
    const abonnement = page.getByRole('link', { name: "S'abonner dans mon agenda" });
    await expect(abonnement).toBeVisible();
    const bande = await abonnement.boundingBox();
    expect(bande, "le bouton d'abonnement n'a pas de boîte").not.toBeNull();
    expect(bande!.y + bande!.height).toBeLessThanOrEqual(hauteur);
  });

  /*
   * Le gabarit de ce projet est un Pixel 7, soit 412 px : la barre d'onglets y
   * tenait alors qu'elle imposait 340 px de large quoi qu'il arrive, et la
   * page débordait de côté sur tout téléphone de 360 px ou moins. Une page
   * décalée latéralement fait tomber le doigt à côté de ce qu'il vise — c'est
   * ainsi que le menu de la barre du haut devenait inatteignable. On mesure
   * donc au plus étroit qui se vende, et sur les trois onglets.
   */
  /*
   * Le jeu amorcé ici tient sur une journée, et c'est précisément ce qui a
   * laissé passer le défaut : la bande de jours ne débordait pas. Une édition
   * réelle en compte quinze, et `.espace-page` — dimensionné sur son contenu
   * parce que ses marges automatiques annulaient l'étirement — s'élargissait
   * alors jusqu'à son `max-width` de 56 rem au lieu de laisser la bande
   * défiler. Toute la page était coupée à droite, menu de la barre compris.
   */
  test('une bande de quinze journées défile sans élargir la page', async ({ page }) => {
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
    await page.route(`**/api/espace-animateur/${jeton}`, async (route) => {
      const reponse = await route.fetch();
      const corps = await reponse.json();
      const modele = (corps.postes ?? [])[0];
      if (modele) {
        corps.postes = Array.from({ length: 15 }, (_, index) => ({
          ...modele,
          creneauId: `bande-${index}`,
          date: `2026-07-${String(index + 1).padStart(2, '0')}`,
        }));
      }
      await route.fulfill({ response: reponse, json: corps });
    });
    await page.setViewportSize({ width: 360, height: 760 });
    await page.goto(`/animateur/${jeton}`);
    await expect(page.locator('.espace-bande')).toBeVisible();

    const debord = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(debord, 'la page ne doit pas défiler de côté').toBeLessThanOrEqual(0);
    // La bande, elle, défile : c'est la seule à qui on l'accorde.
    const bandeDefile = await page.evaluate(() => {
      const bande = document.querySelector('.espace-bande')!;
      return bande.scrollWidth > bande.clientWidth;
    });
    expect(bandeDefile, 'la bande de jours doit défiler en elle-même').toBe(true);
  });

  test('aucun onglet ne déborde latéralement, même sur un écran étroit', async ({ page }) => {
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
    for (const largeur of [320, 360]) {
      await page.setViewportSize({ width: largeur, height: 780 });
      await page.goto(`/animateur/${jeton}`);
      for (const onglet of ['Jour', 'Aperçu', 'Coéquipiers']) {
        await ongletEspace(page, onglet).click();
        await expect(page.getByRole('heading', { name: 'Emporter mon planning' })).toBeVisible();
        const debord = await page.evaluate(
          () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
        );
        expect(debord, `onglet ${onglet} à ${largeur} px`).toBeLessThanOrEqual(0);
      }
    }
  });

  /** Les trois onglets, et l'action de l'espace sous les trois. */
  test('les trois onglets répondent à leurs trois questions', async ({ page }) => {
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);

    await expect(page.locator('.espace-bande-jour').first()).toBeVisible();
    // Le stand de la journée ouverte, sans le nommer : un échange accepté plus
    // haut dans ce fichier déplace Alice d'un stand à l'autre — ces tests
    // partagent un seul `beforeAll` — et la promesse de l'onglet est « où
    // est-ce que je vais », pas « sur quel stand exactement ».
    await expect(page.locator('.espace-poste-carte').first()).toContainText(/Stand E2E/);

    await ongletEspace(page, 'Aperçu').click();
    await expect(page.locator('.espace-frise-ligne').first()).toBeVisible();
    await expect(page).toHaveURL(/onglet=apercu/);

    await ongletEspace(page, 'Coéquipiers').click();
    await expect(page.getByLabel('Chercher un nom')).toBeVisible();

    // L'action de cet espace reste à portée de pouce sous les trois.
    await expect(page.getByRole('link', { name: 'Proposer un échange de créneau' })).toBeVisible();
  });

  test("le périmètre du jeton : l'espace ne donne aucune session admin", async ({ page }) => {
    await ouvrirSessionEspace(page, jeton, EMAIL_ALICE);
    await page.goto(`/animateur/${jeton}`);
    const reponse = await page.request.get('/api/constraints', { maxRedirects: 0 });
    // 403, not 401: the Keycloak session is recognised, it is the admin role
    // that is missing.
    expect(reponse.status()).toBe(403);
  });
});
