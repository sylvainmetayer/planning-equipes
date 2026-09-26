// A solver job is bound to the edition it was submitted from, and the UI only
// locks data entry THERE: after switching editions during a long solve, the
// other editions stay fully editable and scenario imports stay open, while the
// toolbar monitor names the edition the job is working on. Complements the
// unit tests of `SolverJobService.editingLocked`: this exercises the whole
// loop (job submit → /api/jobs/active carrying editionId/editionNom → per-page
// locks) against the real stack.

import { APIRequestContext, expect, test } from '@playwright/test';
import {
  AnimateurSeed,
  CreneauSeed,
  StandSeed,
  contexteAdmin,
  idCree,
  shiftDate,
  pageAdmin,
  seedReferentielSolveur,
} from './support';
import { repartirDeLaReference } from './reference';

/** Created by its name; `id` is the one the application draws, read back at creation. */
const EDITION_B = { id: '', nom: 'Édition e2e verrou' };

// Same twelve-seat shape as solveur.spec.ts: the solve only needs to RUN for a
// while, and a real search space keeps it busy past the assertions below (the
// server-side plateau bailout ends a trivially-converged job after ~30s).
const ANIMATEURS: AnimateurSeed[] = [
  { id: 'SOLV-P', prenom: 'Paula', nom: 'Solve', dateNaissance: '1990-01-01' },
  { id: 'SOLV-Q', prenom: 'Quentin', nom: 'Solve', dateNaissance: '1991-02-02' },
  { id: 'SOLV-R', prenom: 'Rita', nom: 'Solve', dateNaissance: '1992-03-03' },
  { id: 'SOLV-T', prenom: 'Tom', nom: 'Solve', dateNaissance: '1993-04-04' },
  { id: 'SOLV-U', prenom: 'Uma', nom: 'Solve', dateNaissance: '1994-05-05' },
  { id: 'SOLV-V', prenom: 'Victor', nom: 'Solve', dateNaissance: '1995-06-06' },
  { id: 'SOLV-W', prenom: 'Wendy', nom: 'Solve', dateNaissance: '1996-07-07' },
  { id: 'SOLV-X', prenom: 'Xavier', nom: 'Solve', dateNaissance: '1997-08-08' },
];
const STANDS: StandSeed[] = [
  { id: 'SOLV-S1', nom: 'Stand Solve un', effectif: 1 },
  { id: 'SOLV-S2', nom: 'Stand Solve deux', effectif: 1 },
  { id: 'SOLV-S3', nom: 'Stand Solve trois', effectif: 1 },
];
const CRENEAUX: CreneauSeed[] = [
  { id: 987201, date: shiftDate('2026-07-12'), debut: '10:00', fin: '12:00' },
  { id: 987202, date: shiftDate('2026-07-13'), debut: '10:00', fin: '12:00' },
  { id: 987203, date: shiftDate('2026-07-14'), debut: '10:00', fin: '12:00' },
  { id: 987204, date: shiftDate('2026-07-15'), debut: '10:00', fin: '12:00' },
];

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
  // Leftover from a crashed previous run, found by its name.
  const editions = (await (await admin.get('/api/editions')).json()) as {
    id: string;
    nom: string;
  }[];
  for (const reste of editions.filter((edition) => edition.nom === EDITION_B.nom)) {
    await admin.delete(`/api/editions/${reste.id}`);
  }
  EDITION_B.id = await idCree(await admin.post('/api/editions', { data: { nom: EDITION_B.nom } }));
});

test.afterAll(async () => {
  const suppression = await admin.delete(`/api/editions/${EDITION_B.id}`);
  expect(suppression.ok(), await suppression.text()).toBe(true);
  await admin.dispose();
});

test("pendant un solve, la saisie n'est verrouillée que sur l'édition du job", async ({
  browser,
}) => {
  test.slow();
  await seedReferentielSolveur(admin, ANIMATEURS, STANDS, CRENEAUX);
  // The admin context sends no X-Edition-Id header, so the job below is
  // submitted for the DEFAULT edition — the page starts there too (fresh
  // localStorage), then switches to EDITION_B mid-solve.
  const editionA = (await (await admin.get('/api/editions/courant')).json()) as {
    id: string;
    nom: string;
  };
  expect(editionA.id).not.toBe(EDITION_B.id);

  const page = await pageAdmin(browser, admin);
  // Warm the SPA (cold lazy-chunk loads are slow) BEFORE starting the solve:
  // every assertion below must land while the job is still running.
  await page.goto('/typologies');
  await expect(page.getByRole('button', { name: 'Ajouter' })).toBeEnabled();

  const lancement = await admin.post('/api/solve/async/reference-data?seconds=120');
  expect(lancement.status(), await lancement.text()).toBe(202);
  const { id: jobId } = (await lancement.json()) as { id: string };
  try {
    await expect
      .poll(async () => (await admin.get('/api/jobs/active')).status(), { timeout: 15_000 })
      .toBe(200);

    // On the job's own edition: entry locked, and the toolbar monitor says
    // which edition the solver is working on.
    await page.reload();
    await expect(page.getByRole('button', { name: 'Ajouter' })).toBeDisabled();
    await expect(page.locator('.solver-running-indicator')).toHaveAttribute(
      'aria-label',
      new RegExp(`sur l'édition « ${editionA.nom} »`),
    );

    // Switched to the other edition: its referential stays editable…
    await page.evaluate(
      (id) => localStorage.setItem('planning-equipes.editionId', id),
      EDITION_B.id,
    );
    await page.goto('/typologies');
    await expect(page.getByRole('button', { name: 'Ajouter' })).toBeEnabled();

    // …and scenario imports stay open there too.
    await page.goto('/fichiers?cible=scenario');
    await expect(page.getByRole('button', { name: 'Importer un fichier' })).toBeEnabled();

    // Only meaningful if the job was still running while we looked.
    expect((await admin.get('/api/jobs/active')).status()).toBe(200);
  } finally {
    await admin.post(`/api/jobs/${jobId}/cancel`);
    await expect
      .poll(async () => (await admin.get('/api/jobs/active')).status(), { timeout: 90_000 })
      .toBe(204);
    await page.context().close();
  }
});
