// axe-core over the representative screens (issue #46): what a template linter
// never sees — the contrast once the themes are applied, the landmarks, the
// accessible names computed at run time — measured in a real browser.
//
// A **frozen baseline**, not zero: each screen lists the rules it is known to
// break today, and the spec fails on any serious or critical violation outside
// that list. It fails too on a listed rule the screen no longer breaks — the
// baseline only shrinks, and an entry that outlived its debt is a line to
// delete, not a silence to keep. A spec born red would have been switched off
// within the week.
//
// The four screens of the espace animateur also run in the `mobile` project:
// a phone is how most animateurs open their link.

import AxeBuilder from '@axe-core/playwright';
import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { repartirDeLaReference } from './reference';
import {
  SEED,
  contexteAdmin,
  jetonDe,
  ouvrirSessionEspace,
  pageAdmin,
  publierPlanning,
  seedPlanning,
} from './support';

/** WCAG 2.1 A and AA — the level the RGAA 4.1 transposes. */
const NORMES = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

/**
 * The frozen baseline: per screen, the axe rules it is known to break at the
 * serious or critical level. Empty everywhere is the goal; an entry needs its
 * reason next to it.
 */
const LIGNE_DE_BASE: Record<string, string[]> = {
  // The « Accusé de réception » header is sortable and holds its help button:
  // Material renders the sort control as a `role="button"` around its content,
  // so the help button sits inside another control. Moving the help out of the
  // header is a change of layout of its own.
  '/animateurs': ['nested-interactive'],
};

let admin: APIRequestContext;
let jeton: string;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedPlanning(admin);
  await publierPlanning(admin);
  jeton = await jetonDe(admin, SEED.demandeur);
});

test.afterAll(async () => {
  await admin.dispose();
});

/**
 * Runs axe on the page as it stands and holds the result against the
 * baseline of `ecran`. Only serious and critical violations fail: moderate
 * and minor ones are reported in the message, not counted.
 */
async function verifier(page: Page, ecran: string): Promise<void> {
  // Material's ripple and focus indicators animate in: let the page settle so
  // a contrast is not measured mid-transition.
  await page.waitForLoadState('networkidle');
  const resultat = await new AxeBuilder({ page }).withTags(NORMES).analyze();
  const graves = resultat.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );
  const connues = new Set(LIGNE_DE_BASE[ecran] ?? []);
  const nouvelles = graves.filter((violation) => !connues.has(violation.id));
  const resolues = [...connues].filter((id) => !graves.some((violation) => violation.id === id));
  const detail = nouvelles
    .map(
      (violation) =>
        `${violation.id} (${violation.impact}) — ${violation.help}\n` +
        violation.nodes
          .slice(0, 5)
          .map((noeud) => `    ${noeud.target.join(' ')}`)
          .join('\n'),
    )
    .join('\n');
  expect(nouvelles, `${ecran} : violation(s) hors ligne de base\n${detail}`).toEqual([]);
  expect(
    resolues,
    `${ecran} : règle(s) de la ligne de base qui ne sont plus violées — à retirer de LIGNE_DE_BASE`,
  ).toEqual([]);
}

test.describe('accessibilité — administration', () => {
  test.beforeEach(({}, testInfo) => {
    // The admin interface assumes a desktop screen; the mobile project plays
    // the espace animateur only.
    test.skip(testInfo.project.name === 'mobile', 'interface de bureau');
  });

  const ECRANS_ADMIN = [
    '/',
    '/solveur',
    '/animateurs',
    '/ouvertures',
    '/regles',
    '/regles?onglet=qualite',
    '/journee',
    '/parametres',
  ];
  for (const ecran of ECRANS_ADMIN) {
    test(`${ecran} ne porte aucune violation grave hors ligne de base`, async ({ browser }) => {
      const page = await pageAdmin(browser, admin);
      try {
        await page.goto(ecran);
        await expect(page.locator('main#contenu h1')).toBeVisible();
        await verifier(page, ecran);
      } finally {
        await page.context().close();
      }
    });
  }
});

test.describe('accessibilité — pages publiques', () => {
  test.beforeEach(({}, testInfo) => {
    test.skip(testInfo.project.name === 'mobile', 'jouées une fois, sur le bureau');
  });

  const ECRANS_PUBLICS = [
    '/login',
    '/mentions-legales',
    '/politique-confidentialite',
    '/conditions-utilisation',
    '/declaration-accessibilite',
  ];
  for (const ecran of ECRANS_PUBLICS) {
    test(`${ecran} ne porte aucune violation grave hors ligne de base`, async ({ page }) => {
      await page.goto(ecran);
      await expect(page.locator('h1')).toBeVisible();
      await verifier(page, ecran);
    });
  }
});

test.describe('accessibilité — espace animateur', () => {
  const ONGLETS = [
    { ecran: 'espace/planning', suffixe: '' },
    { ecran: 'espace/echanges', suffixe: '/echanges' },
    { ecran: 'espace/disponibilites', suffixe: '/disponibilites' },
    { ecran: 'espace/aide', suffixe: '/aide' },
  ];
  for (const { ecran, suffixe } of ONGLETS) {
    test(`${ecran} ne porte aucune violation grave hors ligne de base`, async ({ page }) => {
      await ouvrirSessionEspace(page.request, jeton, `${SEED.demandeur}@example.org`);
      await page.goto(`/animateur/${jeton}${suffixe}`);
      await expect(page.locator('main#contenu h1')).toBeAttached();
      await expect(page.getByText('Alice E2E')).toBeVisible();
      await verifier(page, ecran);
    });
  }
});
