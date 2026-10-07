import { expect, test, type CDPSession, type Page } from '@playwright/test';
import { COMPTE_ADMIN, COMPTE_ANIMATRICE, KEYCLOAK_URL, REALM } from './keycloak';

/**
 * Les méthodes de connexion que le realm offre à côté du mot de passe.
 *
 * <p>Ce fichier couvre ce qui est vérifiable sans matériel ni tiers : la forme
 * du parcours — l'adresse d'abord, la méthode ensuite — et le passkey, que
 * Chrome sait simuler. Le code par e-mail de Keycloak (l'extension de
 * l'image du dépôt) n'est pas ici : il demanderait de lire la boîte de Keycloak,
 * et il est documenté dans docs/keycloak.md plutôt que couvert à moitié.</p>
 */
test.describe('méthodes de connexion', () => {
  /**
   * La propriété qui commande tout le reste : l'écran de connexion demande
   * l'adresse seule.
   *
   * <p>C'est ce qui rend les autres méthodes possibles. Tant que Keycloak
   * réclame adresse et mot de passe d'un bloc, il ne peut rien offrir d'autre :
   * il faut savoir QUI se connecte avant de savoir de quoi cette personne
   * dispose.</p>
   */
  test("l'adresse est demandée seule, avant toute méthode", async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
    await page.waitForURL(new RegExp(`${KEYCLOAK_URL}/realms/${REALM}/`));

    await expect(page.locator('#username')).toBeVisible();
    await expect(
      page.locator('#password'),
      "le mot de passe ne s'affiche qu'une fois l'adresse connue",
    ).toBeHidden();

    await page.locator('#username').fill(COMPTE_ANIMATRICE.email);
    await page.locator('#kc-login').click();

    await expect(
      page.locator('#password'),
      'ce compte ne dispose que du mot de passe : Keycloak y va directement',
    ).toBeVisible();
  });

  /**
   * Un passkey ouvre la session de l'administrateur <b>sans</b> second facteur.
   *
   * <p>Ce n'est pas une dérogation : une clé résidente à vérification
   * d'utilisateur porte les deux facteurs à elle seule — sa possession, et le
   * déverrouillage qui prouve qu'une personne est là. Le realm l'exprime par sa
   * structure, le passkey étant une alternative à toute la branche mot de
   * passe, dans laquelle vit la condition OTP.</p>
   *
   * <p>Ignoré par défaut : l'enrôlement passe par la console de compte de
   * Keycloak, donc par un parcours que cette suite ne joue pas encore.
   * {@code E2E_PASSKEY=1} l'active une fois ce parcours écrit. L'outillage
   * ci-dessous — l'authentificateur virtuel de Chrome — est là et fonctionne :
   * c'est la moitié qui manque, pas la plomberie.</p>
   */
  test('un passkey ouvre la session sans second facteur', async ({ page, browserName }) => {
    test.skip(
      process.env['E2E_PASSKEY'] !== '1',
      'enrôlement du passkey à écrire : voir docs/keycloak.md',
    );
    test.skip(browserName !== 'chromium', 'authentificateur virtuel : Chrome seulement');

    const authentificateur = await authentificateurVirtuel(page);

    await page.goto('/login');
    await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
    await page.locator('#username').fill(COMPTE_ADMIN.email);
    await page.locator('#kc-login').click();

    // Aucun code : si l'écran OTP apparaît, la structure du flow a bougé et le
    // passkey est redevenu un facteur parmi d'autres.
    await expect(
      page.locator('#otp'),
      'un passkey vaut les deux facteurs : aucun code ne doit être demandé',
    ).toBeHidden();

    await expect(page).toHaveURL(/\/(admin|planning|$)/);
    await authentificateur.detach();
  });
});

/**
 * Branche l'authentificateur virtuel de Chrome, qui répond aux demandes WebAuthn
 * sans matériel.
 *
 * <p>{@code hasResidentKey} et {@code hasUserVerification} ne sont pas des
 * détails : ce sont eux qui font de la clé simulée un passkey plutôt qu'une
 * simple clé de second facteur, et donc ce qui rend le test fidèle à la
 * politique du realm.</p>
 */
async function authentificateurVirtuel(page: Page): Promise<CDPSession> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  });
  return cdp;
}
