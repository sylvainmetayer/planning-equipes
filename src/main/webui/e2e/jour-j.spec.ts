// Le mode jour J tel qu'il est livré : en cours de développement, et le seul
// écran de ce groupe qui écrit. Ce que ces tests verrouillent est ce qu'un
// opérateur voit avant d'agir — le classement dans le menu et l'avertissement —,
// plus la garde qui empêche une instance déployée de figer sa date.
//
// Tout est statique : aucune attente sur une durée, la CI rejoue ces assertions
// à chaque poussée.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';
import { repartirDeLaReference } from './reference';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

test("le mode jour J est rangé dans le groupe « Pendant l'événement »", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/');

  const groupe = page.locator('#nav-group-pendant-evenement');
  await expect(groupe.getByRole('link', { name: 'Mode jour J' })).toBeVisible();
  await expect(groupe.getByRole('link', { name: 'Échanges' })).toBeVisible();
  // Et nulle part ailleurs : un écran qui écrit ne doit pas se lire comme
  // acquis depuis le groupe Planning.
  await expect(page.getByRole('link', { name: 'Mode jour J' })).toHaveCount(1);
  // Le groupe « En cours de développement » n'existe plus : ces écrans sont
  // livrés, et un organisateur n'ouvre pas un écran étiqueté « en cours ».
  await expect(page.getByRole('navigation', { name: 'Navigation principale' })).not.toContainText(
    'En cours de développement',
  );
  await page.context().close();
});

test("l'écran avertit qu'il agit sur le planning enregistré", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/jour-j');

  const banniere = page.locator('.work-in-progress-banner');
  await expect(banniere).toBeVisible();
  // Le texte par défaut du bandeau parle de données « qui peuvent encore
  // évoluer » : trop tiède ici, puisque le geste écrit tout de suite.
  await expect(banniere).toContainText('il agit');
  await expect(banniere).toContainText('planning enregistré');
  await expect(banniere).toContainText('ne sont pas encore garantis');
  await page.context().close();
});

/**
 * Le jeu de données amorcé est daté de juillet 2026 : sur une exécution de CI,
 * aucune journée n'est en cours. C'est le cas que l'écran rencontre le plus
 * souvent hors événement, et il doit le dire au lieu de se déclarer terminé.
 */
test('une date sans créneau programmé se lit comme telle', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/jour-j');

  const contenu = page.locator('#contenu');
  await expect(contenu).toContainText('Aucun créneau');
  await expect(contenu).not.toContainText('La journée est terminée');
  await page.context().close();
});

/**
 * La garde qui compte, vérifiée sur une application empaquetée — c'est-à-dire
 * exactement celle qu'un déploiement sert. Figer la date du jour n'existe qu'en
 * `quarkus:dev` : hors de là le champ n'est pas rendu, et le serveur refuse
 * l'écriture même si un client la tente quand même.
 */
test('la date du jour ne peut pas être figée hors mode développement', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/debug');
  await expect(page.locator('#contenu')).toContainText('Validateur YAML');

  await expect(page.locator('#date-du-jour')).toHaveCount(0);
  const refus = await page.request.put('/api/debug/date-du-jour', {
    data: { dateDuJour: '2026-07-08' },
  });
  expect(refus.status()).toBe(400);
  await page.context().close();
});
