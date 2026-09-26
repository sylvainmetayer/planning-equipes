// L'état de vue vit dans l'URL (docs/decisions/0012-etat-de-vue-dans-l-url.md).
// Les tests unitaires prouvent que le composant lit et écrit les bons
// paramètres ; ils ne peuvent rien dire de ce qui se joue ici : que le vrai
// routeur les pose dans la barre d'adresse, qu'un rechargement complet du
// navigateur les relit, et que `replaceUrl` laisse le bouton Retour quitter
// l'écran au lieu de rejouer chaque frappe du filtre.

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

test('le planning par stand retrouve sa densité et sa recherche après un rechargement', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?axe=stand');
  const lignes = page.locator('.planning-grille tbody th a.planning-grille-lien');

  await page.locator('mat-button-toggle').filter({ hasText: 'Compteurs' }).click();
  await page.getByLabel('Filtrer par nom').fill('deux');
  await expect(lignes).toHaveText([/Stand E2E deux/]);
  await expect(page).toHaveURL(/[?&]densite=compteurs/);
  await expect(page).toHaveURL(/[?&]q=deux/);

  // Le rechargement complet : c'est lui, et non un simple re-rendu, qui
  // distingue un état porté par l'URL d'un état gardé en mémoire.
  await page.reload();

  await expect(page.getByLabel('Filtrer par nom')).toHaveValue('deux');
  await expect(lignes).toHaveText([/Stand E2E deux/]);
  await page.context().close();
});

test('le planning par stand se remet à zéro en une action, sans quitter son axe', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?axe=stand&densite=compteurs&q=deux');
  const lignes = page.locator('.planning-grille tbody th a.planning-grille-lien');
  await expect(lignes).toHaveText([/Stand E2E deux/]);

  await page.getByRole('button', { name: 'Réinitialiser la vue' }).click();

  await expect(page).not.toHaveURL(/[?&]densite=/);
  await expect(page).not.toHaveURL(/[?&]q=/);
  await expect(page).toHaveURL(/[?&]axe=stand/);
  await expect(lignes).toHaveCount(2);
  await page.context().close();
});

test('une densité inconnue dans l’URL laisse le planning par stand sur les noms', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  // The Heatmap's former key, as a bookmark still carries it.
  await page.goto('/heatmap?view=par-jour');

  await expect(page).toHaveURL(/[?&]axe=stand/);
  await expect(page.locator('.planning-grille tbody th').first()).toHaveText(/Stand E2E/);
  await page.context().close();
});

test('le tri par personne survit au rechargement, dans les deux sens', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee?axe=personne');
  const noms = page.locator('.planning-grille tbody th a.planning-grille-lien');
  const entete = page.getByRole('button', { name: 'Animateur', exact: true });

  await entete.click();
  await expect(noms).toHaveText([/Alice E2E/, /Bruno E2E/]);
  await expect(page).toHaveURL(/[?&]sort=nom/);
  await expect(page).toHaveURL(/[?&]dir=asc/);

  await entete.click();
  await expect(noms).toHaveText([/Bruno E2E/, /Alice E2E/]);
  await expect(page).toHaveURL(/[?&]dir=desc/);

  await page.reload();

  await expect(noms).toHaveText([/Bruno E2E/, /Alice E2E/]);
  // L'en-tête aussi : une flèche de tri qui ne suit pas les lignes restaurées
  // affiche un tableau trié en le disant non trié.
  await expect(page.locator('th.planning-grille-entete').first()).toHaveAttribute(
    'aria-sort',
    'descending',
  );
  await page.context().close();
});

test('une colonne de semaine disparue ne casse pas le planning par personne', async ({
  browser,
}) => {
  // Exactement ce que porte un lien vers les Heures mis en favori sur
  // l'édition précédente.
  const page = await pageAdmin(browser, admin);
  await page.goto('/hours?sort=2019-W01&dir=asc');

  await expect(page).toHaveURL(/[?&]axe=personne/);
  await expect(page.locator('.planning-grille tbody th a.planning-grille-lien')).toHaveCount(2);
  await page.context().close();
});

test('le filtre des animateurs se restaure au rechargement et se vide en une action', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/animateurs');
  const lignes = page.locator('table tbody td.mat-column-nom');
  await expect(lignes.filter({ hasText: 'Bruno' })).toHaveCount(1);

  await page.getByRole('searchbox').fill('Alice');
  await expect(lignes.filter({ hasText: 'Bruno' })).toHaveCount(0);
  await expect(page).toHaveURL(/[?&]q=Alice/);

  await page.reload();

  await expect(page.getByRole('searchbox')).toHaveValue('Alice');
  await expect(lignes.filter({ hasText: 'Bruno' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Réinitialiser la vue' }).click();

  await expect(page).not.toHaveURL(/[?&]q=/);
  await expect(lignes.filter({ hasText: 'Bruno' })).toHaveCount(1);
  await page.context().close();
});

test('un filtre se tape d’une traite, sans reprendre le focus entre deux lettres', async ({
  browser,
}) => {
  // Le défaut que l'utilisateur a remonté, et qu'aucun test unitaire ne pouvait
  // voir : il naît de l'interaction entre le routeur, le DOM et le focus réel
  // du navigateur. Refléter l'état de vue dans l'URL passait par une navigation
  // du routeur, donc un re-rendu à chaque frappe — le champ perdait le focus et
  // seule la première lettre arrivait.
  //
  // D'où le geste testé : un clic, puis la frappe caractère par caractère sans
  // jamais recliquer. `fill()` ne l'aurait pas vu, il pose la valeur d'un bloc.
  const page = await pageAdmin(browser, admin);
  await page.goto('/diagnostic?onglet=fragilite');
  const noms = page.locator('.fragilite-nom');
  await expect(noms.filter({ hasText: 'Bruno' })).toHaveCount(1);

  const champ = page.getByLabel('Filtrer', { exact: true });
  await champ.click();
  await page.keyboard.type('Alice', { delay: 50 });

  // 1. le champ a gardé le focus d'un bout à l'autre ;
  await expect(champ).toBeFocused();
  // 2. la saisie est complète — c'est ce qui échouait vraiment ;
  await expect(champ).toHaveValue('Alice');
  // 3. et le filtrage a bien eu lieu.
  await expect(noms.filter({ hasText: 'Bruno' })).toHaveCount(0);
  await expect(noms.filter({ hasText: 'Alice' })).toHaveCount(1);
  await expect(page).toHaveURL(/[?&]q=Alice/);
  await page.context().close();
});

test('un ancien lien de timeline ouvre la fiche de la bonne personne, son planning déplié', async ({
  browser,
}) => {
  // La timeline est devenue la section « Planning » de la fiche : les liens
  // déjà partagés doivent aboutir sur la bonne personne, section ouverte.
  const page = await pageAdmin(browser, admin);
  await page.goto('/timeline?animateur=E2E-B');

  await expect(page).toHaveURL(/\/animateurs\/E2E-B\?section=timeline/);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Bruno');
  await expect(page.locator('#fiche-section-timeline')).toHaveAttribute('open', '');
  await page.context().close();
});

test('précédent et suivant parcourent la liste des animateurs telle qu’elle a été filtrée', async ({
  browser,
}) => {
  // Le filtre de la liste voyage dans l'adresse de la fiche : « suivant »
  // mène à la personne suivante de la liste filtrée, et le filtre suit.
  const page = await pageAdmin(browser, admin);
  await page.goto('/animateurs?q=E2E&sort=nom&dir=asc');
  await page.getByRole('link', { name: 'Alice E2E' }).first().click();

  await expect(page).toHaveURL(/\/animateurs\/E2E-A\?.*q=E2E/);
  await page.getByRole('link', { name: /Suivant : Bruno E2E/ }).click();
  await expect(page).toHaveURL(/\/animateurs\/E2E-B\?.*q=E2E/);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Bruno');
  await expect(page.getByRole('link', { name: /Précédent : Alice E2E/ })).toBeVisible();
  await page.context().close();
});

test('le bouton Retour quitte l’écran au lieu de rejouer chaque frappe du filtre', async ({
  browser,
}) => {
  // `replaceUrl` : sans lui, filtrer sur cinq caractères laisserait cinq
  // entrées d'historique, et il faudrait cinq retours pour sortir de la page.
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');
  await page.goto('/animateurs');

  await page.getByRole('searchbox').fill('Alice');
  await expect(page).toHaveURL(/[?&]q=Alice/);

  await page.goBack();

  await expect(page).toHaveURL(/\/journee/);
  await page.context().close();
});
