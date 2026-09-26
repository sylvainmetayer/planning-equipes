// A rule that penalises everything for lack of data is a floor (issue #495):
// its points are a constant no solve will move, and until now nothing said so.
// The seeded referential declares no wish on anybody, so after a real short
// solve `souhaitsIncompatibles` has matched every filled seat. What only a
// browser can prove is the last mile: the rule's row on « Règles du planning »
// carries the mark, its panel names the missing data, and the link lands on
// the screen where it is entered.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import {
  AnimateurSeed,
  CreneauSeed,
  StandSeed,
  contexteAdmin,
  shiftDate,
  lancerSolve,
  pageAdmin,
  seedReferentielSolveur,
} from './support';
import { repartirDeLaReference } from './reference';

/** MEDIUM, per filled seat — a floor as soon as nobody declared a wish. */
const AT_FLOOR = 'souhaitsIncompatibles';

// Same shape as solveur.spec.ts, for the same reasons: one créneau per day,
// and a real search space so the deterministic solve reaches zero hard.
const ANIMATEURS: AnimateurSeed[] = [
  { id: 'SOLV-P', prenom: 'Paula', nom: 'Plancher', dateNaissance: '1990-01-01' },
  { id: 'SOLV-Q', prenom: 'Quentin', nom: 'Plancher', dateNaissance: '1991-02-02' },
  { id: 'SOLV-R', prenom: 'Rita', nom: 'Plancher', dateNaissance: '1992-03-03' },
  { id: 'SOLV-T', prenom: 'Tom', nom: 'Plancher', dateNaissance: '1993-04-04' },
  { id: 'SOLV-U', prenom: 'Uma', nom: 'Plancher', dateNaissance: '1994-05-05' },
  { id: 'SOLV-V', prenom: 'Victor', nom: 'Plancher', dateNaissance: '1995-06-06' },
];
const STANDS: StandSeed[] = [
  { id: 'SOLV-S1', nom: 'Stand Plancher un', effectif: 1 },
  { id: 'SOLV-S2', nom: 'Stand Plancher deux', effectif: 1 },
];
const CRENEAUX: CreneauSeed[] = [
  { id: 987301, date: shiftDate('2026-07-12'), debut: '10:00', fin: '12:00' },
  { id: 987302, date: shiftDate('2026-07-13'), debut: '10:00', fin: '12:00' },
  { id: 987303, date: shiftDate('2026-07-14'), debut: '10:00', fin: '12:00' },
];

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  await seedReferentielSolveur(admin, ANIMATEURS, STANDS, CRENEAUX);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** The row of one rule, located by its short label — the technical name is never shown. */
function ligne(page: Page, libelle: string) {
  return page
    .locator('table.regles-table tbody tr')
    .filter({ has: page.getByRole('rowheader', { name: libelle, exact: true }) });
}

test('une règle qui pénalise tout faute de donnée porte la marque, nomme la donnée et mène à sa saisie', async ({
  browser,
}) => {
  test.slow();
  const job = await lancerSolve(admin, 3);
  expect(job.result?.diagnostic.hardScore).toBe(0);

  // Server side first: the floor is read, the score net of it differs from
  // the raw one, and the rule is still active — reported, not decided.
  const catalogue = await (await admin.get('/api/constraints')).json();
  const regle = catalogue.contraintes.find((c: { name: string }) => c.name === AT_FLOOR);
  expect(regle.actif).toBe(true);
  expect(regle.plancher?.motif).toBe('SOUHAITS');
  expect(regle.plancher?.lien).toBe('/animateurs');
  expect(regle.plancher?.ratio).toBeGreaterThanOrEqual(0.95);
  expect(catalogue.scoreHorsPlancher).not.toBe(catalogue.scoreGlobal);

  const page = await pageAdmin(browser, admin);
  await page.goto(`/regles?regle=${AT_FLOOR}`);
  // The address names the rule: its tab opens, and its panel with it.
  const rangee = ligne(page, regle.libelleCourt);
  await expect(rangee).toBeVisible();
  await expect(rangee.locator('.regles-plancher')).toBeVisible();
  // Only the floored rule carries the mark: the equity rule has no per-item
  // reading, so it cannot be one.
  await expect(ligne(page, 'Équilibre de la charge').locator('.regles-plancher')).toHaveCount(0);

  const panneau = page.locator('.regles-panneau');
  await expect(panneau).toContainText('souhait');
  await expect(panneau).toContainText('éléments évalués sont en écart');

  // The link is what turns the mark into an action: it lands on the fiches.
  await panneau.getByRole('link', { name: 'Saisir la donnée' }).click();
  await expect(page).toHaveURL(/\/animateurs$/);

  await page.close();
});
