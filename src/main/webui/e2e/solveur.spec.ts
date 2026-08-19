// Real short solves against the seeded referential, and the guarantees the
// application makes around them: every seat staffed, ad hoc constraints
// respected AND visible in the frontend, a locked animateur untouched by a
// re-solve, and an accepted échange surviving regeneration through its
// ANIMATEUR_CRENEAU pins — the core promise of issue #165.

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  AnimateurSeed,
  CreneauSeed,
  StandSeed,
  contexteAdmin,
  jetonDe,
  lancerSolve,
  occupantDe,
  pageAdmin,
  planningPersiste,
  postesDe,
  seedReferentielSolveur
} from './support';

// Problem shape, learnt the hard way:
// - one créneau per day — the hard `pauseMinimaleEntreVacations` forbids two
//   same-day vacations separated by less than the legal break;
// - a REAL search space (12 seats, 8 animateurs, 4 days) — on a toy 4-seat
//   problem the deterministic solve (fixed seed, Late-Acceptance settings
//   tuned for ~3500 postes) can cycle on a local optimum and never repair the
//   last hard violation, which says nothing about the application.
const C1 = 987201;
const C2 = 987202;
const C3 = 987203;
const C4 = 987204;

const ANIMATEURS: AnimateurSeed[] = [
  { id: 'SOLV-P', prenom: 'Paula', nom: 'Solve', dateNaissance: '1990-01-01' },
  { id: 'SOLV-Q', prenom: 'Quentin', nom: 'Solve', dateNaissance: '1991-02-02' },
  { id: 'SOLV-R', prenom: 'Rita', nom: 'Solve', dateNaissance: '1992-03-03' },
  { id: 'SOLV-T', prenom: 'Tom', nom: 'Solve', dateNaissance: '1993-04-04' },
  { id: 'SOLV-U', prenom: 'Uma', nom: 'Solve', dateNaissance: '1994-05-05' },
  { id: 'SOLV-V', prenom: 'Victor', nom: 'Solve', dateNaissance: '1995-06-06' },
  { id: 'SOLV-W', prenom: 'Wendy', nom: 'Solve', dateNaissance: '1996-07-07' },
  { id: 'SOLV-X', prenom: 'Xavier', nom: 'Solve', dateNaissance: '1997-08-08' }
];
const STANDS: StandSeed[] = [
  { id: 'SOLV-S1', nom: 'Stand Solve un', effectif: 1 },
  { id: 'SOLV-S2', nom: 'Stand Solve deux', effectif: 1 },
  { id: 'SOLV-S3', nom: 'Stand Solve trois', effectif: 1 }
];
const CRENEAUX: CreneauSeed[] = [
  { id: C1, date: '2026-07-12', debut: '10:00', fin: '12:00' },
  { id: C2, date: '2026-07-13', debut: '10:00', fin: '12:00' },
  { id: C3, date: '2026-07-14', debut: '10:00', fin: '12:00' },
  { id: C4, date: '2026-07-15', debut: '10:00', fin: '12:00' }
];

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** Fresh twelve-seat problem (3 stands × 4 créneaux on 4 days, 8 adults). */
async function reseed(): Promise<void> {
  await seedReferentielSolveur(admin, ANIMATEURS, STANDS, CRENEAUX);
}

test('un solve lancé depuis la page Solveur pourvoit tous les postes', async ({ browser }) => {
  test.slow();
  await reseed();
  // Short default duration, so the UI button triggers a quick run.
  const parametres = await admin.put('/api/parametres-solveur', {
    data: { dureeResolutionSecondes: 6 }
  });
  expect(parametres.ok()).toBe(true);

  const page = await pageAdmin(browser, admin);
  await page.goto('/');
  await page.getByRole('button', { name: 'Résoudre avec Timefold' }).click();
  // The server-side job lock is the source of truth: first see the job start
  // (otherwise an early poll could observe "no job yet" and pass before the
  // solve even ran), then see it end.
  await expect
    .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 15_000 })
    .toBe(200);
  await expect
    .poll(async () => (await page.request.get('/api/jobs/active')).status(), { timeout: 90_000 })
    .toBe(204);

  const planning = await planningPersiste(admin);
  const postesSolv = planning.postes.filter((poste) => poste.stand?.id.startsWith('SOLV-'));
  expect(postesSolv).toHaveLength(12);
  expect(postesSolv.every((poste) => poste.animateur !== null)).toBe(true);

  // And the result is visible in the frontend right away.
  await page.goto('/hours');
  await expect(page.locator('#contenu')).toContainText('Solve');
  await page.context().close();
});

test('les contraintes ad hoc sont respectées par le solve et visibles dans le frontend', async ({ browser }) => {
  test.slow();
  await reseed();

  // Three constraints, one of each type, created through the same API the
  // frontend uses.
  const creations = [
    {
      id: 'SOLV-ADHOC-INDISPO',
      type: 'INDISPONIBILITE_FORCEE',
      animateursConcernes: [{ id: 'SOLV-P' }],
      creneau: { id: C1 },
      stand: null,
      raison: 'E2E : Paula indisponible le matin'
    },
    {
      id: 'SOLV-ADHOC-FORCEE',
      type: 'AFFECTATION_FORCEE',
      animateursConcernes: [{ id: 'SOLV-Q' }],
      creneau: { id: C2 },
      stand: { id: 'SOLV-S1' },
      raison: 'E2E : Quentin imposé sur Stand Solve un'
    },
    {
      id: 'SOLV-ADHOC-INCOMPAT',
      type: 'INCOMPATIBILITE',
      animateursConcernes: [{ id: 'SOLV-R' }, { id: 'SOLV-T' }],
      creneau: null,
      stand: null,
      raison: 'E2E : Rita et Tom incompatibles'
    }
  ];
  for (const contrainte of creations) {
    const reponse = await admin.post('/api/contraintes-ad-hoc', { data: contrainte });
    expect(reponse.ok(), await reponse.text()).toBe(true);
  }

  // Visible on the admin screen before any solve.
  const page = await pageAdmin(browser, admin);
  await page.goto('/ad-hoc-constraints');
  await expect(page.locator('#contenu')).toContainText('Paula indisponible le matin');
  await expect(page.locator('#contenu')).toContainText('Quentin imposé sur Stand Solve un');
  await expect(page.locator('#contenu')).toContainText('Rita et Tom incompatibles');

  const job = await lancerSolve(admin, 6);
  expect(job.result?.hardScore, 'the constrained problem must stay feasible').toBe(0);
  expect(job.result?.postesNonPourvus).toBe(0);

  const planning = await planningPersiste(admin);
  // INDISPONIBILITE_FORCEE : Paula never works créneau C1.
  expect(postesDe(planning, 'SOLV-P').some((poste) => poste.endsWith(`@${C1}`))).toBe(false);
  // AFFECTATION_FORCEE : Quentin holds exactly the imposed seat.
  expect(occupantDe(planning, 'SOLV-S1', C2)).toBe('SOLV-Q');
  // INCOMPATIBILITE : Rita and Tom never share a créneau.
  for (const creneau of [C1, C2, C3, C4]) {
    const surCreneau = planning.postes
      .filter((poste) => poste.creneau?.id === creneau && poste.animateur)
      .map((poste) => poste.animateur?.id);
    expect(surCreneau.includes('SOLV-R') && surCreneau.includes('SOLV-T'),
      `Rita et Tom tous deux sur le créneau ${creneau}`).toBe(false);
  }

  // The forced seat reads back in the frontend: Quentin's timeline shows it.
  await page.goto('/timeline');
  const champ = page.getByRole('combobox', { name: 'Animateur' });
  await champ.click();
  await champ.fill('Quentin');
  await page.getByRole('option', { name: /Quentin Solve/ }).click();
  await expect(page.locator('#contenu')).toContainText('Stand Solve un');
  await page.context().close();
});

test('un animateur verrouillé garde exactement son planning après re-résolution', async () => {
  test.slow();
  // Continue from the previous state: solve once, freeze Paula, solve again.
  const avant = await lancerSolve(admin, 6);
  expect(avant.result?.hardScore).toBe(0);
  const planAvant = postesDe(await planningPersiste(admin), 'SOLV-P');

  const verrou = await admin.post('/api/verrouillages', {
    data: { type: 'ANIMATEUR', animateurId: 'SOLV-P', raison: 'E2E : Paula validée' }
  });
  expect(verrou.ok(), await verrou.text()).toBe(true);
  const verrouId = ((await verrou.json()) as { id: string }).id;

  const apres = await lancerSolve(admin, 6);
  expect(apres.result?.hardScore).toBe(0);
  expect(postesDe(await planningPersiste(admin), 'SOLV-P')).toEqual(planAvant);

  await admin.delete(`/api/verrouillages/${verrouId}`);
});

test("un échange accepté survit à la régénération du planning", async ({ browser }) => {
  test.slow();
  // Clean problem: no ad hoc constraint, no lock, fresh solve.
  await reseed();
  const initial = await lancerSolve(admin, 6);
  expect(initial.result?.hardScore).toBe(0);

  // Whoever holds the two seats of créneau C1 swaps stands, via the real flow:
  // demande from the occupant's espace, acceptation by the admin.
  const planning = await planningPersiste(admin);
  const surS1 = occupantDe(planning, 'SOLV-S1', C1) as string;
  const surS2 = occupantDe(planning, 'SOLV-S2', C1) as string;
  expect(surS1).toBeTruthy();
  expect(surS2).toBeTruthy();

  const jeton = await jetonDe(admin, surS1);
  const soumission = await admin.post(`/api/espace-animateur/${jeton}/demandes`, {
    data: [{ creneauId: C1, standId: 'SOLV-S1', cibleId: surS2, motif: 'E2E régénération' }]
  });
  expect(soumission.ok(), await soumission.text()).toBe(true);
  const demandeId = ((await soumission.json()) as { id: string }[])[0].id;
  const acceptation = await admin.post(`/api/echanges/${demandeId}/acceptation`, { data: {} });
  expect(acceptation.ok(), await acceptation.text()).toBe(true);

  // Applied as simulated…
  const applique = await planningPersiste(admin);
  expect(occupantDe(applique, 'SOLV-S1', C1)).toBe(surS2);
  expect(occupantDe(applique, 'SOLV-S2', C1)).toBe(surS1);

  // …and pinned: a full re-solve does not undo it.
  const regeneration = await lancerSolve(admin, 6);
  expect(regeneration.result?.hardScore).toBe(0);
  const regenere = await planningPersiste(admin);
  expect(occupantDe(regenere, 'SOLV-S1', C1)).toBe(surS2);
  expect(occupantDe(regenere, 'SOLV-S2', C1)).toBe(surS1);

  // The two ANIMATEUR_CRENEAU pins are visible on the verrouillages screen.
  const page = await pageAdmin(browser, admin);
  await page.goto('/verrouillages');
  await expect(page.getByText('Animateur sur un créneau').first()).toBeVisible();
  await expect(page.locator('#contenu')).toContainText(`Échange validé (demande ${demandeId})`);
  await page.context().close();
});
