// Shared plumbing of the perimeter suite: an authenticated admin API context,
// and the seeding of a tiny two-seat planning through the admin API only —
// exactly what a browser could do, no backdoor into the database.

import {
  APIRequestContext,
  APIResponse,
  Browser,
  Locator,
  Page,
  Playwright,
  expect,
} from '@playwright/test';

export const MOT_DE_PASSE_ADMIN = process.env['E2E_ADMIN_PASSWORD'] ?? 'admin';

/* ------------------------- Dates of the seeded event ------------------------- */

/**
 * The Monday the fixtures are written against: every date a spec seeds is
 * spelt as if the event ran in the summer of 2026, from that week on, and is
 * moved to the future at run time by {@link shiftDate}.
 */
const FIXTURE_MONDAY = Date.UTC(2026, 6, 6);

/** How far ahead of the real clock that Monday lands, at the least. */
const MIN_LEAD_DAYS = 56;

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The days the fixtures cover, as written: the summer of 2026 most specs
 * seed, and the heatwave fortnight of `festival-hivernal.yaml`. Whatever the
 * shift, none of them may land on a public holiday — see {@link SHIFT_DAYS}.
 */
const FIXTURE_WINDOWS: readonly [number, number][] = [
  [Date.UTC(2026, 6, 6), Date.UTC(2026, 7, 16)],
  [Date.UTC(2027, 1, 1), Date.UTC(2027, 1, 16)],
];

/** Anonymous Gregorian algorithm, as `JoursFeries.paques` computes it. */
function easterSunday(year: number): number {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return Date.UTC(year, month - 1, day);
}

const HOLIDAYS_BY_YEAR = new Map<number, Set<number>>();

/** The French public holidays of `year`: the eleven `JoursFeries` knows. */
function publicHolidays(year: number): Set<number> {
  let holidays = HOLIDAYS_BY_YEAR.get(year);
  if (!holidays) {
    const fixed = [
      [1, 1],
      [5, 1],
      [5, 8],
      [7, 14],
      [8, 15],
      [11, 1],
      [11, 11],
      [12, 25],
    ].map(([month, day]) => Date.UTC(year, month - 1, day));
    const easter = easterSunday(year);
    // Easter Monday, Ascension Thursday, Whit Monday.
    const movable = [1, 39, 50].map((offset) => easter + offset * DAY_MS);
    holidays = new Set([...fixed, ...movable]);
    HOLIDAYS_BY_YEAR.set(year, holidays);
  }
  return holidays;
}

function hitsAHoliday(shift: number): boolean {
  return FIXTURE_WINDOWS.some(([first, last]) => {
    for (let day = first; day <= last; day += DAY_MS) {
      const shifted = day + shift * DAY_MS;
      if (publicHolidays(new Date(shifted).getUTCFullYear()).has(shifted)) {
        return true;
      }
    }
    return false;
  });
}

/**
 * Days every seeded date moves by: the smallest whole number of weeks that
 * puts {@link FIXTURE_MONDAY} at least {@link MIN_LEAD_DAYS} ahead of today,
 * and lands no day of {@link FIXTURE_WINDOWS} on a public holiday.
 *
 * « Le passé est figé » (ADR 0044) is on in the e2e stack, as in production,
 * and the packaged application freezes no clock: a timeslot behind the real
 * date is taken from the saved plan and pinned by every solve, and a gesture
 * on its seat is refused. A fixture dated once and for all would slide into
 * the past and stop testing anything; this one is dated from today, whatever
 * the year the suite runs in.
 *
 * Whole weeks, so a date keeps its weekday and the solver sees the very week
 * the fixture was measured on. The eight weeks keep the seeded days clear of
 * the specs that add a timeslot a month ahead or behind the real clock.
 *
 * No holiday, because a holiday is a hard rule for a minor (no work) and a
 * reading of every equity figure: a shift that moved a fixture's Wednesday
 * onto 14 July would hand the solver another problem on some weeks of the
 * year and not on others. Skipping those weeks costs at most half a year of
 * extra lead.
 */
export const SHIFT_DAYS = (() => {
  const now = new Date();
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  const lag = (today + MIN_LEAD_DAYS * DAY_MS - FIXTURE_MONDAY) / DAY_MS;
  let weeks = Math.max(0, Math.ceil(lag / 7));
  while (hitsAHoliday(weeks * 7)) {
    weeks++;
  }
  return weeks * 7;
})();

/**
 * `2026-07-12` → the same weekday {@link SHIFT_DAYS} later, as `AAAA-MM-JJ`:
 * what a spec writes for every date of its fixture.
 *
 * A **minor's birth date goes through it too**: the minor/adult rules read the
 * age at the timeslot's date, and moving both by the same amount keeps that
 * age — a person born in 2010 stays sixteen on the seeded day whichever year
 * the suite runs. An adult's birth date needs no shift: they stay an adult.
 */
export function shiftDate(date: string): string {
  const [year, month, day] = date.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day + SHIFT_DAYS)).toISOString().slice(0, 10);
}

/**
 * `days` days from the real clock, as `AAAA-MM-JJ` read on the local
 * calendar — for what a spec places relative to today (a consigne's date, a
 * window's bounds) rather than inside the seeded event.
 */
export function daysFromToday(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
}

/** `2026-07-10` → `10/07`: how most screens name a day. */
export function dayMonth(date: string): string {
  const [, month, day] = date.split('-');
  return `${day}/${month}`;
}

/** `2026-07-10` → `Vendredi 10/07`: how the selectors and tables of a day say it. */
export function dayLabel(date: string): string {
  const weekdays = ['Dimanche', 'Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi'];
  const [year, month, day] = date.split('-').map(Number);
  return `${weekdays[new Date(Date.UTC(year, month - 1, day)).getUTCDay()]} ${dayMonth(date)}`;
}

/**
 * The edition every spec works in: the one a fresh stack creates, numbered
 * `E1` like every edition since ids are drawn by the application (ADR 0046).
 */
export const EDITION_REFERENCE = 'E1';

/**
 * The SQL expression naming a typologie of the reference edition by its code.
 * Typologie ids are drawn by the application (`T1`, `T2`…), in an order no
 * fixture should depend on; the code (`STRATEGIE`) is what stays readable.
 */
export function typologieSql(code: string): string {
  return `(select id from typologie where edition_id = '${EDITION_REFERENCE}' and code = '${code}')`;
}

/**
 * The id the application gave the typologie carrying `code` — what a read of
 * a stand or an animateur names, where a payload may still cite the code.
 */
export async function typologieId(admin: APIRequestContext, code: string): Promise<string> {
  const reponse = await admin.get('/api/typologies');
  expect(reponse.ok()).toBe(true);
  const typologies = (await reponse.json()) as { id: string; code?: string | null }[];
  const id = typologies.find((typologie) => typologie.code === code)?.id;
  expect(id, `typologie of code ${code} must exist`).toBeTruthy();
  return id as string;
}

/**
 * The id the server drew for what a creation answered. Every referential is
 * numbered by the application now: a spec never chooses an id, it reads the
 * one it was given — at the top of the body, or on the entity a warning-
 * carrying answer wraps.
 */
export async function idCree(reponse: APIResponse): Promise<string> {
  expect(reponse.ok(), await reponse.text()).toBe(true);
  const corps = (await reponse.json()) as Record<string, unknown>;
  const direct = corps['id'];
  if (typeof direct === 'string') {
    return direct;
  }
  for (const [cle, valeur] of Object.entries(corps)) {
    if (cle === 'avertissements' || !valeur || typeof valeur !== 'object') {
      continue;
    }
    const imbrique = (valeur as Record<string, unknown>)['id'];
    if (typeof imbrique === 'string') {
      return imbrique;
    }
  }
  throw new Error(`no id in the creation answer: ${JSON.stringify(corps)}`);
}

/**
 * Ids of everything the suite seeds, so reseeding stays idempotent. These rows
 * are written straight into the database through `/api/database/import`, not
 * created through the API: their ids are fixture keys the counters never draw
 * (no `A12` shape), which is what lets a reseed find and delete them.
 */
export const SEED = {
  demandeur: 'E2E-A',
  cible: 'E2E-B',
  /** Seatless colleague, unavailable on the seeded day (edge-case suite). */
  collegueIndisponible: 'E2E-C',
  /** Seatless colleague, free on the seeded day — the only one an échange can really free the demandeur with. */
  collegueLibre: 'E2E-D',
  standDemandeur: 'E2E-S1',
  standCible: 'E2E-S2',
  creneauId: 987001,
  /** The day after, where the free colleague holds the seat a directed échange trades against. */
  creneauAutreJour: 987002,
  jour: shiftDate('2026-07-10'),
  jourSuivant: shiftDate('2026-07-11'),
} as const;

/**
 * Opens an API context holding a fresh admin session cookie (form login).
 * Callers must `dispose()` it.
 */
export async function contexteAdmin(
  playwright: Playwright,
  baseURL: string,
): Promise<APIRequestContext> {
  // Pinned on the reference edition: the suite seeds rows of that edition,
  // while an unpinned request follows whatever edition the target instance
  // flags as default — which any real deployment may have changed.
  const request = await playwright.request.newContext({
    baseURL,
    extraHTTPHeaders: { 'X-Edition-Id': EDITION_REFERENCE },
  });
  const connexion = await request.post('/j_security_check', {
    form: { j_username: 'admin', j_password: MOT_DE_PASSE_ADMIN },
    maxRedirects: 0,
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
 * prevalidation edge cases. With `avecCollegueLibre`, a fourth (Denis) who is
 * free that day and holds no seat: the only one an échange can hand the
 * demandeur's créneau to without leaving them at work, which is what the
 * « qui peut me remplacer ? » assistant is asked for. Denis also holds a seat
 * the day after: the only thing the demandeur can take in return, so the
 * assistant's three families all exist on this fixture.
 */
export async function seedPlanning(
  admin: APIRequestContext,
  options: { avecCollegueIndisponible?: boolean; avecCollegueLibre?: boolean } = {},
): Promise<void> {
  const script = [
    // Ses propres lignes seulement : la spec est repartie de la base de
    // référence dans son `beforeAll` (e2e/reference.ts), donc rien d'étranger
    // ne traîne. Ce nettoyage-ci sert au cas où la même spec réamorce
    // plusieurs fois.
    //
    // Ce bloc supprimait aussi les postes des préfixes SOLV- et FUZZ-, et les
    // lignes du créneau par identifiant plutôt que par préfixe — un `delete`
    // ajouté par panne, chacun commenté par l'incident qui l'avait fait
    // ajouter. C'est ce que la référence remplace.
    `delete from demande_echange where demandeur_id like 'E2E-%' or cible_id like 'E2E-%';`,
    `delete from verrouillage_planning where animateur_id like 'E2E-%';`,
    `delete from poste_affectation where id like 'E2E-%' or stand_id like 'E2E-%' or animateur_id like 'E2E-%';`,
    `delete from poste_affectation where creneau_id in (${SEED.creneauId}, ${SEED.creneauAutreJour});`,
    `delete from creneau where id in (${SEED.creneauId}, ${SEED.creneauAutreJour});`,
    `delete from animateur where id like 'E2E-%';`,
    `delete from stand_typologie where stand_id like 'E2E-%';`,
    `delete from stand where id like 'E2E-%';`,
    // The dataset itself. Every stand carries a typologie (issue #343): a
    // stand seeded without one would be refused at its next save. access_token is deliberately omitted: the database
    // generates it, and the suite reads it back through the admin API.
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('${EDITION_REFERENCE}', '${SEED.standDemandeur}', 'Stand E2E un', 1, 1, false);`,
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('${EDITION_REFERENCE}', '${SEED.standCible}', 'Stand E2E deux', 1, 1, false);`,
    `insert into stand_typologie (edition_id, stand_id, typologie) values ('${EDITION_REFERENCE}', '${SEED.standDemandeur}', ${typologieSql('STRATEGIE')});`,
    `insert into stand_typologie (edition_id, stand_id, typologie) values ('${EDITION_REFERENCE}', '${SEED.standCible}', ${typologieSql('STRATEGIE')});`,
    // Alice carries an email (mail-sending tests); Bruno deliberately none.
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager, email) values ('${EDITION_REFERENCE}', '${SEED.demandeur}', 'Alice', 'E2E', '1990-01-01', false, '${SEED.demandeur}@example.org');`,
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('${EDITION_REFERENCE}', '${SEED.cible}', 'Bruno', 'E2E', '1992-02-02', false);`,
    `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('${EDITION_REFERENCE}', ${SEED.creneauId}, '${SEED.jour}', '10:00', '12:00');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('${EDITION_REFERENCE}', 'E2E-P1', '${SEED.standDemandeur}', ${SEED.creneauId}, '${SEED.demandeur}');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('${EDITION_REFERENCE}', 'E2E-P2', '${SEED.standCible}', ${SEED.creneauId}, '${SEED.cible}');`,
    ...(options.avecCollegueIndisponible
      ? [
          `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('${EDITION_REFERENCE}', '${SEED.collegueIndisponible}', 'Chloé', 'E2E', '1995-03-03', false);`,
          `insert into animateur_jour_indispo (edition_id, animateur_id, jour) values ('${EDITION_REFERENCE}', '${SEED.collegueIndisponible}', '${SEED.jour}');`,
        ]
      : []),
    ...(options.avecCollegueLibre
      ? [
          `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('${EDITION_REFERENCE}', '${SEED.collegueLibre}', 'Denis', 'E2E', '1988-04-04', false);`,
          `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('${EDITION_REFERENCE}', ${SEED.creneauAutreJour}, '${SEED.jourSuivant}', '14:00', '16:00');`,
          `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('${EDITION_REFERENCE}', 'E2E-P3', '${SEED.standCible}', ${SEED.creneauAutreJour}, '${SEED.collegueLibre}');`,
        ]
      : []),
  ].join('\n');

  const importReponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: script,
  });
  expect(importReponse.ok(), await importReponse.text()).toBe(true);
  await publierPlanning(admin);
}

/**
 * Publishes what was just seeded. Since issue #245 the espace animateur shows
 * the **published** plan, so a fixture that only writes `poste_affectation`
 * leaves every espace legitimately empty.
 *
 * A `409` means nobody is concerned — the published plan already says what the
 * fixture says — which is a perfectly good state to start a test from, not a
 * failed seeding.
 */
export async function publierPlanning(admin: APIRequestContext): Promise<void> {
  // An explicit body, like every other caller: the endpoint takes the
  // deferral list (issue #503), and leaving the server to guess a missing
  // Content-Type is a dependency on a default nothing pins down.
  const reponse = await admin.post('/api/planning/publication', { data: { exclusions: [] } });
  expect(reponse.ok() || reponse.status() === 409, await reponse.text()).toBe(true);
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
  creneaux: CreneauSeed[],
): Promise<void> {
  const prefixes = ['E2E-', 'SOLV-', 'FUZZ-'];
  const statements: string[] = [];
  for (const prefixe of prefixes) {
    statements.push(
      `delete from demande_echange where demandeur_id like '${prefixe}%' or cible_id like '${prefixe}%';`,
      `delete from verrouillage_planning where animateur_id like '${prefixe}%' or stand_id like '${prefixe}%';`,
      `delete from contrainte_ad_hoc where id in (select contrainte_id from contrainte_animateur where animateur_id like '${prefixe}%');`,
      `delete from contrainte_ad_hoc where stand_id like '${prefixe}%' or id like '${prefixe}%';`,
      `delete from poste_affectation where id like '${prefixe}%' or stand_id like '${prefixe}%' or animateur_id like '${prefixe}%';`,
    );
  }
  statements.push(
    `delete from verrouillage_planning where creneau_id >= 987000 and creneau_id < 988000;`,
    `delete from contrainte_ad_hoc where creneau_id >= 987000 and creneau_id < 988000;`,
    `delete from poste_affectation where creneau_id >= 987000 and creneau_id < 988000;`,
    `delete from creneau where id >= 987000 and id < 988000;`,
  );
  for (const prefixe of prefixes) {
    statements.push(
      `delete from animateur where id like '${prefixe}%';`,
      `delete from stand_typologie where stand_id like '${prefixe}%';`,
      `delete from stand where id like '${prefixe}%';`,
    );
  }
  for (const stand of stands) {
    statements.push(
      `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('${EDITION_REFERENCE}', '${stand.id}', '${stand.nom}', ${stand.effectif}, ${stand.effectif}, ${stand.reserveMajeurs ?? false});`,
      // A stand always carries a typologie (issue #343): without one, the specs
      // that then edit the stand through the API would be refused.
      `insert into stand_typologie (edition_id, stand_id, typologie) values ('${EDITION_REFERENCE}', '${stand.id}', ${typologieSql('STRATEGIE')});`,
    );
  }
  for (const animateur of animateurs) {
    statements.push(
      `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager, email) values ('${EDITION_REFERENCE}', '${animateur.id}', '${animateur.prenom}', '${animateur.nom}', '${animateur.dateNaissance}', false, '${animateur.id}@example.org');`,
    );
    for (const jour of animateur.joursIndisponibles ?? []) {
      statements.push(
        `insert into animateur_jour_indispo (edition_id, animateur_id, jour) values ('${EDITION_REFERENCE}', '${animateur.id}', '${jour}');`,
      );
    }
  }
  for (const creneau of creneaux) {
    statements.push(
      `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('${EDITION_REFERENCE}', ${creneau.id}, '${creneau.date}', '${creneau.debut}', '${creneau.fin}');`,
    );
  }
  const importReponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: statements.join('\n'),
  });
  expect(importReponse.ok(), await importReponse.text()).toBe(true);
}

/** What a solve says about its own outcome. */
export interface DiagnosticJob {
  hardScore: number;
  postesNonPourvus: number;
  score: string;
}

/**
 * The plan the solve replaced (issue #274). Null on the first solve of an
 * edition: there was nothing to capture, so nothing to compare against.
 */
export interface PlanPrecedentJob {
  snapshotId: number;
  score: string | null;
  degraded: boolean;
}

/** Terminal view of a solver job, as `/api/jobs/{id}` answers it. */
export interface JobTermine {
  id: string;
  status: string;
  error: string | null;
  /** A SOLVE wraps its diagnostic, next to the plan it replaced (issue #274). */
  result: { diagnostic: DiagnosticJob; previousPlan: PlanPrecedentJob | null } | null;
}

/**
 * Launches a short solve on the current reference data and waits for the job
 * to end. Fails loudly on a FAILED job; the caller asserts on the result.
 */
export async function lancerSolve(admin: APIRequestContext, seconds: number): Promise<JobTermine> {
  const lancement = await admin.post(`/api/solve/async/reference-data?seconds=${seconds}`);
  expect(lancement.status(), await lancement.text()).toBe(202);
  const { id } = (await lancement.json()) as { id: string };
  return awaitJob(admin, id, seconds);
}

/**
 * Waits for an already-submitted job — a full solve, an incremental one — to
 * complete, and returns its terminal view. Fails loudly on a FAILED or
 * CANCELLED job, and on one that outlives its budget by a minute.
 */
export async function awaitJob<T = JobTermine>(
  admin: APIRequestContext,
  id: string,
  seconds: number,
): Promise<T> {
  const debut = Date.now();
  for (;;) {
    const reponse = await admin.get(`/api/jobs/${id}`);
    expect(reponse.ok()).toBe(true);
    const job = (await reponse.json()) as JobTermine;
    if (job.status === 'COMPLETED') {
      return job as unknown as T;
    }
    expect(job.status, job.error ?? 'job in a terminal non-completed state').not.toMatch(
      /FAILED|CANCELLED/,
    );
    expect(Date.now() - debut, 'solve should finish well within its budget').toBeLessThan(
      (seconds + 60) * 1000,
    );
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
export function occupantDe(
  planning: PlanningPersiste,
  standId: string,
  creneauId: number,
): string | null {
  return (
    planning.postes.find((poste) => poste.stand?.id === standId && poste.creneau?.id === creneauId)
      ?.animateur?.id ?? null
  );
}

/** The (standId, creneauId) pairs an animateur holds, sorted for comparison. */
export function postesDe(planning: PlanningPersiste, animateurId: string): string[] {
  return planning.postes
    .filter((poste) => poste.animateur?.id === animateurId)
    .map((poste) => `${poste.stand?.id}@${poste.creneau?.id}`)
    .sort((gauche, droite) => (gauche < droite ? -1 : Number(gauche > droite)));
}

/**
 * Opens a mat-select by clicking its whole form field: aiming at the select
 * itself trips Playwright's actionability check — the floating `mat-label`
 * sits at the aim point and "intercepts pointer events" — while a click
 * anywhere on the field opens the panel for real users and tests alike.
 */
/**
 * Opens the first `mat-select` labelled `label` under `root`. Hand a dialog,
 * not the page, when one is open: a page field of the same name behind the
 * modal backdrop would be picked first and never receive the click.
 */
export async function ouvrirSelect(root: Page | Locator, label: string): Promise<void> {
  await root.locator('mat-form-field').filter({ hasText: label }).first().click();
  // The panel, open for good: an option clicked during the opening animation
  // is not taken, and the panel then stays open behind its transparent
  // backdrop — every later click on the page is intercepted until the test
  // times out.
  await expect(pageOf(root).getByRole('listbox')).toBeVisible();
}

/**
 * Picks `option` in the `mat-select` labelled `label` under `root` — by
 * keyboard, through the select's own typeahead, so no panel opens and no
 * backdrop stands in the way of the next click. The mouse path proved flaky
 * on CI: a click that lands on the field while its panel is still animating
 * toggles it back open, and every later click is then intercepted. What is
 * asserted is the value the field shows, which is what the form will save.
 */
export async function choisirOption(
  root: Page | Locator,
  label: string,
  option: string,
): Promise<void> {
  const combobox = root.getByRole('combobox', { name: label }).first();
  await combobox.focus();
  await combobox.pressSequentially(option, { delay: 20 });
  await expect(combobox).toContainText(option);
}

function pageOf(root: Page | Locator): Page {
  return 'page' in root ? root.page() : root;
}

/**
 * The dialog just opened, once it is ready to be typed in. Material focuses
 * the first field after the open animation: a `fill()` that lands in between
 * gets its text moved to that field — a spec that filled « Prénom » found the
 * text in « Identifiant », one run in three. Waiting for the focus to settle
 * inside the dialog is what makes the next fill land where it was aimed.
 *
 * The focus is awaited on **or** inside the dialog: several dialogues focus
 * their own container (`autoFocus: 'dialog'`, the confirmation dialogue among
 * them), where a `:focus` descendant never appears — the wait would then burn
 * its whole timeout before failing on a dialogue that was ready all along.
 */
export async function dialogueOuvert(page: Page): Promise<Locator> {
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect
    .poll(() => dialog.evaluate((element) => element.contains(document.activeElement)))
    .toBe(true);
  return dialog;
}

/**
 * One tab of the espace animateur (issue #615): the label carries the icon
 * ligature too, so the `button` role does not find it by its name.
 */
export function ongletEspace(page: Page, nom: string): Locator {
  return page.locator('mat-button-toggle', { hasText: nom }).locator('button');
}

/**
 * A browser page carrying the admin session of {@code admin} (its cookies are
 * copied into a fresh context). The caller closes the page's context.
 */
export async function pageAdmin(browser: Browser, admin: APIRequestContext): Promise<Page> {
  const contexte = await browser.newContext({ storageState: await admin.storageState() });
  // Same edition pinning as contexteAdmin, browser-side: the SPA reads its
  // edition from localStorage. Conditional, so a test that deliberately
  // switches editions (setStoredEditionIdAndReload writes before reloading)
  // is not snapped back on the next navigation.
  await contexte.addInitScript((edition) => {
    if (!localStorage.getItem('planning-equipes.editionId')) {
      localStorage.setItem('planning-equipes.editionId', edition);
    }
  }, EDITION_REFERENCE);
  return contexte.newPage();
}

/* --------------------- Espace-animateur authentication ------------------- */

/** Where the e2e stack's Mailpit serves its REST API (docker, port 8025). */
const MAILPIT_URL = process.env['E2E_MAILPIT_URL'] ?? 'http://localhost:8025';

/**
 * Opens the espace session of one animateur the way the animateur would:
 * request a code, read it in Mailpit, exchange it for the HttpOnly cookie.
 * The cookie lands in the requester's jar — pass `page.request` so the page
 * itself is authenticated, or an `APIRequestContext` for API-level flows.
 */
export async function ouvrirSessionEspace(
  requeteur: APIRequestContext,
  jeton: string,
  email: string,
): Promise<void> {
  const avant = await nombreDeMails(requeteur, email);
  const envoi = await requeteur.post(`/api/espace-animateur/${jeton}/code`);
  expect(envoi.ok(), await envoi.text()).toBe(true);
  const code = await lireCodeMailpit(requeteur, email, avant);
  const session = await requeteur.post(`/api/espace-animateur/${jeton}/session`, {
    data: { code },
  });
  expect(session.status(), await session.text()).toBe(204);
}

/** The newest access code Mailpit holds for `email` — for UI flows where the click itself sent it. */
export function dernierCodeMailpit(requeteur: APIRequestContext, email: string): Promise<string> {
  return lireCodeMailpit(requeteur, email, 0);
}

/** One page of a Mailpit search. `messages_count` is the REAL match total — `messages` is capped at 50 per page. */
interface RechercheMailpit {
  messages_count: number;
  messages: { ID: string }[];
}

async function rechercherMails(
  requeteur: APIRequestContext,
  email: string,
): Promise<RechercheMailpit | null> {
  const query = encodeURIComponent('to:"' + email + '"');
  const reponse = await requeteur.get(`${MAILPIT_URL}/api/v1/search?query=${query}`);
  return reponse.ok() ? ((await reponse.json()) as RechercheMailpit) : null;
}

/** How many mails Mailpit holds for `email` — read before and after a send that must happen once. */
export async function nombreDeMails(requeteur: APIRequestContext, email: string): Promise<number> {
  return (await rechercherMails(requeteur, email))?.messages_count ?? 0;
}

/** Polls Mailpit until the freshly sent code mail lands, newest first. */
async function lireCodeMailpit(
  requeteur: APIRequestContext,
  email: string,
  mailsAvant: number,
): Promise<string> {
  for (let essai = 0; essai < 40; essai++) {
    const recherche = await rechercherMails(requeteur, email);
    if (recherche && recherche.messages_count > mailsAvant && recherche.messages.length > 0) {
      // Parmi les seuls messages NOUVEAUX, du plus récent au plus ancien : la
      // publication (issue #245) écrit elle aussi à cette adresse, et son mail
      // peut arriver entre-temps sans porter de code. Se limiter aux nouveaux
      // évite de rejouer un code déjà consommé.
      const nouveaux = recherche.messages.slice(0, recherche.messages_count - mailsAvant);
      for (const message of nouveaux) {
        const detail = await requeteur.get(`${MAILPIT_URL}/api/v1/message/${message.ID}`);
        const texte = ((await detail.json()) as { Text: string }).Text;
        const code = /\b(\d{6})\b/.exec(texte)?.[1];
        if (code) {
          return code;
        }
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Aucun code d'accès reçu dans Mailpit pour ${email} (${MAILPIT_URL})`);
}

/** The database-generated espace token of one seeded animateur, via the admin API. */
export async function jetonDe(admin: APIRequestContext, animateurId: string): Promise<string> {
  const reponse = await admin.get('/api/animateurs');
  expect(reponse.ok()).toBe(true);
  const animateurs = (await reponse.json()) as { id: string; accessToken?: string }[];
  const jeton = animateurs.find((animateur) => animateur.id === animateurId)?.accessToken;
  expect(jeton, `animateur ${animateurId} must exist with a token`).toBeTruthy();
  return jeton as string;
}
