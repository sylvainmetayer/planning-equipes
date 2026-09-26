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
  { path: '/publication', marker: 'Diffusion du planning', sheet: 'publication-etat' },
  // The former Notifications page lands on « À traiter aujourd'hui ».
  { path: '/notifications', marker: "À traiter aujourd'hui", sheet: 'notification-jour' },
  // One page over four tabs, each visited: a tab's stylesheet travels with the
  // page's chunk, and only a browser can tell that it arrived.
  { path: '/diagnostic', marker: 'Diagnostic', sheet: 'diagnostic-onglets' },
  { path: '/diagnostic?onglet=problemes', sheet: 'probleme-counts' },
  { path: '/constraints', sheet: 'constraint-list' },
  { path: '/echanges', marker: 'Échanges de créneaux', sheet: 'espace-demande-horsgroupe' },
  { path: '/instantanes', sheet: 'snapshot-auto-chip' },
  { path: '/aide', marker: "Aide à l'utilisation", sheet: 'aide-search' },
  { path: '/nouveautes', marker: 'Nouveautés', sheet: 'news-release' },
  { path: '/editions', marker: 'Nouvelle édition', sheet: 'edition-nom-input' },
  { path: '/stands', marker: 'Stands (' },
  { path: '/stands?onglet=lieux', marker: 'Lieux (', sheet: 'lieux-map-host' },
  { path: '/stands/E2E-S1', marker: 'Horaires et effectifs', sheet: 'stand-grille' },
  { path: '/animateurs', marker: 'Animateurs (', sheet: 'competence-row' },
  { path: '/animateurs/E2E-A', marker: 'Identité et contact', sheet: 'fiche-section' },
  { path: '/competences', marker: 'Compétences', sheet: 'competences-legende' },
  { path: '/creneaux', marker: 'Créneaux (', sheet: 'creneau-probleme' },
  { path: '/typologies', marker: 'Typologies (' },
  // Fichiers: three tabs, and under Importer one card per file (the
  // referentials share a card, the two older imports are rendered as they are).
  { path: '/fichiers', marker: 'Fichiers', sheet: 'fichiers-onglets' },
  { path: '/fichiers?cible=typologies', sheet: 'imports-onglets' },
  { path: '/fichiers?cible=stands' },
  // Les deux cartes du calendrier partagent la carte des référentiels, et
  // leur marqueur vise ce que seule la carte visée dit.
  { path: '/fichiers?cible=creneaux', marker: 'heureDebut' },
  { path: '/fichiers?cible=journees-types', marker: 'relais repas' },
  {
    // Le titre de l'écran est celui des onglets : le marqueur vise ce que
    // seule cette carte-ci dit.
    path: '/fichiers?cible=animateurs',
    marker: 'date de naissance est obligatoire',
    sheet: 'import-compteurs',
  },
  { path: '/fichiers?cible=grille-stands', sheet: 'import-compteurs' },
  { path: '/fichiers?cible=scenario', marker: 'Un fichier scénario' },
  { path: '/fichiers?cible=exemples', marker: 'Charger cet exemple', sheet: 'exemples-select' },
  {
    path: '/fichiers?cible=verifier',
    marker: "Vérifier un fichier sans l'importer",
    sheet: 'yaml-validator-result',
  },
  { path: '/fichiers?onglet=exporter', marker: 'Export CSV', sheet: 'export-csv-liste' },
  { path: '/fichiers?onglet=archive', marker: "Archive de fin d'événement" },
  // The former addresses land on their tab.
  { path: '/imports?onglet=animateurs', marker: 'date de naissance est obligatoire' },
  { path: '/exports', marker: 'Export CSV' },
  { path: '/calendar', marker: 'Calendrier des affectations', sheet: 'calendar-nav' },
  // Same for the day and its four renderings.
  { path: '/journee', marker: 'Journée', sheet: 'journee-toolbar' },
  { path: '/journee?vue=calendrier', sheet: 'day-calendar-grid' },
  { path: '/hours', marker: 'Heures planifiées par animateur', sheet: 'hours-total-row' },
  { path: '/equite', marker: 'Équité par animateur', sheet: 'equite-synthese-row' },
  { path: '/intendance', marker: 'Intendance des repas', sheet: 'intendance-total' },
  {
    path: '/typologies-planning',
    marker: 'Qui tient quoi, et pour quel volume',
    sheet: 'typologies-barre-piste',
  },
  { path: '/ouvertures', marker: 'Horaires des stands', sheet: 'ouvertures-synthese' },
  { path: '/diagnostic?onglet=besoin', marker: 'Minimum retenu', sheet: 'staffing-summary' },
  { path: '/diagnostic?onglet=former', marker: 'À former' },
  { path: '/diagnostic?onglet=fragilite', marker: 'Fragilité', sheet: 'fragilite-message' },
  { path: '/jour-j', marker: 'Mode jour J', sheet: 'jour-j-entete' },
  { path: '/repos', marker: 'Jours de repos', sheet: 'repos-toolbar' },
  { path: '/heatmap', sheet: 'heatmap-toolbar' },
  { path: '/repartition-heures', marker: 'Répartition des heures', sheet: 'repartition-toolbar' },
  { path: '/marge', marker: 'Marge disponible', sheet: 'marge-synthese' },
  // The former timeline lands on a fiche's planning section; its own sheet is gone.
  { path: '/animateurs/E2E-A?section=timeline', marker: 'Planning', sheet: 'timeline-day' },
  { path: '/journee?vue=rail', marker: 'Mobilisables', sheet: 'rail-toolbar' },
  {
    path: '/journee?vue=carte',
    marker: 'Emplacements à cette heure-là',
    sheet: 'carte-jour-curseur',
  },
  { path: '/comparateur', sheet: 'comparateur-selection' },
  { path: '/historique', sheet: 'historique-controles' },
  { path: '/journee?vue=pauses', sheet: 'pauses-message' },
  {
    path: '/journee?vue=changements',
    marker: 'Changements de la journée',
    sheet: 'journee-changements-toolbar',
  },
  { path: '/disponibilites', sheet: 'espace-dispo-intro' },
  { path: '/kpi' },
  { path: '/graphe', sheet: 'graphe-corps' },
  { path: '/ad-hoc-constraints' },
  { path: '/verrouillages', marker: 'Verrouiller une partie du planning' },
  { path: '/consignes', marker: 'Consignes', sheet: 'consigne-prereglage' },
  // Paramètres and Débogage are tab pages: the default tab
  // carries the marker and the route's own stylesheet, and one other tab of
  // each is visited to prove the `?onglet=` addresses land where they say.
  { path: '/parametres', marker: 'Paramètres légaux', sheet: 'parametres-onglets' },
  { path: '/parametres?onglet=edition', marker: 'Typologie ninja', sheet: 'parametres-renvois' },
  { path: '/parametres?onglet=instance', marker: 'Sauvegarde automatique' },
  // Its former name is still read.
  { path: '/parametres?onglet=globaux', marker: 'Sauvegarde automatique' },
  { path: '/mcp-client', marker: 'Se connecter au serveur MCP', sheet: 'mcp-pre' },
  { path: '/debug', marker: 'Dernière analyse', sheet: 'debug-onglets' },
  { path: '/debug?onglet=verifications', marker: 'Envoyer un mail de test' },
  // The two tabs Débogage gave up land on their Fichiers cards.
  { path: '/debug?onglet=donnees', marker: 'Charger cet exemple' },
  { path: '/debug?onglet=yaml', marker: "Vérifier un fichier sans l'importer" },
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

test('le pied de page admin indique la version en cours', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/');
  const pied = page.locator('.app-version-footer');
  await expect(pied).toContainText('Version');
  // The link points at the exact revision the build was cut from: the release
  // page when it sits on a tag, the commit otherwise.
  await expect(pied.locator('a[target="_blank"]')).toHaveAttribute(
    'href',
    /\/(commit|releases\/tag)\/\S/,
  );
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
  await expect(page.getByRole('link', { name: 'Swaps', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Staff', exact: true })).toBeVisible();
  await page.context().close();
});

/**
 * One menu for everybody (#709): no mode hides a step of the cycle, the groups
 * follow the moments of an edition, Débogage is in no group but answers at its
 * address and in the palette, and the legal pages sit at the foot (#706).
 */
test('un seul menu par moment du cycle, Débogage hors menu mais servi', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/stands');
  const navigation = page.getByRole('navigation', { name: 'Navigation principale' });
  for (const groupe of [
    'Accueil',
    'Planning',
    'Préparer',
    'Construire',
    'Diffuser',
    "Aujourd'hui",
    'Administrer',
  ]) {
    // The folding chevron is a ligature, read as part of the button's name.
    await expect(navigation.getByRole('button', { name: new RegExp(`^${groupe}`) })).toBeVisible();
  }
  // Once hidden by the simple mode, now listed down their group.
  await expect(navigation.getByRole('link', { name: 'Heatmap de charge' })).toBeVisible();
  await expect(navigation.getByRole('link', { name: 'Historique' })).toBeVisible();
  await expect(navigation.getByRole('button', { name: /Menu simple|Menu avancé/ })).toHaveCount(0);
  await expect(navigation.getByRole('link', { name: 'Débogage' })).toHaveCount(0);
  await expect(navigation.getByRole('link', { name: 'Accessibilité' })).toBeVisible();

  // Reached by its address anyway, and by the palette.
  await page.goto('/debug');
  await expect(page.locator('#contenu')).toContainText('Dernière analyse');
  await expect(navigation.getByRole('link', { name: 'Débogage' })).toHaveCount(0);

  await page.goto('/stands');
  // The palette listens once the shell is up: a key pressed before it is lost.
  await expect(navigation).toBeVisible();
  await expect(page.locator('#contenu')).toContainText('Stands');
  await page.keyboard.press('Control+k');
  const palette = page.getByRole('dialog');
  await palette.getByRole('combobox').fill('swagger');
  await palette
    .getByRole('option', { name: /Débogage/ })
    .first()
    .click();
  await expect(page).toHaveURL(/\/debug/);

  // The bench left the Diagnostic for the Siège panel of the Journée: the word
  // a reader remembers still leads there.
  await page.keyboard.press('Control+k');
  await page.getByRole('dialog').getByRole('combobox').fill('banc');
  await page
    .getByRole('dialog')
    .getByRole('option', { name: /^Journée/ })
    .first()
    .click();
  await expect(page).toHaveURL(/\/journee/);

  // A view of the page already on screen: the router reuses the page, which
  // must follow its address rather than keep the view it was built with.
  await page.goto('/ad-hoc-constraints');
  await expect(page.locator('#contenu')).toContainText('Ajustements manuels');
  await expect(page.locator('#contenu app-reseau-paires-vue')).toHaveCount(0);
  await page.keyboard.press('Control+k');
  await page.getByRole('dialog').getByRole('combobox').fill('réseau');
  await page
    .getByRole('dialog')
    .getByRole('option', { name: /Ajustements manuels › Réseau/ })
    .click();
  await expect(page).toHaveURL(/\/ad-hoc-constraints\?vue=reseau/);
  await expect(page.locator('#contenu app-reseau-paires-vue')).toHaveCount(1);
  await page.context().close();
});
