// Plumbing of the Keycloak side of the suites: the sign-in round trip through
// the real authorization server, the second factor computed rather than typed,
// the accounts the espace specs sign in with, and the Keycloak APIs the
// assertions need (token endpoint, admin users).
//
// Everything here talks to the Keycloak the stack runs with the realm
// `docker/keycloak/realm-planning.json` imports. The defaults are that realm's
// and the dev docker-compose's; the environment variables exist so the same
// suites can be pointed at a CI container or a staging realm without editing
// the file.

import { APIRequestContext, Page, expect } from '@playwright/test';
import { createHmac } from 'node:crypto';

/**
 * Same URL from the browser and from the application — the token issuer must
 * be one. `keycloak:8081` under docker-compose (with its /etc/hosts line, see
 * docs/keycloak.md); CI runs both on the host and sets `localhost:8081`.
 */
export const KEYCLOAK_URL = process.env['E2E_KEYCLOAK_URL'] ?? 'http://keycloak:8081';
export const REALM = process.env['E2E_KEYCLOAK_REALM'] ?? 'planning';

/**
 * The Keycloak server's own administrator (master realm), the one the
 * container is bootstrapped with. Used only to create the accounts the espace
 * specs sign in with: a fixture, not the thing under test — the provisioning
 * the application does is asserted with its own service account, below.
 */
const ADMINISTRATEUR_KEYCLOAK = {
  utilisateur: process.env['E2E_KEYCLOAK_ADMIN'] ?? 'admin',
  motDePasse: process.env['E2E_KEYCLOAK_ADMIN_PASSWORD'] ?? 'dev-keycloak-Wq2fT7xM',
};

export const COMPTE_ADMIN = {
  email: process.env['E2E_KC_ADMIN_EMAIL'] ?? 'admin@planning.local',
  motDePasse: process.env['E2E_KC_ADMIN_PASSWORD'] ?? 'dev-admin-Zc6qL1yV',
  /**
   * The demo admin's TOTP secret, seeded by the realm import so the suite can
   * compute a valid code instead of asking a human for their phone.
   *
   * <p>A fixed second-factor secret in a versioned file would be indefensible
   * anywhere but here: it belongs to a demonstration account, in a realm that
   * is rebuilt from that same file on every `docker compose down -v`, on a
   * stack that holds no real data. Production accounts are created by the
   * Ansible playbook, which seeds no credential at all — each person gets an
   * invitation and enrols their own authenticator.</p>
   */
  secretTotp: process.env['E2E_KC_ADMIN_TOTP_SECRET'] ?? 'planningequipesotp01',
};

export const COMPTE_ANIMATRICE = {
  email: process.env['E2E_KC_ANIMATEUR_EMAIL'] ?? 'marie.dupont@example.org',
  motDePasse: process.env['E2E_KC_ANIMATEUR_PASSWORD'] ?? 'dev-animateur-Pn3vK9tR',
};

/**
 * Un compte qui ne porte que le rôle ordinaire du realm. Il existe pour qu'une
 * spec puisse vérifier que « connecté » n'ouvre rien — ni l'administration, ni
 * un espace animateur.
 */
export const COMPTE_SANS_ROLE = {
  email: process.env['E2E_KC_SANS_ROLE_EMAIL'] ?? 'sans.role@example.org',
  motDePasse: process.env['E2E_KC_SANS_ROLE_PASSWORD'] ?? 'dev-animateur-Pn3vK9tR',
};

export const CLIENT_MCP = {
  id: process.env['E2E_KC_MCP_CLIENT_ID'] ?? 'planning-mcp-client',
  secret: process.env['E2E_KC_MCP_CLIENT_SECRET'] ?? 'dev-planning-mcp-rB7nX3wD',
};

export const CLIENT_PROVISIONING = {
  id: process.env['E2E_KC_PROVISIONING_CLIENT_ID'] ?? 'planning-provisioning',
  secret: process.env['E2E_KC_PROVISIONING_CLIENT_SECRET'] ?? 'dev-planning-provisioning-yH5mL8sK',
};

/** Password of every animateur account the suites create for themselves. */
const MOT_DE_PASSE_ANIMATEUR_E2E =
  process.env['E2E_KC_ANIMATEUR_E2E_PASSWORD'] ?? 'e2e-animateur-Tq8mW3zP';

/** The realm role an espace demands, next to the matching verified e-mail. */
const ROLE_ANIMATEUR = process.env['E2E_KC_ANIMATEUR_ROLE'] ?? 'animateur';

/**
 * RFC 6238, six digits, thirty seconds, HMAC-SHA1 — the realm's `otpPolicy*`.
 *
 * Hand-rolled rather than pulled from npm: it is twelve lines, the suite must
 * not grow a dependency for them, and a wrong implementation fails loudly on
 * the very first run instead of hiding.
 *
 * Keycloak stores the secret as a plain string and HMACs its raw bytes (what
 * it Base32-encodes is only the QR code it shows a phone), so the seeded
 * secret is used here exactly as written in the realm file.
 */
export function totp(secret: string, instant: number = Date.now()): string {
  const compteur = Buffer.alloc(8);
  compteur.writeBigInt64BE(BigInt(Math.floor(instant / 1000 / 30)));
  const empreinte = createHmac('sha1', Buffer.from(secret, 'utf8')).update(compteur).digest();
  const decalage = empreinte[empreinte.length - 1] & 0x0f;
  const binaire =
    ((empreinte[decalage] & 0x7f) << 24) |
    ((empreinte[decalage + 1] & 0xff) << 16) |
    ((empreinte[decalage + 2] & 0xff) << 8) |
    (empreinte[decalage + 3] & 0xff);
  return String(binaire % 1_000_000).padStart(6, '0');
}

/**
 * Signs in through the real authorization server and comes back to `retour`.
 *
 * <p>No shortcut: the browser really leaves the application for Keycloak,
 * really posts the login form, really answers the OTP challenge when the realm
 * raises one, and really comes back with the session cookie Quarkus set. That
 * round trip is the thing under test — the Java suite stands in for it with a
 * bearer token, precisely because it cannot perform it.</p>
 *
 * <p>A browser context already signed in at Keycloak is sent straight back
 * (its SSO cookie): no form is shown, and none is filled.</p>
 *
 * @param secretTotp when given, the second factor is answered. When omitted,
 *   the caller is asserting that no second factor is asked for.
 */
export async function connexionKeycloak(
  page: Page,
  compte: { email: string; motDePasse: string; secretTotp?: string },
  retour = '/',
): Promise<void> {
  await page.goto(`/api/auth/oidc/login?redirect=${encodeURIComponent(retour)}`);
  if (!page.url().startsWith(KEYCLOAK_URL)) {
    return;
  }
  await remplirFormulaireKeycloak(page, compte);
  if (compte.secretTotp) {
    await repondreAuSecondFacteur(page, compte.secretTotp);
  }
  await page.waitForURL((url) => !url.href.startsWith(KEYCLOAK_URL));
}

/** The Keycloak login form itself, by its stable field names rather than by label. */
export async function remplirFormulaireKeycloak(
  page: Page,
  compte: { email: string; motDePasse: string },
): Promise<void> {
  await page.waitForURL(new RegExp(`^${echapper(KEYCLOAK_URL)}/realms/${REALM}/`));
  await page.locator('#username').fill(compte.email);

  // Deux écrans, pas un : le realm demande l'adresse d'abord, puis la méthode.
  // C'est ce qui permet d'offrir passkey, code par e-mail ou mot de passe selon
  // ce dont le compte dispose — un formulaire unique devrait les proposer tous
  // à quelqu'un qui n'en a aucun.
  //
  // La condition plutôt qu'un second clic inconditionnel : Keycloak regroupe
  // adresse et mot de passe sur une seule page quand le flow est celui d'avant,
  // et cette suite doit passer sur les deux — sinon corriger le realm et
  // corriger les tests deviennent le même geste, et plus rien ne les arbitre.
  if (!(await page.locator('#password').isVisible())) {
    await page.locator('#kc-login').click();
  }

  await page.locator('#password').fill(compte.motDePasse);
  await page.locator('#kc-login').click();
}

/** Dernier code accepté par compte, pour ne jamais resoumettre le même. */
const codesDejaUtilises = new Map<string, string>();

/** Attend le début de la fenêtre TOTP suivante, plus une marge. */
async function fenetreSuivante(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 30_000 - (Date.now() % 30_000) + 500));
}

/**
 * Answers the OTP screen with a code Keycloak has not already consumed.
 *
 * <p>Two traps, both of which look like an unreliable test and are neither.</p>
 *
 * <p><b>La fenêtre qui se ferme.</b> Un code calculé à la seconde 29 et soumis
 * à la seconde 31 est un code valide pour une fenêtre close — un raté sur
 * trente.</p>
 *
 * <p><b>Le code déjà consommé.</b> Keycloak retient le compteur du dernier code
 * accepté et refuse tout code dont le compteur ne lui est pas strictement
 * supérieur : c'est sa protection contre le rejeu. Deux connexions du même
 * compte dans la même fenêtre de trente secondes calculent donc le <i>même</i>
 * code, et la seconde est refusée alors qu'elle est parfaitement correcte.
 * D'où la mémoire ci-dessous : on attend la fenêtre suivante <b>avant</b> de
 * soumettre plutôt que de réessayer après un refus.</p>
 */
export async function repondreAuSecondFacteur(page: Page, secret: string): Promise<void> {
  const champ = page.locator('#otp');
  await expect(champ, "le realm doit exiger un second facteur de l'administrateur").toBeVisible();

  if (codesDejaUtilises.get(secret) === totp(secret)) {
    await fenetreSuivante();
  }
  const code = totp(secret);
  codesDejaUtilises.set(secret, code);
  await champ.fill(code);
  await page.locator('#kc-login').click();

  // Filet pour la fenêtre franchie pendant la soumission : si l'écran du code
  // est toujours là, la fenêtre suivante donne un code neuf.
  if (await champ.isVisible().catch(() => false)) {
    await fenetreSuivante();
    const suivant = totp(secret);
    codesDejaUtilises.set(secret, suivant);
    await champ.fill(suivant);
    await page.locator('#kc-login').click();
  }
}

/** A machine-to-machine access token, the way a scripted MCP client gets one. */
export async function jetonClientCredentials(
  requeteur: APIRequestContext,
  client: { id: string; secret: string },
): Promise<string> {
  const reponse = await requeteur.post(
    `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`,
    {
      form: {
        grant_type: 'client_credentials',
        client_id: client.id,
        client_secret: client.secret,
      },
    },
  );
  expect(reponse.ok(), await reponse.text()).toBe(true);
  return ((await reponse.json()) as { access_token: string }).access_token;
}

export interface CompteKeycloak {
  id: string;
  username: string;
  email: string;
  enabled: boolean;
  emailVerified?: boolean;
  firstName?: string;
  lastName?: string;
  requiredActions?: string[];
}

/**
 * The realm's account for one address, read through the admin API with the
 * same service account the application provisions with — so the assertion
 * proves what an operator would see in the console, not what the application
 * believes it wrote.
 */
export async function compteKeycloak(
  requeteur: APIRequestContext,
  email: string,
): Promise<CompteKeycloak | null> {
  const jeton = await jetonClientCredentials(requeteur, CLIENT_PROVISIONING);
  return chercherCompte(requeteur, jeton, email);
}

/** Polls until the account appears: the provisioning call and the assertion are two round trips. */
export async function attendreLeCompte(
  requeteur: APIRequestContext,
  email: string,
): Promise<CompteKeycloak> {
  for (let essai = 0; essai < 40; essai++) {
    const compte = await compteKeycloak(requeteur, email);
    if (compte) {
      return compte;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Aucun compte Keycloak pour ${email} dans le realm ${REALM}`);
}

/** Adresses dont le compte a déjà été préparé pendant cette exécution. */
const comptesPrets = new Set<string>();

/**
 * Makes sure `email` has an account an animateur can sign in with — enabled,
 * address verified, the `animateur` realm role, a known password and no
 * pending required action — and returns its credentials.
 *
 * <p>The specs seed their fiches through `/api/database/import`, which
 * provisions nobody, and even an application-provisioned account carries no
 * password (its owner gets an invitation). So the suite prepares the account
 * itself, as Keycloak's own administrator would: a fixture, the same way the
 * database dump is one. Idempotent — an existing account is brought back to
 * that state, which also undoes a spec that disabled it.</p>
 *
 * <p>The e-mail is stored lower-cased by Keycloak: the fiche's `E2E-A@…` and
 * the account's `e2e-a@…` must be the same person for the server.</p>
 */
export async function assurerCompteAnimateur(
  requeteur: APIRequestContext,
  email: string,
): Promise<{ email: string; motDePasse: string }> {
  const identifiants = { email, motDePasse: MOT_DE_PASSE_ANIMATEUR_E2E };
  if (comptesPrets.has(email.toLowerCase())) {
    return identifiants;
  }
  const jeton = await jetonAdministrateurKeycloak(requeteur);
  const entetes = { Authorization: `Bearer ${jeton}` };
  const base = `${KEYCLOAK_URL}/admin/realms/${REALM}`;

  let compte = await chercherCompte(requeteur, jeton, email);
  if (!compte) {
    const creation = await requeteur.post(`${base}/users`, {
      headers: entetes,
      data: {
        username: email,
        email,
        emailVerified: true,
        enabled: true,
        // Required by Keycloak's default user profile: without them the first
        // sign-in stops on an "update your profile" screen.
        firstName: 'E2E',
        lastName: email.split('@')[0],
        requiredActions: [],
      },
    });
    expect(creation.status(), await creation.text()).toBe(201);
    compte = await chercherCompte(requeteur, jeton, email);
  }
  expect(compte, `le compte Keycloak de ${email} doit exister`).toBeTruthy();
  const id = compte!.id;

  const miseAJour = await requeteur.put(`${base}/users/${id}`, {
    headers: entetes,
    data: {
      ...compte,
      enabled: true,
      emailVerified: true,
      firstName: compte!.firstName || 'E2E',
      lastName: compte!.lastName || email.split('@')[0],
      requiredActions: [],
    },
  });
  expect(miseAJour.ok(), await miseAJour.text()).toBe(true);

  const motDePasse = await requeteur.put(`${base}/users/${id}/reset-password`, {
    headers: entetes,
    data: { type: 'password', value: MOT_DE_PASSE_ANIMATEUR_E2E, temporary: false },
  });
  expect(motDePasse.ok(), await motDePasse.text()).toBe(true);

  const role = await requeteur.get(`${base}/roles/${ROLE_ANIMATEUR}`, { headers: entetes });
  expect(role.ok(), `le realm doit porter le rôle « ${ROLE_ANIMATEUR} »`).toBe(true);
  const attribution = await requeteur.post(`${base}/users/${id}/role-mappings/realm`, {
    headers: entetes,
    data: [await role.json()],
  });
  expect(attribution.ok(), await attribution.text()).toBe(true);

  comptesPrets.add(email.toLowerCase());
  return identifiants;
}

/** An admin-API token of the Keycloak server's own administrator (master realm, `admin-cli`). */
async function jetonAdministrateurKeycloak(requeteur: APIRequestContext): Promise<string> {
  const reponse = await requeteur.post(
    `${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token`,
    {
      form: {
        grant_type: 'password',
        client_id: 'admin-cli',
        username: ADMINISTRATEUR_KEYCLOAK.utilisateur,
        password: ADMINISTRATEUR_KEYCLOAK.motDePasse,
      },
    },
  );
  expect(reponse.ok(), await reponse.text()).toBe(true);
  return ((await reponse.json()) as { access_token: string }).access_token;
}

async function chercherCompte(
  requeteur: APIRequestContext,
  jeton: string,
  email: string,
): Promise<CompteKeycloak | null> {
  const reponse = await requeteur.get(
    `${KEYCLOAK_URL}/admin/realms/${REALM}/users?email=${encodeURIComponent(email)}&exact=true`,
    { headers: { Authorization: `Bearer ${jeton}` } },
  );
  expect(reponse.ok(), await reponse.text()).toBe(true);
  const comptes = (await reponse.json()) as CompteKeycloak[];
  return comptes.length === 0 ? null : comptes[0];
}

function echapper(valeur: string): string {
  return valeur.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
