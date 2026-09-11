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

test('la heatmap retrouve sa vue et sa recherche après un rechargement', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/heatmap');

  await page.locator('mat-button-toggle').filter({ hasText: 'Par animateur' }).click();
  await page.getByLabel('Filtrer par nom').fill('Alice');
  await expect(page.locator('.heatmap-row-label')).toHaveText([/Alice E2E/]);
  await expect(page).toHaveURL(/[?&]view=animateur/);
  await expect(page).toHaveURL(/[?&]q=Alice/);

  // Le rechargement complet : c'est lui, et non un simple re-rendu, qui
  // distingue un état porté par l'URL d'un état gardé en mémoire.
  await page.reload();

  await expect(page.getByLabel('Filtrer par nom')).toHaveValue('Alice');
  await expect(page.locator('.heatmap-row-label')).toHaveText([/Alice E2E/]);
  await page.context().close();
});

test('la heatmap se remet à zéro en une action, et l’URL avec elle', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/heatmap?view=animateur&q=Alice');
  await expect(page.locator('.heatmap-row-label')).toHaveText([/Alice E2E/]);

  await page.getByRole('button', { name: 'Réinitialiser la vue' }).click();

  await expect(page).not.toHaveURL(/[?&]view=/);
  await expect(page).not.toHaveURL(/[?&]q=/);
  // Retour à la vue d'ouverture : les stands, pas les animateurs.
  await expect(page.locator('.heatmap-row-label').first()).toHaveText(/Stand E2E/);
  await page.context().close();
});

test('une vue inconnue dans l’URL laisse la heatmap sur sa vue d’ouverture', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/heatmap?view=par-jour');

  await expect(page.locator('.heatmap-row-label').first()).toHaveText(/Stand E2E/);
  await page.context().close();
});

test('le tri des heures survit au rechargement, dans les deux sens', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/hours');
  // `tbody` explicitement : la ligne de pied « Tous les animateurs » porte la
  // même classe de colonne, et compterait comme une ligne de plus.
  const noms = page.locator('table tbody td.mat-column-animateur');
  const entete = page.getByRole('button', { name: 'Animateur' });

  await entete.click();
  await expect(noms).toHaveText([/Alice E2E/, /Bruno E2E/]);
  await expect(page).toHaveURL(/[?&]sort=animateur/);
  await expect(page).toHaveURL(/[?&]dir=asc/);

  await entete.click();
  await expect(noms).toHaveText([/Bruno E2E/, /Alice E2E/]);
  await expect(page).toHaveURL(/[?&]dir=desc/);

  await page.reload();

  await expect(noms).toHaveText([/Bruno E2E/, /Alice E2E/]);
  // L'en-tête aussi : une flèche de tri qui ne suit pas les lignes restaurées
  // affiche un tableau trié en le disant non trié.
  await expect(page.getByRole('columnheader', { name: 'Animateur' })).toHaveAttribute(
    'aria-sort',
    'descending',
  );
  await page.context().close();
});

test('une colonne de semaine disparue ne casse pas le tableau des heures', async ({ browser }) => {
  // Exactement ce que porte un lien mis en favori sur l'édition précédente.
  const page = await pageAdmin(browser, admin);
  await page.goto('/hours?sort=2019-W01&dir=asc');

  await expect(page.locator('#contenu')).toContainText('Heures planifiées par animateur');
  await expect(page.locator('table tbody td.mat-column-animateur')).toHaveCount(2);
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

test('la timeline suit l’animateur choisi dans l’URL, et ce lien rouvre la même personne', async ({
  browser,
}) => {
  // Le lien qu'on partage depuis cet écran, c'est « regarde le planning
  // d'Untel » : c'est donc la sélection, et non un tri, que l'URL doit porter.
  // Cette page écrivait son URL à la main, hors du helper partagé, jusqu'à ce
  // qu'elle le rejoigne — d'où ce test au même endroit que les autres.
  const page = await pageAdmin(browser, admin);
  await page.goto('/timeline');
  // Le champ de sélection porte lui-même le nom retenu : c'est le seul endroit
  // de l'écran qui nomme la personne affichée.
  const champ = page.getByLabel('Animateur', { exact: true });
  // Le champ arrive prérempli par la sélection d'ouverture : on le vide comme
  // le ferait l'utilisateur, puis on tape.
  await champ.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('Bruno', { delay: 30 });
  await page.getByRole('option', { name: /Bruno/ }).click();

  await expect(page).toHaveURL(/[?&]animateur=E2E-B/);
  await expect(champ).toHaveValue(/Bruno/);

  // Le rechargement : c'est lui qui distingue un état porté par l'URL d'un
  // état gardé en mémoire.
  await page.reload();
  await expect(page.getByLabel('Animateur', { exact: true })).toHaveValue(/Bruno/);
  await expect(page).toHaveURL(/[?&]animateur=E2E-B/);
  await page.context().close();
});

test('un lien de timeline partagé ouvre directement la bonne personne', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/timeline?animateur=E2E-B');

  await expect(page.getByLabel('Animateur', { exact: true })).toHaveValue(/Bruno/);
  await page.context().close();
});

test('le bouton Retour quitte la timeline au lieu de rejouer chaque sélection', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/hours');
  await page.goto('/timeline');

  const champ = page.getByLabel('Animateur', { exact: true });
  // Le champ arrive prérempli par la sélection d'ouverture : on le vide comme
  // le ferait l'utilisateur, puis on tape.
  await champ.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('Bruno', { delay: 30 });
  await page.getByRole('option', { name: /Bruno/ }).click();
  await expect(page).toHaveURL(/[?&]animateur=E2E-B/);

  await page.goBack();

  await expect(page).toHaveURL(/\/hours/);
  await page.context().close();
});

test('le bouton Retour quitte l’écran au lieu de rejouer chaque frappe du filtre', async ({
  browser,
}) => {
  // `replaceUrl` : sans lui, filtrer sur cinq caractères laisserait cinq
  // entrées d'historique, et il faudrait cinq retours pour sortir de la page.
  const page = await pageAdmin(browser, admin);
  await page.goto('/hours');
  await page.goto('/animateurs');

  await page.getByRole('searchbox').fill('Alice');
  await expect(page).toHaveURL(/[?&]q=Alice/);

  await page.goBack();

  await expect(page).toHaveURL(/\/hours/);
  await page.context().close();
});
