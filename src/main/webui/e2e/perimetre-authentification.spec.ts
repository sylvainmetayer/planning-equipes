// Security perimeter of the admin application (issue #165): everything behind
// the session, the two deliberate public exceptions, and the login round trip.

import { expect, test } from '@playwright/test';
import { MOT_DE_PASSE_ADMIN } from './support';

test.describe('mur d’authentification', () => {
  test("l'API répond 401 nu sans session, jamais une redirection HTML", async ({ request }) => {
    const reponse = await request.get('/api/constraints', { maxRedirects: 0 });
    expect(reponse.status()).toBe(401);
  });

  test("l'espace animateur reste public : un jeton inconnu répond 404, pas 401", async ({
    request,
  }) => {
    const reponse = await request.get('/api/espace-animateur/jeton-invente', { maxRedirects: 0 });
    expect(reponse.status()).toBe(404);
  });

  test('le statut de session est public et anonyme par défaut', async ({ request }) => {
    const reponse = await request.get('/api/auth/me');
    expect(reponse.status()).toBe(200);
    expect(await reponse.json()).toMatchObject({ authentifie: false });
  });

  test("ouvrir l'administration sans session mène à la page de connexion", async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
    // None of the admin chrome leaked onto the login page.
    await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toHaveCount(0);
  });

  test('un mauvais mot de passe est refusé sans ouvrir de session', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Utilisateur').fill('admin');
    await page.getByLabel('Mot de passe').fill('mauvais-mot-de-passe');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByText('Identifiants incorrects.')).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    const statut = await page.request.get('/api/auth/me');
    expect(await statut.json()).toMatchObject({ authentifie: false });
  });

  test('connexion, navigation admin, puis déconnexion', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Utilisateur').fill('admin');
    await page.getByLabel('Mot de passe').fill(MOT_DE_PASSE_ADMIN);
    await page.getByRole('button', { name: 'Se connecter' }).click();

    // The admin shell is up and the API answers with the session cookie.
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toBeVisible();
    const statut = await page.request.get('/api/auth/me');
    expect(await statut.json()).toMatchObject({ authentifie: true, nom: 'admin' });

    await page.getByRole('button', { name: 'Se déconnecter' }).click();
    await expect(page).toHaveURL(/\/login$/);
    const apres = await page.request.get('/api/constraints', { maxRedirects: 0 });
    expect(apres.status()).toBe(401);
  });
});
