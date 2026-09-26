// La pile Keycloak telle que la production la monte, de bout en bout et pour
// de vrai : un navigateur qui quitte l'application pour l'authorization
// server, un second facteur exigé du seul administrateur, le compte de secours
// fermé, un compte animateur créé par la seule création d'une fiche, et un
// serveur MCP qui n'ouvre qu'à un jeton OAuth2 d'audience correcte.
//
// Cette suite ne tourne PAS avec les autres : elle vise une pile montée avec
// OIDC_ENABLED=true et ADMIN_SECOURS_ENABLED=false, où le formulaire du compte
// de secours répond 409 — y compris celui par lequel le globalSetup de la
// suite principale photographie la base. D'où sa configuration à elle
// (playwright.oidc.config.ts) et son job de CI (e2e-keycloak, dans e2e.yml).
//
// Ce que la suite Java ne peut pas prouver et que celle-ci prouve : le code
// flow, le flow d'authentification du realm (donc le second facteur), le
// provisioning contre un vrai serveur, et la déconnexion RP-initiated.

import { expect, test, type Page } from '@playwright/test';
import {
  CLIENT_MCP,
  CLIENT_PROVISIONING,
  COMPTE_ADMIN,
  COMPTE_ANIMATRICE,
  COMPTE_SANS_ROLE,
  KEYCLOAK_URL,
  REALM,
  attendreLeCompte,
  compteKeycloak,
  connexionKeycloak,
  jetonClientCredentials,
  remplirFormulaireKeycloak,
  repondreAuSecondFacteur,
  totp,
} from './keycloak';
import { EDITION_REFERENCE, MOT_DE_PASSE_ADMIN } from './support';

/**
 * The login page, whatever Keycloak hangs off it.
 *
 * <p>RP-initiated logout comes back with a {@code state} query parameter of
 * Keycloak's own making, so anchoring on the end of the URL asserts something
 * the protocol never promised — and fails on a logout that worked perfectly.
 * What matters is the page reached.</p>
 */
const RETOUR_SUR_LA_CONNEXION = /\/login(\?|$)/;

/** Préfixe réservé à cette suite, pour que ses fiches se reconnaissent. */
const PREFIXE = 'KC-';

test.describe('connexion administrateur', () => {
  test('le second facteur est exigé, pas proposé : sans code, pas de session', async ({ page }) => {
    await page.goto('/api/auth/oidc/login?redirect=%2F');
    await remplirFormulaireKeycloak(page, COMPTE_ADMIN);

    // Mot de passe accepté, et pourtant toujours chez Keycloak : c'est la
    // condition de rôle du flow du realm qui parle.
    await expect(page.locator('#otp')).toBeVisible();
    const statut = await page.request.get('/api/auth/me');
    expect(await statut.json()).toMatchObject({ authentifie: false });
  });

  test('un mauvais code est refusé', async ({ page }) => {
    await page.goto('/api/auth/oidc/login?redirect=%2F');
    await remplirFormulaireKeycloak(page, COMPTE_ADMIN);
    await page.locator('#otp').fill('000000');
    await page.locator('#kc-login').click();

    await expect(page.locator('#otp')).toBeVisible();
    const statut = await page.request.get('/api/auth/me');
    expect(await statut.json()).toMatchObject({ authentifie: false });
  });

  test('connexion complète, rôle admin, puis déconnexion', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
    await remplirFormulaireKeycloak(page, COMPTE_ADMIN);
    await repondreAuSecondFacteur(page, COMPTE_ADMIN.secretTotp);

    await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toBeVisible();
    const statut = await page.request.get('/api/auth/me');
    expect(await statut.json()).toMatchObject({
      authentifie: true,
      roles: expect.arrayContaining(['admin']),
    });

    await page.getByRole('button', { name: 'Se déconnecter' }).click();
    await expect(page).toHaveURL(RETOUR_SUR_LA_CONNEXION);

    // Déconnexion RP-initiated : la session Keycloak est fermée elle aussi.
    // Sans cela, « se déconnecter » puis « se connecter » rouvrirait la
    // session sans rien redemander — un bouton qui ne déconnecte personne.
    await page.goto('/api/auth/oidc/login?redirect=%2F');
    await expect(page.locator('#username')).toBeVisible();
  });

  test("le compte de secours fermé, la page n'offre que Keycloak", async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Se connecter', exact: true })).toBeVisible();
    await expect(page.getByText('Compte de secours')).toHaveCount(0);
    await expect(page.getByLabel('Mot de passe')).toHaveCount(0);
  });

  /**
   * The password door, closed rather than hidden.
   *
   * The test just above checks that /login stops drawing the field; that is
   * the frontend's doing and proves nothing about the route. Quarkus compiles
   * the form login in — its switch is build-time, ADMIN_SECOURS_ENABLED is
   * not — so this posts the password this very stack is started with, and
   * expects the door to stay shut.
   */
  test('le mot de passe admin n’ouvre plus rien, même en postant le formulaire', async ({
    request,
  }) => {
    const reponse = await request.post('/j_security_check', {
      form: { j_username: 'admin', j_password: MOT_DE_PASSE_ADMIN },
      maxRedirects: 0,
    });
    expect(reponse.status()).toBe(409);
    expect(reponse.headers()['set-cookie'] ?? '').not.toContain('planning-session');

    // And nothing was opened: the session probe still says anonymous.
    const statut = await request.get('/api/auth/me');
    expect(await statut.json()).toMatchObject({ authentifie: false });
  });

  test('/api/config annonce Keycloak, et le compte de secours fermé', async ({ request }) => {
    const reponse = await request.get('/api/config');
    expect(await reponse.json()).toMatchObject({ authOidc: true, authSecours: false });
  });
});

test.describe('provisioning des comptes animateurs', () => {
  test('créer une fiche crée le compte qui ouvre son espace', async ({ page, request }) => {
    await connexionKeycloak(page, COMPTE_ADMIN);
    const email = `${PREFIXE.toLowerCase()}nouvelle@example.org`;

    const creation = await page.request.post('/api/animateurs', {
      headers: { 'X-Edition-Id': EDITION_REFERENCE },
      data: {
        prenom: 'Nouvelle',
        nom: 'Recrue',
        dateNaissance: '1995-04-12',
        email,
      },
    });
    expect(creation.ok(), await creation.text()).toBe(true);

    const compte = await attendreLeCompte(request, email);
    expect(compte.enabled).toBe(true);
    expect(compte.firstName).toBe('Nouvelle');
    expect(compte.lastName).toBe('Recrue');
  });

  /**
   * Un compte = une PERSONNE, pas une fiche. Deux fiches, la même adresse : un
   * seul compte, qui garde son mot de passe et son second facteur d'une année
   * sur l'autre. C'est aussi la seule forme possible — un realm ne peut pas à
   * la fois tolérer les doublons d'adresse et laisser s'y connecter.
   */
  test('la même adresse sur deux fiches ne crée pas un second compte', async ({
    page,
    request,
  }) => {
    await connexionKeycloak(page, COMPTE_ADMIN);
    const email = `${PREFIXE.toLowerCase()}deux-editions@example.org`;

    const premiere = await page.request.post('/api/animateurs', {
      headers: { 'X-Edition-Id': EDITION_REFERENCE },
      data: {
        prenom: 'Fidèle',
        nom: 'Bénévole',
        dateNaissance: '1988-02-03',
        email,
      },
    });
    expect(premiere.ok(), await premiere.text()).toBe(true);
    const compte = await attendreLeCompte(request, email);

    const seconde = await page.request.post('/api/animateurs', {
      headers: { 'X-Edition-Id': EDITION_REFERENCE },
      data: {
        prenom: 'Fidèle',
        nom: 'Bénévole',
        dateNaissance: '1988-02-03',
        email,
      },
    });
    expect(seconde.ok(), await seconde.text()).toBe(true);

    const apres = await compteKeycloak(request, email);
    expect(apres?.id).toBe(compte.id);
  });

  /**
   * Désactivé, jamais supprimé — et seulement quand plus aucune fiche, dans
   * aucune édition, ne porte l'adresse. Supprimer effacerait l'historique de
   * connexion de quelqu'un qui revient peut-être l'an prochain.
   */
  test('supprimer la dernière fiche désactive le compte sans le détruire', async ({
    page,
    request,
  }) => {
    await connexionKeycloak(page, COMPTE_ADMIN);
    const email = `${PREFIXE.toLowerCase()}partante@example.org`;

    const creation = await page.request.post('/api/animateurs', {
      headers: { 'X-Edition-Id': EDITION_REFERENCE },
      data: {
        prenom: 'Partante',
        nom: 'Bénévole',
        dateNaissance: '1992-09-01',
        email,
      },
    });
    expect(creation.ok(), await creation.text()).toBe(true);
    // The id is the one the application drew (ADR 0050), never one we chose.
    const { id } = ((await creation.json()) as { animateur: { id: string } }).animateur;
    await attendreLeCompte(request, email);

    const suppression = await page.request.delete(`/api/animateurs/${id}`, {
      headers: { 'X-Edition-Id': EDITION_REFERENCE },
    });
    expect(suppression.ok(), await suppression.text()).toBe(true);

    await expect
      .poll(async () => (await compteKeycloak(request, email))?.enabled, { timeout: 15_000 })
      .toBe(false);
  });
});

test.describe('import et invitations', () => {
  /**
   * Un import crée les comptes sans écrire à personne ; « Envoyer les
   * invitations » écrit ensuite à ceux-là seulement, une fois.
   */
  test("un import crée le compte sans mail, puis le bouton l'invite", async ({ page, request }) => {
    await connexionKeycloak(page, COMPTE_ADMIN);
    const email = `${PREFIXE.toLowerCase()}importee@example.org`;
    const enTetes = { 'X-Edition-Id': EDITION_REFERENCE };
    // The import refuses an edition without a single créneau: it could not
    // place an unavailability day. One, removed at the end.
    const creneau = await page.request.post('/api/creneaux', {
      headers: enTetes,
      data: { date: '2030-01-03', heureDebut: '18:00:00', heureFin: '22:00:00' },
    });
    expect(creneau.ok(), await creneau.text()).toBe(true);
    const creneauId = ((await creneau.json()) as { creneau: { id: number } }).creneau.id;

    const importation = await page.request.post('/api/animateurs/import-csv', {
      headers: enTetes,
      data: {
        fileName: 'kc-import.csv',
        content: `prénom;nom;date de naissance;email\nImportée;Bénévole;1990-05-06;${email}\n`,
        mapping: null,
        replaceAnimateurs: false,
        replaceJoursIndisponibles: false,
      },
    });
    expect(importation.ok(), await importation.text()).toBe(true);

    const compte = await attendreLeCompte(request, email);
    expect(compte.emailVerified).toBe(false);
    const etat = await page.request.get('/api/animateurs/invitations', { headers: enTetes });
    expect(((await etat.json()) as { enAttente: number }).enAttente).toBeGreaterThanOrEqual(1);

    const envoi = await page.request.post('/api/animateurs/invitations', {
      headers: enTetes,
      data: {},
    });
    expect(envoi.ok(), await envoi.text()).toBe(true);
    const bilan = (await envoi.json()) as { invites: number; echecs: number };
    expect(bilan.invites).toBeGreaterThanOrEqual(1);
    expect(bilan.echecs).toBe(0);
    expect((await compteKeycloak(request, email))?.emailVerified).toBe(true);

    const apres = await page.request.get('/api/animateurs/invitations', { headers: enTetes });
    expect(((await apres.json()) as { enAttente: number }).enAttente).toBe(0);

    await page.request.delete(`/api/creneaux/${creneauId}`, { headers: enTetes });
  });
});

test.describe('administrateurs', () => {
  /**
   * Le rôle se pose dans le realm, par le compte de service qui n'a que
   * manage-users : seul un vrai serveur dit si ce droit suffit.
   */
  test('inviter un administrateur pose le rôle admin dans le realm', async ({ page, request }) => {
    await connexionKeycloak(page, COMPTE_ADMIN);
    const email = `${PREFIXE.toLowerCase()}second-admin@example.org`;

    const invitation = await page.request.post('/api/comptes/administrateurs', {
      data: { email, nom: 'Second Admin' },
    });
    expect(invitation.ok(), await invitation.text()).toBe(true);

    const compte = await attendreLeCompte(request, email);
    const jeton = await jetonClientCredentials(request, CLIENT_PROVISIONING);
    const roles = await request.get(
      `${KEYCLOAK_URL}/admin/realms/${REALM}/users/${compte.id}/role-mappings/realm`,
      { headers: { Authorization: `Bearer ${jeton}` } },
    );
    expect(roles.ok(), await roles.text()).toBe(true);
    const noms = ((await roles.json()) as { name: string }[]).map((role) => role.name);
    expect(noms).toContain('admin');
    expect(noms).not.toContain('animateur');
  });
});

test.describe('espace animateur', () => {
  test("l'animatrice ouvre son espace avec son compte, sans code par e-mail", async ({
    page,
    browser,
  }) => {
    const jeton = await jetonDeLAnimatrice(page);

    const contexte = await browser.newContext();
    const pageAnimatrice = await contexte.newPage();
    // Le lien seul n'ouvre rien : il n'a jamais suffi.
    await pageAnimatrice.goto(`/animateur/${jeton}`);
    await expect(
      pageAnimatrice.getByRole('button', { name: 'Se connecter avec mon compte' }),
    ).toBeVisible();
    // Et l'écran du code a disparu : aucun champ à remplir.
    await expect(pageAnimatrice.getByRole('textbox')).toHaveCount(0);

    await connexionKeycloak(pageAnimatrice, COMPTE_ANIMATRICE, `/animateur/${jeton}`);
    await expect(pageAnimatrice.getByRole('link', { name: 'Mon planning' })).toBeVisible();
    await contexte.close();
  });

  test("un animateur authentifié n'atteint pas l'administration", async ({ page, browser }) => {
    const jeton = await jetonDeLAnimatrice(page);

    const contexte = await browser.newContext();
    const pageAnimatrice = await contexte.newPage();
    await connexionKeycloak(pageAnimatrice, COMPTE_ANIMATRICE, `/animateur/${jeton}`);

    // 403 et non 401 : l'identité est reconnue, c'est le rôle qui manque —
    // noms, dates de naissance, mineurs compris, restent fermés.
    const referentiel = await pageAnimatrice.request.get('/api/animateurs', {
      headers: { 'X-Edition-Id': EDITION_REFERENCE },
    });
    expect(referentiel.status()).toBe(403);

    // Et l'interface le dit, au lieu d'empiler les panneaux en erreur : un
    // signet vers l'administration ramène sur /login avec la raison, et le
    // seul geste qui change quelque chose — se déconnecter.
    await pageAnimatrice.goto('/');
    await expect(pageAnimatrice).toHaveURL(RETOUR_SUR_LA_CONNEXION);
    await expect(
      pageAnimatrice.getByText("ce compte n'a pas accès à l'administration", { exact: false }),
    ).toBeVisible();
    await expect(pageAnimatrice.getByRole('button', { name: 'Se déconnecter' })).toBeVisible();
    await contexte.close();
  });

  /**
   * Le moindre privilège, vérifié de bout en bout : un compte parfaitement
   * valide du realm, qui ne porte que son rôle ordinaire, n'ouvre rien. C'est
   * ce qui garantit qu'un rôle ajouté demain arrive sans accès plutôt qu'avec
   * celui d'un animateur.
   */
  test("un compte sans rôle n'ouvre ni l'administration ni un espace", async ({
    page,
    browser,
  }) => {
    const jeton = await jetonDeLAnimatrice(page);

    const contexte = await browser.newContext();
    const pageSansRole = await contexte.newPage();
    await connexionKeycloak(pageSansRole, COMPTE_SANS_ROLE, `/animateur/${jeton}`);

    // L'espace refuse, et le dit — au lieu de reproposer une connexion que la
    // personne vient de réussir.
    await expect(
      pageSansRole.getByText("ce compte n'ouvre pas cet espace", { exact: false }),
    ).toBeVisible();
    await expect(pageSansRole.getByRole('button', { name: 'Se déconnecter' })).toBeVisible();
    await expect(
      pageSansRole.getByRole('button', { name: 'Se connecter avec mon compte' }),
    ).toHaveCount(0);

    // Et l'administration aussi.
    await pageSansRole.goto('/');
    await expect(pageSansRole).toHaveURL(RETOUR_SUR_LA_CONNEXION);
    await expect(
      pageSansRole.getByText("ce compte n'a pas accès à l'administration", { exact: false }),
    ).toBeVisible();
    await contexte.close();
  });

  /** La fiche de l'animatrice de démonstration, créée à la volée si besoin. */
  async function jetonDeLAnimatrice(page: Page): Promise<string> {
    await connexionKeycloak(page, COMPTE_ADMIN);
    const creation = await page.request.post('/api/animateurs', {
      headers: { 'X-Edition-Id': EDITION_REFERENCE },
      data: {
        prenom: 'Marie',
        nom: 'Dupont',
        dateNaissance: '1990-06-15',
        email: COMPTE_ANIMATRICE.email,
      },
    });
    expect(creation.ok(), await creation.text()).toBe(true);
    // Found by the id the application drew for it (ADR 0050).
    const { id } = ((await creation.json()) as { animateur: { id: string } }).animateur;
    const animateurs = await page.request.get('/api/animateurs', {
      headers: { 'X-Edition-Id': EDITION_REFERENCE },
    });
    const fiche = ((await animateurs.json()) as { id: string; accessToken?: string }[]).find(
      (candidat) => candidat.id === id,
    );
    expect(fiche?.accessToken, 'la fiche de démonstration doit porter un jeton').toBeTruthy();
    return fiche!.accessToken as string;
  }
});

test.describe('serveur MCP en OAuth2', () => {
  test("le challenge désigne l'authorization server (RFC 9728)", async ({ request }) => {
    const reponse = await request.post('/mcp', { failOnStatusCode: false });
    expect(reponse.status()).toBe(401);

    const challenge = reponse.headers()['www-authenticate'] ?? '';
    const url = /resource_metadata="([^"]+)"/.exec(challenge)?.[1];
    expect(url, `le challenge doit porter une URL de métadonnées : « ${challenge} »`).toBeTruthy();

    const metadonnees = await request.get(new URL(url!).pathname);
    expect(metadonnees.status()).toBe(200);
    expect(await metadonnees.text()).toContain('/realms/');
  });

  test('un jeton client_credentials du realm ouvre les outils', async ({ request }) => {
    const jeton = await jetonClientCredentials(request, CLIENT_MCP);
    const reponse = await request.post('/mcp', {
      headers: { Authorization: `Bearer ${jeton}` },
      failOnStatusCode: false,
    });
    expect(reponse.status()).not.toBe(401);
    expect(reponse.status()).not.toBe(403);
  });

  /**
   * Un jeton parfaitement valide du même realm, et qui n'ouvre rien : le
   * compte de service du provisioning n'est pas un client MCP, son jeton ne
   * porte pas l'audience `planning-mcp`. Refusé à l'authentification (401),
   * comme l'exige RFC 6750 pour une audience étrangère — pas un simple droit
   * manquant.
   */
  test("un jeton du realm émis pour autre chose n'ouvre rien", async ({ request }) => {
    const jeton = await jetonClientCredentials(request, CLIENT_PROVISIONING);
    const reponse = await request.post('/mcp', {
      headers: { Authorization: `Bearer ${jeton}` },
      failOnStatusCode: false,
    });
    expect(reponse.status()).toBe(401);
  });
});

test.describe('second facteur', () => {
  /** Le calcul TOTP lui-même, sur les vecteurs de la RFC 6238 (clé « 12345678901234567890 »). */
  test('le générateur TOTP suit la RFC 6238', () => {
    expect(totp('12345678901234567890', 59_000)).toBe('287082');
    expect(totp('12345678901234567890', 1_111_111_109_000)).toBe('081804');
    expect(totp('12345678901234567890', 1_234_567_890_000)).toBe('005924');
  });
});
