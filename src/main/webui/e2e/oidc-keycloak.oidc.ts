// The real provider, once: sign in through Keycloak's own login page (code
// flow, PKCE), read the session, be refused as an account the application does
// not know, sign out. The in-memory provider of the Java tests proves the
// application's rules; this proves they hold against a provider that really
// redirects, signs and ends sessions. Run by .github/workflows/oidc-keycloak.yml.

import { expect, test, type Browser, type Page } from '@playwright/test';
import { contexteAdmin } from './support';

const MOT_DE_PASSE = process.env['E2E_OIDC_USER_PASSWORD'] ?? '';
const FOURNISSEUR = new URL(process.env['E2E_KEYCLOAK_URL'] ?? 'http://localhost:8180').origin;
const ADRESSE_CONNUE = 'organisatrice@example.org';

interface Statut {
  authentifie: boolean;
  nom: string | null;
  roles: string[];
}

/** Signs `utilisateur` in at the provider, from the application's own login route. */
async function seConnecter(page: Page, utilisateur: string): Promise<void> {
  await page.goto('/api/auth/oidc/login');
  await page.locator('#username').fill(utilisateur);
  await page.locator('#password').fill(MOT_DE_PASSE);
  await page.locator('#kc-login').click();
  // Back on the application, whatever it then decides to show.
  await page.waitForURL((url) => url.origin !== FOURNISSEUR);
}

async function statut(page: Page): Promise<Statut> {
  return (await (await page.request.get('/api/auth/me')).json()) as Statut;
}

async function nouvellePage(browser: Browser): Promise<Page> {
  return (await browser.newContext()).newPage();
}

test.describe('connexion par Keycloak', () => {
  test.skip(!MOT_DE_PASSE, 'E2E_OIDC_USER_PASSWORD: le mot de passe importé dans le realm');

  test.beforeAll(async ({ playwright, baseURL }) => {
    // The break-glass account is open in this stack, and is the only way to
    // create the first account: sign-in itself never creates one.
    const admin = await contexteAdmin(playwright, baseURL as string);
    const existants = (await (await admin.get('/api/comptes')).json()) as {
      id: string;
      email: string;
    }[];
    let id = existants.find((compte) => compte.email === ADRESSE_CONNUE)?.id;
    if (!id) {
      const cree = await admin.post('/api/comptes', { data: { email: ADRESSE_CONNUE } });
      expect(cree.ok(), await cree.text()).toBe(true);
      id = ((await cree.json()) as { id: string }).id;
    }
    // A re-run on the same database finds the right already there: 409, harmless.
    const droit = await admin.post(`/api/comptes/${id}/habilitations`, { data: { role: 'ADMIN' } });
    expect([200, 409], await droit.text()).toContain(droit.status());
    await admin.dispose();
  });

  test('un compte connu se connecte, un compte inconnu est refusé, la déconnexion ferme tout', async ({
    browser,
  }) => {
    const page = await nouvellePage(browser);
    await seConnecter(page, 'organisatrice');
    const connu = await statut(page);
    expect(connu).toMatchObject({ authentifie: true, nom: ADRESSE_CONNUE });
    expect(connu.roles).toContain('admin');
    expect((await page.request.get('/api/comptes')).status()).toBe(200);

    // Signed in at the provider, unknown to the application: no role, no API.
    const inconnue = await nouvellePage(browser);
    await seConnecter(inconnue, 'inconnue');
    expect((await statut(inconnue)).roles).toEqual([]);
    expect((await inconnue.request.get('/api/comptes')).status()).toBe(403);

    // Signing out ends the provider's session too: the next sign-in asks again.
    await page.goto('/');
    await page.getByRole('button', { name: 'Se déconnecter' }).click();
    await expect(page).toHaveURL(/\/login(\?|$)/);
    expect((await statut(page)).authentifie).toBe(false);
    await page.goto('/api/auth/oidc/login');
    await expect(page.locator('#username')).toBeVisible();
  });
});
