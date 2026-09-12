// Smoke sweep of the whole admin frontend: every route of the drawer renders
// behind the session, plus the runtime language toggle. Catches a page whose
// lazy chunk, store preload or template breaks — the class of regression a
// unit test on one service never sees.

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

/**
 * One entry per admin route (`app.routes.ts`). `marker` is a stable French
 * label of the page; routes without one just have to render *something* in
 * the shell's main region. `sheet` is a class the route's own stylesheet
 * defines: since #468 a page's CSS travels with its lazy chunk, and nothing
 * but a browser can tell that the chunk brought it — the unit tests render
 * without CSS, and a `styleUrl` pointing at the wrong file, or a missing
 * `ViewEncapsulation.None`, left the whole CI green.
 */
const ROUTES: { path: string; marker?: string; sheet?: string }[] = [
  { path: '/', marker: "État de l'édition", sheet: 'accueil-ligne' },
  { path: '/solveur', marker: 'Calculer le planning', sheet: 'solver-volumetry' },
  { path: '/notifications', sheet: 'notification-jour' },
  // One page over four tabs, each visited: a tab's stylesheet travels with the
  // page's chunk, and only a browser can tell that it arrived.
  { path: '/diagnostic', marker: 'Diagnostic', sheet: 'diagnostic-onglets' },
  { path: '/diagnostic?onglet=problemes', sheet: 'probleme-counts' },
  { path: '/constraints', sheet: 'constraint-grid' },
  { path: '/echanges', marker: 'Échanges de créneaux', sheet: 'espace-demande-horsgroupe' },
  { path: '/instantanes', sheet: 'snapshot-auto-chip' },
  { path: '/aide', marker: "Aide à l'utilisation", sheet: 'aide-search' },
  { path: '/editions', marker: 'Nouvelle édition', sheet: 'edition-nom-input' },
  { path: '/stands', marker: 'Stands (' },
  { path: '/emplacements' },
  { path: '/animateurs', marker: 'Animateurs (', sheet: 'competence-row' },
  { path: '/competences', marker: 'Compétences', sheet: 'competences-legende' },
  { path: '/creneaux', marker: 'Créneaux (', sheet: 'creneau-probleme' },
  { path: '/typologies', marker: 'Typologies (' },
  { path: '/import-animateurs', marker: 'Import des animateurs', sheet: 'import-compteurs' },
  { path: '/import-grille-stands', sheet: 'import-compteurs' },
  { path: '/calendar', marker: 'Calendrier des affectations', sheet: 'calendar-nav' },
  // Same for the day and its four renderings.
  { path: '/journee', marker: 'Journée', sheet: 'journee-toolbar' },
  { path: '/journee?vue=calendrier', sheet: 'day-calendar-grid' },
  { path: '/hours', marker: 'Heures planifiées par animateur', sheet: 'hours-total-row' },
  { path: '/equite', marker: 'Équité par animateur', sheet: 'equite-synthese-row' },
  { path: '/ouvertures', marker: 'Ouvertures des stands', sheet: 'ouvertures-synthese' },
  { path: '/diagnostic?onglet=besoin', marker: 'Minimum retenu', sheet: 'staffing-summary' },
  { path: '/diagnostic?onglet=banc', marker: 'Banc de touche', sheet: 'banc-controls' },
  { path: '/diagnostic?onglet=fragilite', marker: 'Fragilité', sheet: 'fragilite-message' },
  { path: '/jour-j', marker: 'Mode jour J', sheet: 'jour-j-entete' },
  { path: '/repos', marker: 'Jours de repos', sheet: 'repos-toolbar' },
  { path: '/heatmap', sheet: 'heatmap-toolbar' },
  { path: '/timeline', marker: 'Timeline animateur', sheet: 'timeline-toolbar' },
  { path: '/journee?vue=rail', marker: 'Mobilisables', sheet: 'rail-toolbar' },
  {
    path: '/journee?vue=carte',
    marker: 'Emplacements à cette heure-là',
    sheet: 'carte-jour-curseur',
  },
  { path: '/comparateur', sheet: 'comparateur-selection' },
  { path: '/historique', sheet: 'historique-controles' },
  { path: '/journee?vue=pauses', sheet: 'pauses-message' },
  { path: '/disponibilites', sheet: 'espace-dispo-intro' },
  { path: '/kpi' },
  { path: '/graphe', sheet: 'graphe-corps' },
  { path: '/ad-hoc-constraints' },
  { path: '/verrouillages', marker: 'Verrouiller une partie du planning' },
  { path: '/parametres', marker: 'Paramètres légaux', sheet: 'scenario-select' },
  { path: '/mcp-client', marker: 'Se connecter au serveur MCP', sheet: 'mcp-pre' },
  { path: '/debug', marker: 'Validateur YAML', sheet: 'debug-date-du-jour' },
];

/** True when a loaded stylesheet has a rule naming `.${classe}` — the route's chunk brought its CSS. */
async function feuilleChargee(
  page: import('@playwright/test').Page,
  classe: string,
): Promise<boolean> {
  return page.evaluate((selecteur) => {
    return Array.from(document.styleSheets).some((feuille) => {
      try {
        return Array.from(feuille.cssRules).some(
          (regle) => regle instanceof CSSStyleRule && regle.selectorText.includes(selecteur),
        );
      } catch {
        return false;
      }
    });
  }, `.${classe}`);
}

test('chaque page du menu admin se charge et affiche son contenu', async ({ browser }) => {
  test.slow();
  const page = await pageAdmin(browser, admin);
  await page.goto('/');
  await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toBeVisible();

  for (const route of ROUTES) {
    await page.goto(route.path, { waitUntil: 'domcontentloaded' });
    const contenu = page.locator('#contenu');
    if (route.marker) {
      await expect(contenu, `page ${route.path}`).toContainText(route.marker);
    } else {
      await expect(contenu, `page ${route.path}`).toContainText(/\S/);
    }
    if (route.sheet) {
      expect(await feuilleChargee(page, route.sheet), `feuille de ${route.path}`).toBe(true);
    }
  }
  await page.context().close();
});

/**
 * The public legal pages render outside the admin shell, on their own route
 * sheet; no other spec visits them.
 */
test('les pages légales publiques se chargent avec leur feuille', async ({ page }) => {
  for (const path of ['/conditions-utilisation', '/politique-confidentialite']) {
    await page.goto(path, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('main, .mentions-page').first(), `page ${path}`).toContainText(/\S/);
    expect(await feuilleChargee(page, 'mentions-page-toolbar'), `feuille de ${path}`).toBe(true);
  }
});

/**
 * The reference screens whose rows carry a delete button, and which the seeded
 * dataset actually fills.
 */
const ROUTES_AVEC_ACTIONS = ['/stands', '/animateurs', '/creneaux'];

test("la colonne d'actions reste visible sur un écran étroit", async ({ browser }) => {
  // These tables are wider than a laptop screen — /animateurs alone overflows
  // by ~260 px at 1000 px wide — and the actions column is the last one, so the
  // delete button used to sit off-screen until you scrolled the table
  // sideways. It is pinned to the right edge (`stickyEnd`); what this checks is
  // that it is reachable without that scroll, which is the whole point.
  const page = await pageAdmin(browser, admin);
  await page.setViewportSize({ width: 900, height: 900 });

  for (const route of ROUTES_AVEC_ACTIONS) {
    await page.goto(route);
    const cellule = page.locator('td.row-actions').first();
    await expect(cellule, `page ${route}`).toBeVisible();

    const dernierBouton = cellule.locator('button').last();
    const boite = await dernierBouton.boundingBox();
    expect(boite, `dernier bouton de ${route}`).not.toBeNull();
    expect(boite!.x + boite!.width, `dernier bouton hors écran sur ${route}`).toBeLessThanOrEqual(
      900,
    );
    expect(boite!.x, `dernier bouton hors écran à gauche sur ${route}`).toBeGreaterThanOrEqual(0);
  }
  await page.context().close();
});

test('le catalogue des contraintes documente le verrouillage des échanges', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/constraints');
  await expect(page.locator('#contenu')).toContainText(
    'Un échange validé est figé sur son créneau',
  );
  await page.context().close();
});

test("la bascule de langue passe l'interface en anglais", async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/');
  await page.getByRole('button', { name: 'Changer de langue' }).click();
  // The switch reloads the app with the English catalog.
  await expect(page.getByRole('link', { name: 'Swaps' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Staff', exact: true })).toBeVisible();
  await page.context().close();
});

/**
 * The drawer opens in its simple mode: the expert screens are not listed, and
 * a menu shorter by more than a third is the point — the technical tools, the
 * deep diagnostics and the specialised renderings of the plan all sit behind
 * the toggle. The toggle at the top of the drawer
 * shows them, the choice survives a reload, and a screen reached by its
 * address is listed for the time of the visit — the entry says where the
 * reader landed, without switching the mode under them.
 */
test('le menu simple masque les écrans de diagnostic, et les montre sur demande', async ({
  browser,
}) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/stands');
  const navigation = page.getByRole('navigation', { name: 'Navigation principale' });
  await expect(navigation.getByRole('link', { name: 'Stands', exact: true })).toBeVisible();
  await expect(navigation.getByRole('link', { name: 'Débogage' })).toHaveCount(0);
  await expect(navigation.getByRole('link', { name: 'Historique' })).toHaveCount(0);
  // A specialised rendering, not only the technical tools.
  await expect(navigation.getByRole('link', { name: 'Heatmap de charge' })).toHaveCount(0);
  await expect(navigation.getByRole('link', { name: 'Heures', exact: true })).toBeVisible();

  const bascule = navigation.getByRole('button', { name: /Menu simple/ });
  await expect(bascule).toHaveAttribute('aria-pressed', 'false');
  await bascule.click();
  await expect(navigation.getByRole('link', { name: 'Débogage' })).toBeVisible();
  await expect(navigation.getByRole('link', { name: 'Historique' })).toBeVisible();
  await expect(navigation.getByRole('link', { name: 'Heatmap de charge' })).toBeVisible();
  await expect(navigation.getByRole('button', { name: /Menu avancé/ })).toHaveAttribute(
    'aria-pressed',
    'true',
  );

  await page.reload();
  await expect(navigation.getByRole('link', { name: 'Débogage' })).toBeVisible();

  await navigation.getByRole('button', { name: /Menu avancé/ }).click();
  await expect(navigation.getByRole('link', { name: 'Débogage' })).toHaveCount(0);

  // Reached by its address anyway: the route is open whatever the menu lists.
  await page.goto('/debug');
  await expect(page.locator('#contenu')).toContainText('Validateur YAML');
  await expect(navigation.getByRole('link', { name: 'Débogage' })).toBeVisible();
  await expect(navigation.getByRole('link', { name: 'Historique' })).toHaveCount(0);
  await page.context().close();
});
