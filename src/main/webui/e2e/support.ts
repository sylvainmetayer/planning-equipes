// Shared plumbing of the perimeter suite: an authenticated admin API context,
// and the seeding of a tiny two-seat planning through the admin API only —
// exactly what a browser could do, no backdoor into the database.

import { APIRequestContext, Browser, Page, Playwright, expect } from '@playwright/test';

export const MOT_DE_PASSE_ADMIN = process.env['E2E_ADMIN_PASSWORD'] ?? 'admin';

/** Ids of everything the suite seeds, so reseeding stays idempotent. */
export const SEED = {
  demandeur: 'E2E-A',
  cible: 'E2E-B',
  /** Seatless colleague, unavailable on the seeded day (edge-case suite). */
  collegueIndisponible: 'E2E-C',
  standDemandeur: 'E2E-S1',
  standCible: 'E2E-S2',
  creneauId: 987001,
  jour: '2026-07-10'
} as const;

/**
 * Opens an API context holding a fresh admin session cookie (form login).
 * Callers must `dispose()` it.
 */
export async function contexteAdmin(
  playwright: Playwright,
  baseURL: string
): Promise<APIRequestContext> {
  const request = await playwright.request.newContext({ baseURL });
  const connexion = await request.post('/j_security_check', {
    form: { j_username: 'admin', j_password: MOT_DE_PASSE_ADMIN },
    maxRedirects: 0
  });
  expect(connexion.status(), 'form login should answer the landing redirect').toBe(302);
  return request;
}

/**
 * Seeds (idempotently) a two-seat persisted planning on the same créneau:
 * Alice on stand E2E-S1, Bruno on stand E2E-S2 — the smallest dataset on
 * which an échange croisé exists. Replayed through `/api/database/import`,
 * whose statements are restricted to the business tables server-side.
 *
 * With `avecCollegueIndisponible`, a third, seatless colleague (Chloé) who
 * declared the seeded day off joins the referential — the fixture of the
 * prevalidation edge cases.
 */
export async function seedPlanning(
  admin: APIRequestContext,
  options: { avecCollegueIndisponible?: boolean } = {}
): Promise<void> {
  const script = [
    // Clean previous runs, children first. Postes of the other test prefixes
    // are wiped too: a solver spec may have persisted a whole planning over
    // its SOLV-/FUZZ- referential, and the sends ("Envoyer à tous") must see
    // exactly the two-seat planning seeded here.
    `delete from demande_echange where demandeur_id like 'E2E-%' or cible_id like 'E2E-%';`,
    `delete from verrouillage_planning where animateur_id like 'E2E-%';`,
    `delete from poste_affectation where id like 'E2E-%';`,
    `delete from poste_affectation where stand_id like 'SOLV-%' or animateur_id like 'SOLV-%';`,
    `delete from poste_affectation where stand_id like 'FUZZ-%' or animateur_id like 'FUZZ-%';`,
    `delete from creneau where id = ${SEED.creneauId};`,
    `delete from animateur where id like 'E2E-%';`,
    `delete from stand where id like 'E2E-%';`,
    // The dataset itself. jeton_acces is deliberately omitted: the database
    // generates it, and the suite reads it back through the admin API.
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${SEED.standDemandeur}', 'Stand E2E un', 1, 1, false);`,
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${SEED.standCible}', 'Stand E2E deux', 1, 1, false);`,
    // Alice carries an email (mail-sending tests); Bruno deliberately none.
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager, email) values ('DEFAUT', '${SEED.demandeur}', 'Alice', 'E2E', '1990-01-01', false, '${SEED.demandeur}@example.org');`,
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('DEFAUT', '${SEED.cible}', 'Bruno', 'E2E', '1992-02-02', false);`,
    `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('DEFAUT', ${SEED.creneauId}, '2026-07-10', '10:00', '12:00');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', 'E2E-P1', '${SEED.standDemandeur}', ${SEED.creneauId}, '${SEED.demandeur}');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', 'E2E-P2', '${SEED.standCible}', ${SEED.creneauId}, '${SEED.cible}');`,
    ...(options.avecCollegueIndisponible
      ? [
          `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('DEFAUT', '${SEED.collegueIndisponible}', 'Chloé', 'E2E', '1995-03-03', false);`,
          `insert into animateur_jour_indispo (edition_id, animateur_id, jour) values ('DEFAUT', '${SEED.collegueIndisponible}', '${SEED.jour}');`
        ]
      : [])
  ].join('\n');

  const importReponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: script
  });
  expect(importReponse.ok(), await importReponse.text()).toBe(true);
}

/* ------------------------- Solver-backed seeding ------------------------- */

export interface AnimateurSeed {
  id: string;
  prenom: string;
  nom: string;
  /** ISO date; drives the adult/minor rules. */
  dateNaissance: string;
  joursIndisponibles?: string[];
}

export interface StandSeed {
  id: string;
  nom: string;
  /** Both effectif_min and effectif_max: seats the solver must fill. */
  effectif: number;
  reserveMajeurs?: boolean;
}

export interface CreneauSeed {
  /** Keep every test créneau in the reserved 987000–987999 range. */
  id: number;
  date: string;
  debut: string;
  fin: string;
}

/**
 * Replaces the whole test referential (every `E2E-`/`SOLV-`/`FUZZ-` row and
 * every créneau of the reserved 987xxx range) with the given dataset, without
 * seeding any assignment: the solver builds the postes itself. Ad hoc
 * constraints, locks and demandes attached to test rows are wiped too, so
 * each solver test starts from a clean, known problem.
 */
export async function seedReferentielSolveur(
  admin: APIRequestContext,
  animateurs: AnimateurSeed[],
  stands: StandSeed[],
  creneaux: CreneauSeed[]
): Promise<void> {
  const prefixes = ['E2E-', 'SOLV-', 'FUZZ-'];
  const statements: string[] = [];
  for (const prefixe of prefixes) {
    statements.push(
      `delete from demande_echange where demandeur_id like '${prefixe}%' or cible_id like '${prefixe}%';`,
      `delete from verrouillage_planning where animateur_id like '${prefixe}%' or stand_id like '${prefixe}%';`,
      `delete from contrainte_ad_hoc where id in (select contrainte_id from contrainte_animateur where animateur_id like '${prefixe}%');`,
      `delete from contrainte_ad_hoc where stand_id like '${prefixe}%' or id like '${prefixe}%';`,
      `delete from poste_affectation where id like '${prefixe}%' or stand_id like '${prefixe}%' or animateur_id like '${prefixe}%';`
    );
  }
  statements.push(
    `delete from verrouillage_planning where creneau_id >= 987000 and creneau_id < 988000;`,
    `delete from contrainte_ad_hoc where creneau_id >= 987000 and creneau_id < 988000;`,
    `delete from poste_affectation where creneau_id >= 987000 and creneau_id < 988000;`,
    `delete from creneau where id >= 987000 and id < 988000;`
  );
  for (const prefixe of prefixes) {
    statements.push(
      `delete from animateur where id like '${prefixe}%';`,
      `delete from stand where id like '${prefixe}%';`
    );
  }
  for (const stand of stands) {
    statements.push(
      `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${stand.id}', '${stand.nom}', ${stand.effectif}, ${stand.effectif}, ${stand.reserveMajeurs ?? false});`
    );
  }
  for (const animateur of animateurs) {
    statements.push(
      `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager, email) values ('DEFAUT', '${animateur.id}', '${animateur.prenom}', '${animateur.nom}', '${animateur.dateNaissance}', false, '${animateur.id}@example.org');`
    );
    for (const jour of animateur.joursIndisponibles ?? []) {
      statements.push(
        `insert into animateur_jour_indispo (edition_id, animateur_id, jour) values ('DEFAUT', '${animateur.id}', '${jour}');`
      );
    }
  }
  for (const creneau of creneaux) {
    statements.push(
      `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('DEFAUT', ${creneau.id}, '${creneau.date}', '${creneau.debut}', '${creneau.fin}');`
    );
  }
  const importReponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: statements.join('\n')
  });
  expect(importReponse.ok(), await importReponse.text()).toBe(true);
}

/** Terminal view of a solver job, as `/api/jobs/{id}` answers it. */
export interface JobTermine {
  id: string;
  status: string;
  error: string | null;
  result: { hardScore: number; postesNonPourvus: number; score: string } | null;
}

/**
 * Launches a short solve on the current reference data and waits for the job
 * to end. Fails loudly on a FAILED job; the caller asserts on the result.
 */
export async function lancerSolve(admin: APIRequestContext, seconds: number): Promise<JobTermine> {
  const lancement = await admin.post(`/api/solve/async/reference-data?seconds=${seconds}`);
  expect(lancement.status(), await lancement.text()).toBe(202);
  const { id } = (await lancement.json()) as { id: string };

  const debut = Date.now();
  for (;;) {
    const reponse = await admin.get(`/api/jobs/${id}`);
    expect(reponse.ok()).toBe(true);
    const job = (await reponse.json()) as JobTermine;
    if (job.status === 'COMPLETED') {
      return job;
    }
    expect(job.status, job.error ?? 'job in a terminal non-completed state').not.toMatch(/FAILED|CANCELLED/);
    expect(Date.now() - debut, 'solve should finish well within its budget').toBeLessThan((seconds + 60) * 1000);
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}

/** Minimal shape of `/api/planning/persisted` the assertions need. */
export interface PlanningPersiste {
  postes: {
    id: string;
    stand: { id: string } | null;
    creneau: { id: number; date: string | null } | null;
    animateur: { id: string } | null;
  }[];
}

export async function planningPersiste(admin: APIRequestContext): Promise<PlanningPersiste> {
  const reponse = await admin.get('/api/planning/persisted');
  expect(reponse.ok()).toBe(true);
  return (await reponse.json()) as PlanningPersiste;
}

/** Occupant of the single seat of (stand, créneau), `null` when empty/absent. */
export function occupantDe(planning: PlanningPersiste, standId: string, creneauId: number): string | null {
  return (
    planning.postes.find(
      (poste) => poste.stand?.id === standId && poste.creneau?.id === creneauId
    )?.animateur?.id ?? null
  );
}

/** The (standId, creneauId) pairs an animateur holds, sorted for comparison. */
export function postesDe(planning: PlanningPersiste, animateurId: string): string[] {
  return planning.postes
    .filter((poste) => poste.animateur?.id === animateurId)
    .map((poste) => `${poste.stand?.id}@${poste.creneau?.id}`)
    .sort();
}

/**
 * A browser page carrying the admin session of {@code admin} (its cookies are
 * copied into a fresh context). The caller closes the page's context.
 */
export async function pageAdmin(browser: Browser, admin: APIRequestContext): Promise<Page> {
  const contexte = await browser.newContext({ storageState: await admin.storageState() });
  return contexte.newPage();
}

/** The database-generated espace token of one seeded animateur, via the admin API. */
export async function jetonDe(admin: APIRequestContext, animateurId: string): Promise<string> {
  const reponse = await admin.get('/api/animateurs');
  expect(reponse.ok()).toBe(true);
  const animateurs = (await reponse.json()) as { id: string; jetonAcces?: string }[];
  const jeton = animateurs.find((animateur) => animateur.id === animateurId)?.jetonAcces;
  expect(jeton, `animateur ${animateurId} must exist with a token`).toBeTruthy();
  return jeton as string;
}
