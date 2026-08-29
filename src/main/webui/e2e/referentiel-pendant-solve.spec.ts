// Writing the referential while a solve is in flight (issue #328).
//
// A solve builds its problem from the referential at its start and re-upserts
// that referential when it lands, so a delete or an edit made meanwhile is
// silently undone minutes later — an animateur coming back from the dead, a
// renamed stand reverting to its old name. The backend refuses those writes
// with a 409 while it holds the solver FOR THE CURRENT EDITION.
//
// What this suite checks, and could not be checked below the browser:
//   1. the screen prevents the geste — the buttons are actually disabled;
//   2. the refusal is real server-side, not only a disabled button;
//   3. the 409 says something an operator can act on, all the way to the
//      snack bar — the frontend's `toError` reads `body.message` and nothing
//      else, so a conflict without one reaches the user as the useless
//      "Échec de la requête (code 409)";
//   4. everything works again once the solver is free.
//
// The edition scoping of the guard (a solve on edition A must not freeze data
// entry on B) is covered by ReferenceDataServiceSuppressionTest, which can
// build a second edition far more cheaply than the browser can.

import { APIRequestContext, Page, expect, test } from '@playwright/test';
import {
  AnimateurSeed,
  CreneauSeed,
  StandSeed,
  contexteAdmin,
  pageAdmin,
  seedReferentielSolveur
} from './support';

const C1 = 987301;
const C2 = 987302;

const ANIMATEURS: AnimateurSeed[] = [
  { id: 'SOLV-G1', prenom: 'Gaby', nom: 'Garde', dateNaissance: '1990-01-01' },
  { id: 'SOLV-G2', prenom: 'Hugo', nom: 'Garde', dateNaissance: '1991-02-02' },
  { id: 'SOLV-G3', prenom: 'Inès', nom: 'Garde', dateNaissance: '1992-03-03' },
  { id: 'SOLV-G4', prenom: 'Jules', nom: 'Garde', dateNaissance: '1993-04-04' }
];
const STANDS: StandSeed[] = [
  { id: 'SOLV-GS1', nom: 'Stand garde un', effectif: 1 },
  { id: 'SOLV-GS2', nom: 'Stand garde deux', effectif: 1 }
];
const CRENEAUX: CreneauSeed[] = [
  { id: C1, date: '2026-07-20', debut: '10:00', fin: '12:00' },
  { id: C2, date: '2026-07-21', debut: '10:00', fin: '12:00' }
];

/** Long enough that the assertions run while it is still solving. */
const BUDGET_SECONDES = 40;

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
});

test.afterAll(async () => {
  await admin.dispose();
});

/** Starts a solve and returns its id WITHOUT waiting for it — the point is to write during it. */
async function demarrerSolve(): Promise<string> {
  const lancement = await admin.post(`/api/solve/async/reference-data?seconds=${BUDGET_SECONDES}`);
  expect(lancement.status(), await lancement.text()).toBe(202);
  const { id } = (await lancement.json()) as { id: string };
  await expect
    .poll(async () => (await admin.get('/api/jobs/active')).status(), {
      message: 'the solve should be holding the solver'
    })
    .toBe(200);
  return id;
}

/** Stops the run and waits for the solver to be free again, whatever the outcome. */
async function libererLeSolveur(jobId: string | null): Promise<void> {
  if (jobId) {
    await admin.post(`/api/jobs/${jobId}/cancel`).catch(() => undefined);
  }
  await expect
    .poll(async () => (await admin.get('/api/jobs/active')).status(), {
      message: 'the solver should end up free',
      timeout: 90_000
    })
    .toBe(204);
}

/**
 * Makes the page believe the solver is idle, the way a tab opened before the
 * solve started does until its stream and its poll catch up. The client-side
 * lock then steps aside and the request actually reaches the server — which is
 * the only way to see what the server's refusal looks like to a user.
 */
async function tabPerimee(page: Page): Promise<void> {
  await page.route('**/api/jobs/stream*', (route) => route.abort());
  await page.route('**/api/jobs/active*', (route) =>
    route.fulfill({ status: 204, body: '', headers: { 'content-type': 'application/json' } })
  );
}

test.describe('écriture du référentiel pendant une résolution', () => {
  test.beforeEach(async () => {
    await seedReferentielSolveur(admin, ANIMATEURS, STANDS, CRENEAUX);
    await libererLeSolveur(null);
  });

  test("l'écran Stands se verrouille pendant la résolution, et le serveur refuse aussi", async ({
    browser
  }) => {
    test.slow();
    let jobId: string | null = null;
    const page = await pageAdmin(browser, admin);
    try {
      await page.goto('/stands');
      await page.getByLabel('Filtrer').fill('SOLV-GS1');
      const ligne = page.getByRole('row', { name: /SOLV-GS1/ });
      await expect(ligne).toBeVisible();
      // Editable before anything runs — otherwise the assertion below proves nothing.
      await expect(ligne.getByRole('button', { name: 'Modifier' })).toBeEnabled();

      jobId = await demarrerSolve();

      // 1. The screen prevents the geste, without the user having to try it.
      await expect(ligne.getByRole('button', { name: 'Modifier' })).toBeDisabled();
      await expect(ligne.getByRole('button', { name: 'Supprimer' })).toBeDisabled();
      await expect(page.getByRole('button', { name: 'Ajouter' })).toBeDisabled();
      await expect(page.getByRole('button', { name: 'Compacter les horaires' })).toBeDisabled();

      // 2. And the server refuses on its own — the button is not the guard.
      //    Same session, same edition: what a stale tab or a script would get.
      const refus = await page.request.put('/api/stands/SOLV-GS1', {
        data: { id: 'SOLV-GS1', nom: 'Renommé pendant le solve', effectifMin: 1, effectifMax: 1 }
      });
      expect(refus.status()).toBe(409);

      // 3. …with a message an operator can act on, which is what reaches the
      //    snack bar: the frontend reads body.message and nothing else.
      const corps = (await refus.json()) as { message?: string; id?: string; status?: string };
      expect(corps.message ?? '').toMatch(/résolution est en cours/i);
      expect(corps.message ?? '').toMatch(/écran Solveur/i);
      // The blocking run is still named, for whoever wants to act on it.
      expect(corps.id).toBe(jobId);
      expect(corps.status).toMatch(/PENDING|RUNNING/);

      // The stand kept its name: nothing was half-written before the refusal.
      const relu = await admin.get('/api/stands');
      const stands = (await relu.json()) as { id: string; nom: string }[];
      expect(stands.find((s) => s.id === 'SOLV-GS1')?.nom).toBe('Stand garde un');
    } finally {
      await libererLeSolveur(jobId);
      await page.close();
    }
  });

  test('le refus arrive lisiblement jusque dans le bandeau de notification', async ({ browser }) => {
    test.slow();
    let jobId: string | null = null;
    const page = await pageAdmin(browser, admin);
    try {
      // A tab that has not learnt about the solve: its buttons stay live, so
      // the save really goes out and the server's answer really gets rendered.
      await tabPerimee(page);
      await page.goto('/stands');
      await page.getByLabel('Filtrer').fill('SOLV-GS1');
      const ligne = page.getByRole('row', { name: /SOLV-GS1/ });
      await expect(ligne).toBeVisible();

      jobId = await demarrerSolve();

      await ligne.getByRole('button', { name: 'Modifier' }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();
      await dialog.getByLabel('Nom', { exact: true }).fill('Nom qui ne doit pas tenir');
      await dialog.getByRole('button', { name: 'Modifier le stand' }).click();

      // The user is told what happened, in words that name the cause and the
      // way out — not "Échec de la requête (code 409)".
      const bandeau = page.locator('mat-snack-bar-container');
      await expect(bandeau).toBeVisible();
      await expect(bandeau).toContainText('résolution est en cours');
      await expect(bandeau).toContainText('écran Solveur');

      // And nothing was saved.
      const relu = await admin.get('/api/stands');
      const stands = (await relu.json()) as { id: string; nom: string }[];
      expect(stands.find((s) => s.id === 'SOLV-GS1')?.nom).toBe('Stand garde un');
    } finally {
      await libererLeSolveur(jobId);
      await page.close();
    }
  });

  test('une résolution terminée rend la main : le renommage passe par l’écran', async ({
    browser
  }) => {
    test.slow();
    let jobId: string | null = null;
    const page = await pageAdmin(browser, admin);
    try {
      jobId = await demarrerSolve();
      await libererLeSolveur(jobId);
      jobId = null;

      await page.goto('/stands');
      await page.getByLabel('Filtrer').fill('SOLV-GS1');
      const ligne = page.getByRole('row', { name: /SOLV-GS1/ });
      await expect(ligne).toBeVisible();
      await expect(ligne.getByRole('button', { name: 'Modifier' })).toBeEnabled();

      await ligne.getByRole('button', { name: 'Modifier' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Nom', { exact: true }).fill('Stand garde renommé');
      await dialog.getByRole('button', { name: 'Modifier le stand' }).click();
      await expect(dialog).toBeHidden();

      await expect(page.getByRole('row', { name: /Stand garde renommé/ })).toBeVisible();
    } finally {
      await libererLeSolveur(jobId);
      await page.close();
    }
  });
});
