// Shared plumbing of the perimeter suite: an authenticated admin API context,
// and the seeding of a tiny two-seat planning through the admin API only —
// exactly what a browser could do, no backdoor into the database.

import { APIRequestContext, Browser, Locator, Page, Playwright, expect } from '@playwright/test';

export const MOT_DE_PASSE_ADMIN = process.env['E2E_ADMIN_PASSWORD'] ?? 'admin';

/** Ids of everything the suite seeds, so reseeding stays idempotent. */
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
  jour: '2026-07-10',
  jourSuivant: '2026-07-11',
} as const;

/**
 * Opens an API context holding a fresh admin session cookie (form login).
 * Callers must `dispose()` it.
 */
export async function contexteAdmin(
  playwright: Playwright,
  baseURL: string,
): Promise<APIRequestContext> {
  // Pinned on the DEFAUT edition: the suite seeds hard-coded 'DEFAUT' rows,
  // while an unpinned request follows whatever edition the target instance
  // flags as default — which any real deployment may have changed.
  const request = await playwright.request.newContext({
    baseURL,
    extraHTTPHeaders: { 'X-Edition-Id': 'DEFAUT' },
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
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${SEED.standDemandeur}', 'Stand E2E un', 1, 1, false);`,
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${SEED.standCible}', 'Stand E2E deux', 1, 1, false);`,
    `insert into stand_typologie (edition_id, stand_id, typologie) values ('DEFAUT', '${SEED.standDemandeur}', 'STRATEGIE');`,
    `insert into stand_typologie (edition_id, stand_id, typologie) values ('DEFAUT', '${SEED.standCible}', 'STRATEGIE');`,
    // Alice carries an email (mail-sending tests); Bruno deliberately none.
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager, email) values ('DEFAUT', '${SEED.demandeur}', 'Alice', 'E2E', '1990-01-01', false, '${SEED.demandeur}@example.org');`,
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('DEFAUT', '${SEED.cible}', 'Bruno', 'E2E', '1992-02-02', false);`,
    `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('DEFAUT', ${SEED.creneauId}, '2026-07-10', '10:00', '12:00');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', 'E2E-P1', '${SEED.standDemandeur}', ${SEED.creneauId}, '${SEED.demandeur}');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', 'E2E-P2', '${SEED.standCible}', ${SEED.creneauId}, '${SEED.cible}');`,
    ...(options.avecCollegueIndisponible
      ? [
          `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('DEFAUT', '${SEED.collegueIndisponible}', 'Chloé', 'E2E', '1995-03-03', false);`,
          `insert into animateur_jour_indispo (edition_id, animateur_id, jour) values ('DEFAUT', '${SEED.collegueIndisponible}', '${SEED.jour}');`,
        ]
      : []),
    ...(options.avecCollegueLibre
      ? [
          `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('DEFAUT', '${SEED.collegueLibre}', 'Denis', 'E2E', '1988-04-04', false);`,
          `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('DEFAUT', ${SEED.creneauAutreJour}, '${SEED.jourSuivant}', '14:00', '16:00');`,
          `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', 'E2E-P3', '${SEED.standCible}', ${SEED.creneauAutreJour}, '${SEED.collegueLibre}');`,
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
  const reponse = await admin.post('/api/planning/publication');
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
      `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${stand.id}', '${stand.nom}', ${stand.effectif}, ${stand.effectif}, ${stand.reserveMajeurs ?? false});`,
      // A stand always carries a typologie (issue #343): without one, the specs
      // that then edit the stand through the API would be refused.
      `insert into stand_typologie (edition_id, stand_id, typologie) values ('DEFAUT', '${stand.id}', 'STRATEGIE');`,
    );
  }
  for (const animateur of animateurs) {
    statements.push(
      `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager, email) values ('DEFAUT', '${animateur.id}', '${animateur.prenom}', '${animateur.nom}', '${animateur.dateNaissance}', false, '${animateur.id}@example.org');`,
    );
    for (const jour of animateur.joursIndisponibles ?? []) {
      statements.push(
        `insert into animateur_jour_indispo (edition_id, animateur_id, jour) values ('DEFAUT', '${animateur.id}', '${jour}');`,
      );
    }
  }
  for (const creneau of creneaux) {
    statements.push(
      `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('DEFAUT', ${creneau.id}, '${creneau.date}', '${creneau.debut}', '${creneau.fin}');`,
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

  const debut = Date.now();
  for (;;) {
    const reponse = await admin.get(`/api/jobs/${id}`);
    expect(reponse.ok()).toBe(true);
    const job = (await reponse.json()) as JobTermine;
    if (job.status === 'COMPLETED') {
      return job;
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
    .sort();
}

/**
 * Opens a mat-select by clicking its whole form field: aiming at the select
 * itself trips Playwright's actionability check — the floating `mat-label`
 * sits at the aim point and "intercepts pointer events" — while a click
 * anywhere on the field opens the panel for real users and tests alike.
 */
export async function ouvrirSelect(page: Page, label: string): Promise<void> {
  await page.locator('mat-form-field').filter({ hasText: label }).first().click();
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
 * A browser page carrying the admin session of {@code admin} (its cookies are
 * copied into a fresh context). The caller closes the page's context.
 */
export async function pageAdmin(browser: Browser, admin: APIRequestContext): Promise<Page> {
  const contexte = await browser.newContext({ storageState: await admin.storageState() });
  // Same edition pinning as contexteAdmin, browser-side: the SPA reads its
  // edition from localStorage. Conditional, so a test that deliberately
  // switches editions (setStoredEditionIdAndReload writes before reloading)
  // is not snapped back on the next navigation.
  await contexte.addInitScript(() => {
    if (!localStorage.getItem('planning-equipes.editionId')) {
      localStorage.setItem('planning-equipes.editionId', 'DEFAUT');
    }
  });
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
  const reponse = await requeteur.get(
    `${MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`,
  );
  return reponse.ok() ? ((await reponse.json()) as RechercheMailpit) : null;
}

async function nombreDeMails(requeteur: APIRequestContext, email: string): Promise<number> {
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
