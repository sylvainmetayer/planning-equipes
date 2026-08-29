// Smoke sweep of the whole admin frontend: every route of the drawer renders
// behind the session, plus the runtime language toggle. Catches a page whose
// lazy chunk, store preload or template breaks — the class of regression a
// unit test on one service never sees.

import { APIRequestContext, expect, test } from '@playwright/test';
import { contexteAdmin, pageAdmin, seedPlanning } from './support';

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await seedPlanning(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

/**
 * One entry per admin route (`app.routes.ts`). `marker` is a stable French
 * label of the page; routes without one just have to render *something* in
 * the shell's main region.
 */
const ROUTES: { path: string; marker?: string }[] = [
  { path: '/', marker: 'Résoudre avec Timefold' },
  { path: '/notifications' },
  { path: '/problemes' },
  { path: '/constraints' },
  { path: '/echanges', marker: 'Échanges de créneaux' },
  { path: '/instantanes' },
  { path: '/aide', marker: "Aide à l'utilisation" },
  { path: '/editions', marker: 'Nouvelle édition' },
  { path: '/stands', marker: 'Stands (' },
  { path: '/emplacements' },
  { path: '/animateurs', marker: 'Animateurs (' },
  { path: '/creneaux', marker: 'Créneaux (' },
  { path: '/typologies', marker: 'Typologies (' },
  { path: '/calendar', marker: 'Calendrier des affectations' },
  { path: '/day-calendar' },
  { path: '/hours', marker: 'Heures planifiées par animateur' },
  { path: '/ouvertures', marker: 'Ouvertures des stands' },
  { path: '/staffing', marker: 'Besoin minimum en effectif' },
  { path: '/fragilite', marker: 'Fragilité du planning' },
  { path: '/jour-j', marker: 'Mode jour J' },
  { path: '/heatmap' },
  { path: '/timeline', marker: 'Timeline animateur' },
  { path: '/ad-hoc-constraints' },
  { path: '/verrouillages', marker: 'Verrouiller une partie du planning' },
  { path: '/parametres', marker: 'Paramètres de découpage' },
  { path: '/mcp-client', marker: 'Se connecter au serveur MCP' },
  { path: '/debug', marker: 'Validateur YAML' }
];

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
  }
  await page.context().close();
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
    expect(boite!.x + boite!.width, `dernier bouton hors écran sur ${route}`).toBeLessThanOrEqual(900);
    expect(boite!.x, `dernier bouton hors écran à gauche sur ${route}`).toBeGreaterThanOrEqual(0);
  }
  await page.context().close();
});

test('le catalogue des contraintes documente le verrouillage des échanges', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/constraints');
  await expect(page.locator('#contenu')).toContainText('Un échange validé est figé sur son créneau');
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
