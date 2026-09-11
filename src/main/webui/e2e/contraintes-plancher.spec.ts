// A rule that penalises everything for lack of data is a floor (issue #495):
// its points are a constant no solve will move, and until now nothing said so.
// The seeded referential declares no wish on anybody, so after a real short
// solve `souhaitsIncompatibles` has matched every filled seat. What only a
// browser can prove is the last mile: the card carries the badge, names the
// missing data, and the link lands on the screen where it is entered.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import {
  AnimateurSeed,
  CreneauSeed,
  StandSeed,
  contexteAdmin,
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
  { id: 987301, date: '2026-07-12', debut: '10:00', fin: '12:00' },
  { id: 987302, date: '2026-07-13', debut: '10:00', fin: '12:00' },
  { id: 987303, date: '2026-07-14', debut: '10:00', fin: '12:00' },
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

/** The card of one constraint, located by the technical name it displays. */
function carte(page: Page, nom: string) {
  return page.locator('.constraint-card').filter({ has: page.getByText(nom, { exact: true }) });
}

test('une règle qui pénalise tout faute de donnée porte le badge, nomme la donnée et mène à sa saisie', async ({
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
  await page.goto('/constraints');
  const cartePlancher = carte(page, AT_FLOOR);
  await expect(cartePlancher).toBeVisible();
  await expect(cartePlancher.getByText('Mesure une donnée absente')).toBeVisible();
  await expect(cartePlancher).toContainText('souhait');
  // Only the floored rule carries the badge: the equity rule has no per-item
  // reading, so it cannot be one.
  await expect(carte(page, 'equilibrerCharge').getByText('Mesure une donnée absente')).toHaveCount(
    0,
  );
  // The screen says what the run is worth once the constant is taken out.
  await expect(page.getByText(/Hors plancher :/)).toBeVisible();

  // The link is what turns the badge into an action: it lands on the fiches.
  await cartePlancher.getByRole('link', { name: 'Saisir la donnée' }).click();
  await expect(page).toHaveURL(/\/animateurs$/);

  await page.close();
});

test('trier par plancher met la règle au plancher en tête de sa catégorie', async ({ browser }) => {
  const page = await pageAdmin(browser, admin);
  await page.goto('/constraints');
  await expect(carte(page, AT_FLOOR)).toBeVisible();

  await page.getByRole('switch', { name: 'Trier par plancher' }).click();

  // The dosable rules are one fieldset; a floored one comes first in it. Not
  // necessarily the one named above: the reference scenario floors more than
  // one rule (no wish, no referent), and two floors at 100 % tie.
  const premiere = page
    .locator('.constraint-fieldset')
    .filter({ has: carte(page, AT_FLOOR) })
    .locator('.constraint-card')
    .first();
  await expect(premiere.getByText('Mesure une donnée absente')).toBeVisible();

  await page.close();
});
