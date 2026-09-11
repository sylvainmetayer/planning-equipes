// The two pages that gathered eight screens: the day under its four
// renderings, and the diagnostic under its four tabs. What a unit test cannot
// see is pinned here — that switching the rendering fetches nothing again,
// that the addresses of the former screens still land on the right tab or
// rendering with their parameters, and that Leaflet only travels with the map.

import { APIRequestContext, expect, test, type Page } from '@playwright/test';
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

/** Every `/api/…` request the page sends from now on, by path. */
function espionnerLApi(page: Page): string[] {
  const chemins: string[] = [];
  page.on('request', (requete) => {
    const url = new URL(requete.url());
    if (url.pathname.startsWith('/api/')) {
      chemins.push(url.pathname);
    }
  });
  return chemins;
}

test('la journée change de rendu sans relire le planning, et garde le jour choisi', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/journee');
  await expect(page.locator('#contenu')).toContainText('Journée');
  await expect(page.locator('.day-calendar-grid')).toBeVisible();

  const appels = espionnerLApi(page);
  const rendus = page.getByRole('radiogroup', { name: 'Rendu de la journée' });
  await rendus.getByText('Rail').click();
  await expect(page.locator('.rail-table')).toBeVisible();
  await expect(page).toHaveURL(/vue=rail/);

  await rendus.getByText('Pauses').click();
  await expect(page.locator('.pauses-message, .empty-hint').first()).toBeVisible();
  await expect(page).toHaveURL(/vue=pauses/);

  await rendus.getByText('Calendrier').click();
  await expect(page.locator('.day-calendar-grid')).toBeVisible();
  await expect(page).not.toHaveURL(/vue=/);

  // Three switches, and the plan, the breaks and the referentials were read
  // once, before the spy: nothing was fetched again. The calendar's persisted
  // count is its own and cheap; it is re-read when the calendar comes back.
  expect(
    appels.filter(
      (chemin) => chemin.startsWith('/api/planning') && chemin !== '/api/planning/persisted/count',
    ),
  ).toEqual([]);
  expect(appels.filter((chemin) => chemin === '/api/pauses')).toEqual([]);
  await page.context().close();
});

test('les adresses des anciens écrans mènent au bon rendu, paramètres compris', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);

  await page.goto('/rail-jour?vue=libres');
  await expect(page).toHaveURL(/\/journee\?.*vue=rail/);
  await expect(page).toHaveURL(/lignes=libres/);
  await expect(page.getByRole('radiogroup', { name: 'Lignes affichées' })).toContainText(
    'Mobilisables',
  );

  await page.goto('/pauses?vue=sans-relais');
  await expect(page).toHaveURL(/\/journee\?.*vue=pauses/);
  await expect(page).toHaveURL(/relais=sans/);

  await page.goto('/carte-jour');
  await expect(page).toHaveURL(/\/journee\?.*vue=carte/);

  await page.goto('/staffing');
  await expect(page).toHaveURL(/\/diagnostic\?.*onglet=besoin/);
  await expect(page.locator('#contenu')).toContainText('Minimum retenu');

  await page.goto('/problemes');
  await expect(page).toHaveURL(/\/diagnostic/);
  await expect(page.locator('#contenu')).toContainText('Diagnostic');
  await page.context().close();
});

test("Leaflet ne voyage qu'avec le rendu carte", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  const scripts: string[] = [];
  page.on('response', (reponse) => {
    if (reponse.request().resourceType() === 'script') {
      scripts.push(reponse.url());
    }
  });
  await page.goto('/journee?vue=rail');
  await expect(page.locator('.rail-table')).toBeVisible();
  const avantLaCarte = scripts.length;

  await page.getByRole('radiogroup', { name: 'Rendu de la journée' }).getByText('Carte').click();
  await expect(page.locator('.leaflet-container')).toBeVisible();

  // The map's chunk arrives with the map, not with the page.
  expect(scripts.length).toBeGreaterThan(avantLaCarte);
  await page.context().close();
});

test('le diagnostic ouvre ses onglets sans changer de page, chacun avec son état', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/diagnostic');
  await expect(page.locator('#contenu')).toContainText('Diagnostic');
  await expect(page.locator('.probleme-counts, .calendar-meta').first()).toBeVisible();
  // One heading only: the tab's own title stays out.
  await expect(page.locator('#contenu h1')).toHaveCount(1);

  const onglets = page.getByRole('radiogroup', { name: 'Onglet du diagnostic' });
  await onglets.getByText('Fragilité').click();
  await expect(page).toHaveURL(/onglet=fragilite/);
  const champ = page.getByLabel('Filtrer', { exact: true });
  await champ.fill('Alice');
  // The tab's own state sits next to the page's key, neither erasing the other.
  await expect(page).toHaveURL(/onglet=fragilite/);
  await expect(page).toHaveURL(/[?&]q=Alice/);

  await onglets.getByText('Besoin en animateurs').click();
  await expect(page).toHaveURL(/onglet=besoin/);
  await expect(page.locator('#contenu')).toContainText('Minimum retenu');
  await page.context().close();
});
