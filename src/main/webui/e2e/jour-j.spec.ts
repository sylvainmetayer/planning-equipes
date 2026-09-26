// Aujourd'hui (ex-« Mode jour J ») tel qu'il est livré : l'écran du jour, rangé
// dans son groupe, que l'ancienne adresse ouvre encore, qui trouve quelqu'un
// par une recherche plutôt que dans une liste de boutons — plus la garde qui
// empêche une instance déployée de figer sa date.
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

test("l'écran Aujourd'hui est rangé dans son groupe, et nulle part ailleurs", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/');

  const groupe = page.locator('#nav-group-aujourdhui');
  await expect(groupe.getByRole('link', { name: "Aujourd'hui" })).toBeVisible();
  await expect(
    page.locator('#nav-group-diffuser').getByRole('link', { name: 'Échanges' }),
  ).toBeVisible();
  await expect(
    page.getByRole('navigation', { name: 'Navigation principale' }).getByRole('link', {
      name: "Aujourd'hui",
    }),
  ).toHaveCount(1);
  await page.context().close();
});

test("l'ancienne adresse /jour-j ouvre Aujourd'hui, sans bandeau « en cours »", async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/jour-j');

  await expect(page).toHaveURL(/\/aujourdhui$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText("Aujourd'hui");
  await expect(page.locator('.work-in-progress-banner')).toHaveCount(0);
  await page.context().close();
});

/**
 * Trouver une personne parmi tout l'effectif se fait par une recherche, sur
 * téléphone : aucun bouton « Marquer absent » avant d'avoir cherché — là où
 * l'écran en alignait un par personne de service.
 */
test('on cherche une personne au lieu de parcourir une liste', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/aujourdhui');

  const contenu = page.locator('#contenu');
  await expect(contenu.getByRole('button', { name: 'Marquer absent' })).toHaveCount(0);
  const animateurs = (await (await admin.get('/api/animateurs')).json()) as {
    prenom: string;
    nom: string;
  }[];
  const cible = animateurs[0];
  await page.getByLabel('Chercher une personne').fill(`${cible.prenom} ${cible.nom}`);
  await expect(contenu).toContainText(`${cible.prenom} ${cible.nom}`);
  await page.context().close();
});

/**
 * Le jeu de données amorcé est daté de plusieurs semaines après l'horloge réelle
 * (`shiftDate`) : sur une exécution de CI, aucune journée n'est en cours. C'est le cas que l'écran rencontre le plus
 * souvent hors événement, et il doit le dire au lieu de se déclarer terminé.
 */
test('une date sans créneau programmé se lit comme telle', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/aujourdhui');

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
  // L'onglet qui porte la carte : si la garde tombait, c'est là qu'elle
  // apparaîtrait — l'onglet par défaut ne la rendrait de toute façon pas.
  await page.goto('/parametres?onglet=instance');
  await expect(page.locator('#contenu')).toContainText('Sauvegarde automatique');

  await expect(page.locator('#date-du-jour')).toHaveCount(0);
  await expect(page.locator('#contenu')).not.toContainText('Date et heure simulées');
  const refus = await page.request.put('/api/horloge', {
    data: { dateDuJour: '2026-07-08' },
  });
  expect(refus.status()).toBe(400);
  await page.context().close();
});
