// The two pages that gathered eight screens: the day under its four
// renderings, and the diagnostic under its four tabs. What a unit test cannot
// see is pinned here — that switching the rendering fetches nothing again,
// that the addresses of the former screens still land on the right tab or
// rendering with their parameters, and that Leaflet only travels with the map.

import { APIRequestContext, expect, test, type Page } from '@playwright/test';
import { contexteAdmin, pageAdmin, SEED, seedPlanning } from './support';
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
  await expect(page.locator('#contenu')).toContainText('Planning');
  await expect(page.locator('.jour-table')).toBeVisible();

  const appels = espionnerLApi(page);
  const rendus = page.getByRole('radiogroup', { name: 'Rendu de la journée' });
  await rendus.getByText('Rail').click();
  await expect(page.locator('.rail-table')).toBeVisible();
  await expect(page).toHaveURL(/vue=rail/);

  await rendus.getByText('Pauses et repas').click();
  await expect(page.locator('.pauses-message, .empty-hint').first()).toBeVisible();
  await expect(page).toHaveURL(/vue=pauses/);

  await rendus.getByText('Tableau').click();
  await expect(page.locator('.jour-table')).toBeVisible();
  await expect(page).not.toHaveURL(/vue=/);

  // Three switches, and the plan, the breaks and the referentials were read
  // once, before the spy: nothing was fetched again — the table no longer
  // re-reads a count of persisted assignments either (issue #712).
  expect(appels.filter((chemin) => chemin.startsWith('/api/planning'))).toEqual([]);
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

  // The three screens the Planning page absorbed (issue #712).
  // The first day of the edition is the page's default: it writes no `date=`.
  await page.goto(`/calendar?date=${SEED.jour}&stand=${SEED.standDemandeur}`);
  await expect(page).toHaveURL(new RegExp(`/journee\\?.*stand=${SEED.standDemandeur}`));

  await page.goto('/intendance');
  await expect(page).toHaveURL(/\/journee\?.*vue=pauses/);
  await expect(page.locator('#contenu')).toContainText('Combien de personnes mangent');

  await page.goto('/graphe');
  await expect(page).toHaveURL(/\/journee\?.*vue=carte/);

  // A date in `jour` opens that day, where it used to be ignored in silence.
  // Read as `date`, then left out of the address as the edition's first day.
  await page.goto(`/journee?jour=${SEED.jour}`);
  await expect(page).toHaveURL(/\/journee$/);

  await page.goto('/staffing');
  await expect(page).toHaveURL(/\/diagnostic\?.*onglet=besoin/);
  await expect(page.locator('#contenu')).toContainText('Minimum retenu');

  await page.goto('/problemes');
  await expect(page).toHaveURL(/\/diagnostic/);
  await expect(page.locator('#contenu')).toContainText('Diagnostic');

  // The margin became two tabs: its « avant » reading a column of Besoin, its
  // « après » and « tension » readings the Tension tab.
  await page.goto('/marge');
  await expect(page).toHaveURL(/\/diagnostic\?onglet=besoin/);
  await expect(page.locator('#contenu')).toContainText('Disponibles − sièges');
  await page.goto('/marge?mode=tension');
  await expect(page).toHaveURL(/\/diagnostic\?onglet=tension/);
  await expect(page.locator('#contenu')).toContainText('Tension, tranche par tranche');

  // « À former » is the foot of Besoin.
  await page.goto('/diagnostic?onglet=former');
  await expect(page).toHaveURL(/onglet=besoin/);
  await expect(page.locator('#a-former')).toContainText('À former');
  await page.context().close();
});

test('le diagnostic ne mène à aucune timeline et ne se dit plus à l’essai', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  for (const onglet of ['problemes', 'besoin', 'tension', 'fragilite']) {
    await page.goto(`/diagnostic?onglet=${onglet}`);
    await expect(page.locator('#contenu h1')).toHaveCount(1);
    await expect(page.locator('#contenu a[href^="/timeline"]')).toHaveCount(0);
    await expect(page.locator('#contenu')).not.toContainText('livré à l’essai');
    await expect(page.locator('#contenu')).not.toContainText("livré à l'essai");
  }
  await page.context().close();
});

/**
 * Issue #712: on a 1440 × 900 screen the first line of the plan shows without
 * scrolling, whatever the rendering — the day, the filters, the relecture and
 * the consigne fit above it.
 */
test('le planning se lit sans défiler, sur les cinq rendus', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.setViewportSize({ width: 1440, height: 900 });
  const premiers: Record<string, string> = {
    calendrier: '.jour-table tbody th',
    rail: '.rail-table tbody th',
    carte: '.leaflet-container',
    pauses: '.pauses-message, .pauses-stand-titre, app-pauses-vue .empty-hint',
    changements: '.journee-changements-toolbar',
  };
  for (const [vue, selecteur] of Object.entries(premiers)) {
    await page.goto(`/journee?vue=${vue}&date=${SEED.jour}`);
    const premier = page.locator(selecteur).first();
    await expect(premier, `rendu ${vue}`).toBeVisible();
    const boite = await premier.boundingBox();
    expect(boite, `rendu ${vue}`).not.toBeNull();
    expect(boite!.y, `premier nom du rendu ${vue} sous la ligne de flottaison`).toBeLessThan(900);
  }
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

  await onglets.getByText('Tension').click();
  await expect(page).toHaveURL(/onglet=tension/);
  await expect(page.locator('#contenu')).toContainText('Tension, tranche par tranche');
  await page.context().close();
});
