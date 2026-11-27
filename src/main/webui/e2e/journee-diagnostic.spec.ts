// The two pages that gathered eight screens: the day under its four
// renderings, and the diagnostic under its tabs. What a unit test cannot
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
  // The rail is the default mode of the view « Jour », the table its other mode.
  await expect(page.locator('.rail-table')).toBeVisible();

  const appels = espionnerLApi(page);
  const rendus = page.getByRole('radiogroup', { name: 'Vue du planning' });
  const modes = page.getByRole('radiogroup', { name: 'Mode de la vue Jour' });
  await modes.getByText('Tableau').click();
  await expect(page.locator('.jour-table')).toBeVisible();
  await expect(page).toHaveURL(/vue=jour&mode=tableau/);
  await modes.getByText('Rail').click();
  await expect(page.locator('.rail-table')).toBeVisible();
  await expect(page).not.toHaveURL(/vue=/);

  await rendus.getByText('Carte').click();
  await expect(page.locator('.leaflet-container')).toBeVisible();
  await expect(page).toHaveURL(/vue=carte/);

  await rendus.getByText('Jour').click();
  await expect(page.locator('.rail-table')).toBeVisible();
  await expect(page).not.toHaveURL(/vue=/);

  // Several switches, and the plan, the breaks and the referentials were read
  // once, before the spy: nothing was fetched again — the table no longer
  // re-reads a count of persisted assignments either (issue #712).
  expect(appels.filter((chemin) => chemin.startsWith('/api/planning'))).toEqual([]);
  expect(appels.filter((chemin) => chemin === '/api/pauses')).toEqual([]);

  // Stand and Personne read their own reports: outside the no-refetch count above.
  await rendus.getByText('Stand').click();
  await expect(page.locator('.planning-grille').first()).toBeVisible();
  await expect(page).toHaveURL(/vue=stand/);

  await rendus.getByText('Personne').click();
  await expect(page.locator('.planning-grille').first()).toBeVisible();
  await expect(page).toHaveURL(/vue=personne/);

  await page.context().close();
});

test('the addresses of former screens lead to the right rendering, parameters included', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);

  await page.goto('/rail-jour?vue=libres');
  await expect(page).toHaveURL(/\/journee\?.*lignes=libres/);
  // The rail is the default mode: its address names neither the view nor the mode.
  await expect(page).not.toHaveURL(/vue=|mode=/);
  await expect(page.getByRole('radiogroup', { name: 'Lignes affichées' })).toContainText(
    'Mobilisables',
  );

  // The table, by its former screen and by its 3.x key: both rewritten to the mode.
  await page.goto('/day-calendar');
  await expect(page.locator('.jour-table')).toBeVisible();
  await expect(page).toHaveURL(/\/journee\?vue=jour&mode=tableau$/);
  await page.goto('/journee?vue=calendrier');
  await expect(page.locator('.jour-table')).toBeVisible();
  await expect(page).toHaveURL(/\/journee\?vue=jour&mode=tableau$/);
  await page.goto('/journee?vue=rail');
  await expect(page.locator('.rail-table')).toBeVisible();
  await expect(page).toHaveURL(/\/journee$/);

  // The views the page gave up (4.0) land on the screen holding their reading
  // today: no bookmark falls on an empty page.
  // The breaks and the meals are the Diagnostic's « Repas et pauses », the day kept.
  await page.goto(`/pauses?vue=sans-relais&jour=${SEED.jour}`);
  await expect(page).toHaveURL(
    new RegExp(String.raw`/diagnostic\?onglet=repas-pauses&jour=${SEED.jour}`),
  );
  await page.goto('/journee?vue=pauses');
  await expect(page).toHaveURL(/\/diagnostic\?onglet=repas-pauses/);
  await page.goto('/intendance');
  await expect(page).toHaveURL(/\/diagnostic\?onglet=repas-pauses/);
  await page.goto('/journee?axe=typologie');
  await expect(page).toHaveURL(/\/diagnostic\?onglet=besoin/);
  // The synthesis of « Par personne » is the Diagnostic's Charge, its sort kept.
  await page.goto('/journee?vue=personne&rendu=synthese&sort=heuresTotal&dir=desc');
  await expect(page).toHaveURL(/\/diagnostic\?onglet=charge&sort=heuresTotal&dir=desc/);
  await expect(page.locator('#contenu')).toContainText('Écart méd.');
  await page.goto(`/journee?vue=changements&date=${SEED.jour}`);
  // Envoyer is the default tab of Diffuser: its address drops `onglet` too.
  await expect(page).toHaveURL(new RegExp(String.raw`/publication\?jour=${SEED.jour}$`));
  // A day compared with another: the comparison is gone, the day stays.
  await page.goto(`/journee?date=${SEED.jour}&comparer=${SEED.jourSuivant}`);
  await expect(page.locator('.rail-table')).toBeVisible();
  await expect(page).not.toHaveURL(/comparer=/);
  // The 3.x keys of the two grids are rewritten to the 4.0 ones.
  await page.goto('/journee?axe=stand&vue=treemap');
  await expect(page).toHaveURL(/\/journee\?vue=stand&rendu=treemap/);
  await page.goto('/journee?axe=personne');
  await expect(page).toHaveURL(/\/journee\?vue=personne/);

  await page.goto('/carte-jour');
  await expect(page).toHaveURL(/\/journee\?.*vue=carte/);

  // The three screens the Planning page absorbed (issue #712).
  // The first day of the edition is the page's default: it writes no `date=`.
  await page.goto(`/calendar?date=${SEED.jour}&stand=${SEED.standDemandeur}`);
  await expect(page).toHaveURL(new RegExp(String.raw`/journee\?.*stand=${SEED.standDemandeur}`));

  await page.goto('/intendance');
  await expect(page).toHaveURL(/\/diagnostic$/);

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

  // The margin became a column of Besoin and a section of Risques: its « avant »
  // reading the column, its « après » and « tension » readings the section.
  await page.goto('/marge');
  await expect(page).toHaveURL(/\/diagnostic\?onglet=besoin/);
  await expect(page.locator('#contenu')).toContainText('Disponibles − sièges');
  await page.goto('/marge?mode=tension');
  await expect(page).toHaveURL(/\/diagnostic\?onglet=risques/);
  await expect(page.locator('#contenu')).toContainText('Tension, tranche par tranche');

  // Fragilité, « À former » and Tension are the three sections of Risques.
  await page.goto('/diagnostic?onglet=former');
  await expect(page).toHaveURL(/onglet=risques/);
  await expect(page.locator('#risques-former')).toContainText('À former');
  await page.goto('/diagnostic?onglet=fragilite');
  await expect(page).toHaveURL(/onglet=risques/);
  await expect(page.locator('#risques-fragilite')).toContainText('Fragilité');
  // A landing, not a view: once obeyed, `section` leaves the address.
  await page.goto('/diagnostic?onglet=besoin&section=former');
  await expect(page.locator('#risques-former')).toBeFocused();
  await expect(page).toHaveURL(/onglet=risques/);
  await expect(page).not.toHaveURL(/section=/);

  // The volumetry and the plan by typologie are sections of Besoin.
  await page.goto('/diagnostic?onglet=besoin');
  await expect(page.locator('#besoin-volumetrie')).toContainText('Volumétrie du problème');
  await expect(page.locator('#besoin-par-typologie')).toContainText('typologie par typologie');
  await page.context().close();
});

test('le diagnostic ne mène à aucune timeline et ne se dit plus à l’essai', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  for (const onglet of ['problemes', 'besoin', 'risques', 'charge']) {
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
test('le planning se lit sans défiler, sur les quatre vues', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.setViewportSize({ width: 1440, height: 900 });
  const premiers: Record<string, string> = {
    calendrier: '.jour-table tbody th',
    rail: '.rail-table tbody th',
    carte: '.leaflet-container',
    stand: '.planning-grille tbody th',
    personne: '.planning-grille tbody th',
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
  await page.goto('/journee?vue=jour&mode=rail');
  await expect(page.locator('.rail-table')).toBeVisible();
  const avantLaCarte = scripts.length;

  await page.getByRole('radiogroup', { name: 'Vue du planning' }).getByText('Carte').click();
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
  await onglets.getByText('Risques').click();
  await expect(page).toHaveURL(/onglet=risques/);
  const champ = page.getByLabel('Filtrer', { exact: true });
  await champ.fill('Alice');
  // The tab's own state sits next to the page's key, neither erasing the other.
  await expect(page).toHaveURL(/onglet=risques/);
  await expect(page).toHaveURL(/[?&]q=Alice/);

  await onglets.getByText('Besoin en animateurs').click();
  await expect(page).toHaveURL(/onglet=besoin/);
  await expect(page.locator('#contenu')).toContainText('Minimum retenu');

  await onglets.getByText('Risques').click();
  await expect(page).toHaveURL(/onglet=risques/);
  await expect(page.locator('#contenu')).toContainText('Tension, tranche par tranche');

  await onglets.getByText('Charge').click();
  await expect(page).toHaveURL(/onglet=charge/);
  await expect(page.locator('#contenu')).toContainText('Heures pour la paie (CSV)');
  await expect(page.locator('#contenu h1')).toHaveCount(1);
  // Charge reads its own name filter, not the one typed on Risques (`q`).
  await expect(page.getByLabel('Filtrer par nom')).toHaveValue('');
  await page.getByLabel('Filtrer par nom').fill('Bruno');
  await expect(page).toHaveURL(/[?&]nom=Bruno/);
  await onglets.getByText('Risques').click();
  await expect(page.getByLabel('Filtrer', { exact: true })).toHaveValue('Alice');

  await onglets.getByText('Repas et pauses').click();
  await expect(page).toHaveURL(/onglet=repas-pauses/);
  await expect(page.locator('#contenu')).toContainText('Enchaînements serrés entre deux stands');
  await expect(page.locator('#contenu')).toContainText('Combien de personnes mangent');
  await expect(
    page.locator('#contenu').getByRole('link', { name: 'Règles du planning' }),
  ).toBeVisible();
  await expect(page.locator('#contenu h1')).toHaveCount(1);
  await page.context().close();
});
