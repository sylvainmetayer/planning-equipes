// Issue #274: a solve used to announce its own score and never the one it
// replaced, so re-solving an edition that already held a good plan read as a
// success — "0 hard" — while quietly costing points nobody was shown. These
// specs drive the three states through the real stack: nothing to compare
// (first solve), a comparison that must NOT cry wolf (nothing got worse), and
// a genuine regression the screen has to announce and be able to undo.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import {
  AnimateurSeed,
  CreneauSeed,
  StandSeed,
  contexteAdmin,
  lancerSolve,
  pageAdmin,
  planningPersiste,
  seedReferentielSolveur,
} from './support';
import { repartirDeLaReference } from './reference';

// Same problem shape as solveur.spec.ts, and for the same reasons: one créneau
// per day (the hard pauseMinimaleEntreVacations forbids two same-day
// vacations), and a real search space rather than a toy one.
const C1 = 987301;
const C2 = 987302;
const C3 = 987303;
const C4 = 987304;

const ANIMATEURS: AnimateurSeed[] = [
  { id: 'SOLV-DEG-A', prenom: 'Alice', nom: 'Degrade', dateNaissance: '1990-01-01' },
  { id: 'SOLV-DEG-B', prenom: 'Bruno', nom: 'Degrade', dateNaissance: '1991-02-02' },
  { id: 'SOLV-DEG-C', prenom: 'Chloé', nom: 'Degrade', dateNaissance: '1992-03-03' },
  { id: 'SOLV-DEG-D', prenom: 'David', nom: 'Degrade', dateNaissance: '1993-04-04' },
  { id: 'SOLV-DEG-E', prenom: 'Elena', nom: 'Degrade', dateNaissance: '1994-05-05' },
  { id: 'SOLV-DEG-F', prenom: 'Farid', nom: 'Degrade', dateNaissance: '1995-06-06' },
  { id: 'SOLV-DEG-G', prenom: 'Gaby', nom: 'Degrade', dateNaissance: '1996-07-07' },
  { id: 'SOLV-DEG-H', prenom: 'Hugo', nom: 'Degrade', dateNaissance: '1997-08-08' },
];
const STANDS: StandSeed[] = [
  { id: 'SOLV-DEG-S1', nom: 'Stand Degrade un', effectif: 1 },
  { id: 'SOLV-DEG-S2', nom: 'Stand Degrade deux', effectif: 1 },
  { id: 'SOLV-DEG-S3', nom: 'Stand Degrade trois', effectif: 1 },
];
const CRENEAUX: CreneauSeed[] = [
  { id: C1, date: '2026-08-12', debut: '10:00', fin: '12:00' },
  { id: C2, date: '2026-08-13', debut: '10:00', fin: '12:00' },
  { id: C3, date: '2026-08-14', debut: '10:00', fin: '12:00' },
  { id: C4, date: '2026-08-15', debut: '10:00', fin: '12:00' },
];

/** Twelve seats: 3 stands × 4 créneaux, each stand needing one animateur. */
const POSTES_ATTENDUS = 12;
const DUREE_SOLVE_SECONDES = 6;

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** Wipes the plan too, so the next solve is genuinely the first of the edition. */
async function reseed(): Promise<void> {
  await seedReferentielSolveur(admin, ANIMATEURS, STANDS, CRENEAUX);
  const parametres = await admin.put('/api/parametres-solveur', {
    data: { dureeResolutionSecondes: DUREE_SOLVE_SECONDES },
  });
  expect(parametres.ok(), await parametres.text()).toBe(true);
}

/** Seats of this spec's stands that the persisted plan currently holds. */
async function postesDuSpec(): Promise<
  { standId: string; creneauId: number; animateurId: string | null }[]
> {
  const planning = await planningPersiste(admin);
  return planning.postes
    .filter((poste) => poste.stand?.id.startsWith('SOLV-DEG-'))
    .map((poste) => ({
      standId: poste.stand?.id as string,
      creneauId: poste.creneau?.id as number,
      animateurId: poste.animateur?.id ?? null,
    }));
}

/**
 * Asks for far more animateurs than the edition has, so the next solve cannot
 * staff everything and is bound to score worse than the plan it replaces. The
 * business gesture behind it is ordinary — "I added places, then re-solved" —
 * which is exactly when an operator can lose a good plan without noticing.
 */
async function agrandirLeStand(standId: string, effectif: number): Promise<void> {
  const lecture = await admin.get('/api/stands');
  expect(lecture.ok(), await lecture.text()).toBe(true);
  const stands = (await lecture.json()) as {
    id: string;
    effectifMin: number;
    effectifMax: number;
  }[];
  const stand = stands.find((candidat) => candidat.id === standId);
  expect(stand, `stand ${standId} absent du référentiel`).toBeTruthy();

  const ecriture = await admin.put(`/api/stands/${standId}`, {
    data: { ...stand, effectifMin: effectif, effectifMax: effectif },
  });
  expect(ecriture.ok(), await ecriture.text()).toBe(true);
}

/** Runs a solve from the page itself, so the recap lands on screen as a user sees it. */
async function resoudreDepuisLaPage(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByRole('button', { name: 'Calculer le planning' }).click();
  // The server-side lock is the source of truth: see the job hold it, then
  // release it. Polling the result alone could pass before the solve ran.
  await expect
    .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 15_000 })
    .toBe(200);
  await expect
    .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 90_000 })
    .toBe(204);
}

test("le premier solve d'une édition n'a rien à comparer, le suivant nomme le plan remplacé", async () => {
  test.slow();
  await reseed();

  const premier = await lancerSolve(admin, DUREE_SOLVE_SECONDES);
  expect(premier.result?.diagnostic.hardScore).toBe(0);
  // Nothing was captured before it: there was no plan to overwrite.
  expect(premier.result?.previousPlan).toBeNull();

  // Issue #174: the first solve had nothing to start from, the second restarts
  // from the plan the first one saved — every seat carried over, none pinned.
  expect(premier.result?.reamorcage).toEqual({ mode: 'AUCUN', postes: 0, postesLiberes: 0 });

  const second = await lancerSolve(admin, DUREE_SOLVE_SECONDES);

  expect(second.result?.diagnostic.hardScore).toBe(0);
  // At least this spec's seats: the edition may still hold another spec's stand.
  expect(second.result?.reamorcage?.mode).toBe('PLAN_COURANT');
  expect(second.result?.reamorcage?.postes).toBeGreaterThanOrEqual(POSTES_ATTENDUS);
  expect(second.result?.reamorcage?.postesLiberes).toBe(0);
  expect(second.result?.previousPlan).not.toBeNull();
  // The snapshot named here is the one to restore to undo this solve.
  expect(second.result?.previousPlan?.snapshotId).toBeGreaterThan(0);
  expect(second.result?.previousPlan?.score).toBe(premier.result?.diagnostic.score);
});

test('une résolution qui améliore le plan ne crie pas au loup', async ({ browser }) => {
  test.slow();
  // Deliberately the other way round: start from an unstaffable problem, so
  // the first plan is bad, then give the edition back a problem it can solve.
  // The improvement is then certain — rather than resting on two identical
  // solves landing on the same score, which a slower machine could break, the
  // budget being spent in seconds and not in steps.
  await reseed();
  await agrandirLeStand('SOLV-DEG-S1', 9);
  const mauvais = await lancerSolve(admin, DUREE_SOLVE_SECONDES);
  expect(mauvais.result?.diagnostic.hardScore, 'nine seats for eight animateurs').toBeLessThan(0);

  await agrandirLeStand('SOLV-DEG-S1', 1);
  const page = await pageAdmin(browser, admin);
  await resoudreDepuisLaPage(page);

  // The comparison is drawn — and says nothing alarming. A false alarm here
  // would teach the user to ignore the flag on the day it matters.
  await expect(page.locator('#contenu')).toContainText('Avant :');
  await expect(page.getByText('Cette résolution a dégradé le plan enregistré.')).toBeHidden();
  await expect(page.getByRole('button', { name: "Revenir au plan d'avant" })).toBeHidden();
  await page.context().close();
});

test("une résolution qui dégrade le plan le dit, et le retour en arrière rétablit l'ancien", async ({
  browser,
}) => {
  test.slow();
  await reseed();
  const initial = await lancerSolve(admin, DUREE_SOLVE_SECONDES);
  expect(initial.result?.diagnostic.hardScore).toBe(0);
  const planInitial = await postesDuSpec();
  expect(planInitial).toHaveLength(POSTES_ATTENDUS);

  // Nine seats asked for on one stand, eight animateurs in the whole edition:
  // the next solve cannot staff everything, whatever it tries.
  await agrandirLeStand('SOLV-DEG-S1', 9);

  const page = await pageAdmin(browser, admin);
  await resoudreDepuisLaPage(page);

  // The screen says it, rather than leaving "0 hard" to speak for a run that
  // made things worse.
  await expect(page.getByText('Cette résolution a dégradé le plan enregistré.')).toBeVisible();
  await expect(page.locator('#contenu')).toContainText('Avant :');
  await expect(page.locator('#contenu')).toContainText(initial.result?.diagnostic.score as string);

  // And the way back is offered where the loss is learnt, not on another screen.
  await page.getByRole('button', { name: "Revenir au plan d'avant" }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Revenir' }).click();

  await expect(page.locator('#contenu')).toContainText('affectation(s) restaurée(s)');
  expect(await postesDuSpec()).toEqual(planInitial);
  // The comparison described a plan that is no longer the persisted one.
  await expect(page.getByRole('button', { name: "Revenir au plan d'avant" })).toBeHidden();

  await page.context().close();
});

/**
 * Issue #174, as a user sees it: the page says where the next calculation
 * starts from, « Recommencer de zéro » asks before throwing the saved plan
 * away, and the recap says which starting point the run actually used.
 */
test('« Calculer » repart du plan enregistré, « Recommencer de zéro » demande confirmation', async ({
  browser,
}) => {
  test.slow();
  await reseed();
  const page = await pageAdmin(browser, admin);
  try {
    await page.goto('/');
    await expect(page.locator('#contenu')).toContainText(
      'Aucun plan enregistré : le calcul part de zéro.',
    );
    await expect(page.getByRole('button', { name: 'Recommencer de zéro' })).toBeDisabled();

    await resoudreDepuisLaPage(page);
    await expect(page.locator('#contenu')).toContainText(
      'Point de départ : aucun, calcul de zéro.',
    );
    await expect(page.locator('#contenu')).toContainText(
      /repart du plan enregistré le .* \(\d+ affectations\)/,
    );
    await expect(page.getByRole('button', { name: 'Recommencer de zéro' })).toBeEnabled();

    await resoudreDepuisLaPage(page);
    await expect(page.locator('#contenu')).toContainText(
      /Point de départ : le plan enregistré, \d+ postes repris\./,
    );

    await page.getByRole('button', { name: 'Recommencer de zéro' }).click();
    const confirmation = page.getByRole('dialog');
    await expect(confirmation).toContainText('Recommencer de zéro ?');
    await confirmation.getByRole('button', { name: 'Recommencer de zéro' }).click();
    await expect
      .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 15_000 })
      .toBe(200);
    await expect
      .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 90_000 })
      .toBe(204);
    await expect(page.locator('#contenu')).toContainText(
      'Point de départ : aucun, calcul de zéro.',
    );
  } finally {
    await page.context().close();
  }
});
