// The organiser's real week under a heatwave (issue #4), played on the
// anonymised real-world grid (`festival-hivernal.yaml`: 153 animateurs, 65
// stands, sixteen days): the edition is solved and published as usual; an
// arrêté closes 12h–18h on three consecutive days, the evening is reopened
// 18h–22h and the day's meal window is aligned on it from the Consignes
// screen; the plan is re-solved incrementally, still feasible, and published
// again to the people concerned — with the modified days named in their
// mail; then the alert is lifted on the third day only, and the nominal grid
// of that day gets its seats back.
//
// What a unit test cannot see is the chain itself: that the request the form
// previews is the one written, that the incremental solve leaves the other
// days alone, and that the publication says so.
//
// A consigne only accepts a date strictly ahead of the REAL clock (the
// packaged application freezes no date), so the fixture is shifted at test
// time by a whole number of weeks, far into the future: weekdays are kept,
// and so are the ages of the animateurs, since every date of the file moves
// by the same amount.

import { APIRequestContext, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { JobTermine, contexteAdmin, dialogueOuvert, ouvrirSelect, pageAdmin } from './support';
import { repartirDeLaReference } from './reference';

/** The bundled fixture, read from the sources: `npm run e2e` runs from `src/main/webui`. */
const FIXTURE = join(process.cwd(), '..', 'resources', 'scenarios', 'festival-hivernal.yaml');

/**
 * 1 722 weeks: the fixture's Monday 2027-02-01 lands on Monday 2060-02-02.
 * A multiple of seven, so every date keeps its weekday.
 */
const SHIFT_DAYS = 1722 * 7;

const MOTIF = 'Arrêté préfectoral canicule';

/**
 * Every solve of the journey is a full one restarted from the plan (see
 * `lockDaysExcept`): the nominal reaches zero hard in about 70 s locally on
 * this fixture, roughly twice that on the CI runner, and the two others start
 * from a plan already staffed. The budget leaves room.
 */
const FULL_SOLVE_SECONDS = 240;

/** Where the e2e stack's Mailpit serves its REST API (docker, port 8025). */
const MAILPIT_URL = process.env['E2E_MAILPIT_URL'] ?? 'http://localhost:8025';

/** `2027-02-03` → `2060-02-04`: one date of the fixture, moved by the shift. */
function shiftDate(date: string): string {
  const [year, month, day] = date.split('-').map(Number);
  const shifted = new Date(Date.UTC(year, month - 1, day + SHIFT_DAYS));
  return shifted.toISOString().slice(0, 10);
}

/** The three opening days of the real event — Wednesday to Friday — once shifted. */
const DAYS = ['2027-02-03', '2027-02-04', '2027-02-05'].map(shiftDate);
const [DAY1, DAY2, DAY3] = DAYS as [string, string, string];

/** `Mercredi 04/02`: the date as the page's selectors and table say it. */
function dateLabel(date: string): string {
  const weekdays = ['Dimanche', 'Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi'];
  const [year, month, day] = date.split('-').map(Number);
  return `${weekdays[new Date(year, month - 1, day).getDay()]} ${dayMonth(date)}`;
}

/** `2060-02-04` → `04/02`: how the publication names a day. */
function dayMonth(date: string): string {
  const [, month, day] = date.split('-');
  return `${day}/${month}`;
}

let admin: APIRequestContext;

test.beforeAll(async ({ playwright }, testInfo) => {
  admin = await contexteAdmin(playwright, testInfo.project.use.baseURL as string);
  await repartirDeLaReference(admin);
});

test.afterAll(async () => {
  // The edition is handed back usable: the reference state, as every spec starts from.
  await repartirDeLaReference(admin);
  await admin.dispose();
});

/* ------------------------------- the solver ------------------------------- */

/** Polls the job until it completes; a failed or cancelled job fails loudly. */
async function awaitJob(id: string, seconds: number): Promise<JobTermine> {
  const start = Date.now();
  for (;;) {
    const response = await admin.get(`/api/jobs/${id}`);
    expect(response.ok()).toBe(true);
    const job = (await response.json()) as JobTermine;
    if (job.status === 'COMPLETED') {
      return job;
    }
    expect(job.status, job.error ?? 'job in a terminal non-completed state').not.toMatch(
      /FAILED|CANCELLED/,
    );
    // Building a 3 000-seat problem and persisting its plan sit outside the
    // solver's budget: the margin is for them.
    expect(Date.now() - start, 'solve should finish well within its budget').toBeLessThan(
      (seconds + 120) * 1000,
    );
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
}

async function solveFromReferenceData(): Promise<JobTermine> {
  const launch = await admin.post(`/api/solve/async/reference-data?seconds=${FULL_SOLVE_SECONDS}`);
  expect(launch.status(), await launch.text()).toBe(202);
  const { id } = (await launch.json()) as { id: string };
  return awaitJob(id, FULL_SOLVE_SECONDS);
}

/**
 * The organiser's way of re-solving around a consigne: lock every other day
 * (`JOUR` locks, the deliberate freeze of issue #87), then run a full solve
 * restarted from the plan. The incremental re-solve is the wrong tool here,
 * twice over: its automatic perimeter pins every seat whose holder is still
 * available — including, after a lift, a person whose 18h–20h grew back to
 * 14h–20h next to another seat, a hard violation nobody can move any more —
 * and its local search on a mostly-pinned problem is too slow to trade seats
 * within the budget when the construction leaves one empty. A full solve keeps
 * the unlocked seats movable and fixes both.
 */
async function lockDaysExcept(free: readonly string[]): Promise<void> {
  const days = new Set((await creneaux()).map((creneau) => creneau.date).filter(Boolean));
  for (const jour of [...days].sort()) {
    if (free.includes(jour as string)) {
      continue;
    }
    const lock = await admin.post('/api/verrouillages', {
      data: { type: 'JOUR', jour, raison: 'E2E : journée hors consigne, préservée' },
    });
    expect(lock.ok(), await lock.text()).toBe(true);
  }
}

/** The hard score of the last analysed plan, as the Contraintes screen reads it. */
async function constraintsHardScore(): Promise<number | null> {
  const response = await admin.get('/api/constraints');
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { hardScore: number | null }).hardScore;
}

/* ------------------------------ the persisted plan ------------------------------ */

interface Seat {
  id: string;
  stand: { id: string } | null;
  creneau: { id: number; date: string | null; heureDebut: string; heureFin: string } | null;
  animateur: { id: string } | null;
  /** Renfort generated above the staffing the window asks for (issue #505). */
  optionnel?: boolean;
  heureDebutEffective?: string | null;
  heureFinEffective?: string | null;
}

/** A renfort may stay empty: what is owed is what must be staffed. */
function owed(seats: Seat[]): Seat[] {
  return seats.filter((seat) => !seat.optionnel);
}

async function persistedPlan(): Promise<Seat[]> {
  const response = await admin.get('/api/planning/persisted', { timeout: 120_000 });
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { postes: Seat[] }).postes;
}

function seatsOf(plan: Seat[], date: string): Seat[] {
  return plan.filter((seat) => seat.creneau?.date === date);
}

/** The hours a seat really covers: its créneau's, unless a closure narrowed them. */
function effectiveSpan(seat: Seat): string {
  return `${seat.heureDebutEffective ?? seat.creneau?.heureDebut}-${seat.heureFinEffective ?? seat.creneau?.heureFin}`;
}

/**
 * The hours a day's seats really cover — stand and effective span, one entry
 * per seat. A stand's own closing hours already narrow some seats on an
 * ordinary day (a hall shut at 19h, a bar open from 21h): what the consigne
 * adds is only told apart by comparing with the nominal plan.
 */
function seatSpans(plan: Seat[], date: string): string[] {
  return seatsOf(plan, date)
    .map((seat) => `${seat.stand?.id}@${effectiveSpan(seat)}`)
    .sort();
}

/** The shape of a day's seats — stand and hours, one entry per seat — regardless of who sits. */
function seatShapes(plan: Seat[], date: string): string[] {
  return seatsOf(plan, date)
    .map((seat) => `${seat.stand?.id}@${seat.creneau?.heureDebut}-${seat.creneau?.heureFin}`)
    .sort();
}

/* ------------------------------ consignes & grid ------------------------------ */

interface ConsigneState {
  consignes: {
    date: string;
    motif: string;
    fenetres: { debut: string; fin: string | null }[];
    ouvertures: { standId: string; debut: string; fin: string | null }[];
    repas: { soirDebut: string | null; soirFin: string | null; justification: string } | null;
    creneauxAjoutes: number[];
  }[];
  aujourdhui: string;
}

async function consignesState(): Promise<ConsigneState> {
  const response = await admin.get('/api/consignes');
  expect(response.ok()).toBe(true);
  return (await response.json()) as ConsigneState;
}

interface Creneau {
  id: number;
  date: string;
  heureDebut: string;
  heureFin: string;
}

async function creneaux(): Promise<Creneau[]> {
  const response = await admin.get('/api/creneaux');
  expect(response.ok()).toBe(true);
  return (await response.json()) as Creneau[];
}

async function legalParameters(): Promise<Record<string, unknown>> {
  const response = await admin.get('/api/parametres-legaux');
  expect(response.ok()).toBe(true);
  return (await response.json()) as Record<string, unknown>;
}

/* -------------------------------- publication -------------------------------- */

interface PublicationPreview {
  jamaisPublie: boolean;
  dernierePublicationLe: string | null;
  nombreConcernes: number;
  destinataires: {
    animateurId: string;
    email: string | null;
    premiereDiffusion: boolean;
    changements: string[];
  }[];
}

async function publicationPreview(): Promise<PublicationPreview> {
  const response = await admin.get('/api/planning/publication', { timeout: 120_000 });
  expect(response.ok(), await response.text()).toBe(true);
  return (await response.json()) as PublicationPreview;
}

/** Sends the mails for real: with a PDF per person, it takes tens of seconds. */
async function publishByApi(): Promise<{ envoyes: number }> {
  const response = await admin.post('/api/planning/publication', { timeout: 600_000 });
  expect(response.ok(), await response.text()).toBe(true);
  return (await response.json()) as { envoyes: number };
}

/** The `dd/MM` every change of the preview is dated on: « mercredi 04/02 : … ». */
function changedDays(preview: PublicationPreview): Set<string> {
  const days = new Set<string>();
  for (const recipient of preview.destinataires) {
    for (const change of recipient.changements) {
      const day = /^\S+ (\d{2}\/\d{2}) :/.exec(change)?.[1];
      expect(day, `a change should name its day: « ${change} »`).toBeTruthy();
      days.add(day as string);
    }
  }
  return days;
}

/* ---------------------------------- Mailpit ---------------------------------- */

interface MailSearch {
  messages_count: number;
  messages: { ID: string }[];
}

async function searchMails(email: string): Promise<MailSearch> {
  const response = await admin.get(
    `${MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`,
  );
  expect(response.ok(), `Mailpit should answer at ${MAILPIT_URL}`).toBe(true);
  return (await response.json()) as MailSearch;
}

async function mailCount(email: string): Promise<number> {
  return (await searchMails(email)).messages_count;
}

/** Waits for one more mail than `before` for `email`, and returns the newest one's text. */
async function newestMailText(email: string, before: number): Promise<string> {
  await expect.poll(() => mailCount(email), { timeout: 60_000 }).toBeGreaterThan(before);
  const search = await searchMails(email);
  const detail = await admin.get(`${MAILPIT_URL}/api/v1/message/${search.messages[0].ID}`);
  expect(detail.ok()).toBe(true);
  return ((await detail.json()) as { Text: string }).Text;
}

/* ---------------------------------- the week ---------------------------------- */

test('la semaine de l’organisateur : canicule posée, résolue, publiée, puis levée sur un jour', async ({
  browser,
}) => {
  // Three real solves on a 3 000-seat problem, and three mailings with a PDF
  // per person: minutes, not seconds.
  test.slow();
  test.setTimeout(20 * 60_000);

  // 1. The real-world edition, shifted into the future and imported as a file
  //    — the same route the Imports screen's « Importer un fichier » uses.
  const yaml = readFileSync(FIXTURE, 'utf8').replace(/\b\d{4}-\d{2}-\d{2}\b/g, (date: string) =>
    shiftDate(date),
  );
  const importation = await admin.post('/api/reference-data/import-scenario-fichier', {
    headers: { 'Content-Type': 'application/x-yaml' },
    data: yaml,
    timeout: 180_000,
  });
  expect(importation.ok(), await importation.text()).toBe(true);

  const grid = await creneaux();
  expect(grid.some((creneau) => creneau.date === DAY1)).toBe(true);
  const nominalGridDay3 = grid
    .filter((creneau) => creneau.date === DAY3)
    .map((creneau) => `${creneau.heureDebut}-${creneau.heureFin}`)
    .sort();
  expect(nominalGridDay3.length).toBeGreaterThan(0);
  const animateurs = (await (await admin.get('/api/animateurs')).json()) as {
    id: string;
    email: string | null;
  }[];
  expect(animateurs).toHaveLength(153);
  const legalBefore = await legalParameters();

  // 2. Nominal: solved to zero hard, then published to everybody from the
  //    Publication screen — the first publication concerns the whole team.
  const nominal = await solveFromReferenceData();
  expect(nominal.result?.diagnostic.hardScore, 'the nominal grid must be feasible').toBe(0);
  await expect.poll(constraintsHardScore, { timeout: 60_000 }).toBe(0);
  const nominalPlan = await persistedPlan();
  const nominalSeatsDay3 = seatShapes(nominalPlan, DAY3);
  const nominalSpansDay3 = seatSpans(nominalPlan, DAY3);
  expect(nominalSeatsDay3.length).toBeGreaterThan(0);
  expect(owed(nominalPlan).every((seat) => seat.animateur !== null)).toBe(true);

  const page = await pageAdmin(browser, admin);
  await page.goto('/publication');
  const publishButton = page.getByRole('button', { name: /Publier — \d+ personnes concernées/ });
  await expect(publishButton).toBeEnabled();
  await publishButton.click();
  await page.getByRole('button', { name: 'Publier', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Tout le monde est à jour' })).toBeVisible({
    timeout: 600_000,
  });
  const afterFirst = await publicationPreview();
  expect(afterFirst.jamaisPublie).toBe(false);
  expect(afterFirst.dernierePublicationLe).not.toBeNull();
  expect(afterFirst.nombreConcernes).toBe(0);

  // One witness: somebody seated on the first day of the alert, with an
  // address — the mails of the week are read through them.
  const emailOf = (animateurId: string | undefined) =>
    animateurs.find((animateur) => animateur.id === animateurId)?.email ?? null;
  const witnessEmail = seatsOf(nominalPlan, DAY1)
    .map((seat) => emailOf(seat.animateur?.id))
    .find((email) => email !== null);
  expect(witnessEmail, 'somebody with an e-mail must sit on the first day').toBeTruthy();
  await expect
    .poll(() => mailCount(witnessEmail as string), { timeout: 60_000 })
    .toBeGreaterThan(0);

  // 3. Canicule: the consigne laid down from the screen on the three days.
  await page.goto('/consignes');
  await expect(page.locator('#contenu')).toContainText('Aucune journée sous consigne');
  await page.getByRole('button', { name: 'Poser une consigne' }).click();
  const form = await dialogueOuvert(page);
  await expect(form).toContainText('Poser une consigne');

  await ouvrirSelect(page, 'Dates');
  for (const day of DAYS) {
    await page.getByRole('option', { name: dateLabel(day) }).click();
  }
  await page.keyboard.press('Escape');
  // The band is the form's default, 12h–18h: the résumé says so.
  await expect(form).toContainText('Tous les stands fermés de 12h–18h, sur 3 date(s).');
  await form.getByLabel('Motif').fill(MOTIF);

  // One default window, the evening, that every ticked stand reopens on.
  await form.getByRole('button', { name: 'Ajouter une fenêtre' }).first().click();
  const window = form.locator('.consigne-form-fenetres .consigne-fenetre').first();
  await window.locator('input[type="time"]').nth(0).fill('18:00');
  await window.locator('input[type="time"]').nth(1).fill('22:00');

  // The stands arrive ticked as the server proposes them for the first date
  // and the band: what the screen lists is what the API pre-selects.
  const preselection = (await (
    await admin.post('/api/consignes/preselection', {
      data: { date: DAY1, fermetureDebut: '12:00', fermetureFin: '18:00' },
    })
  ).json()) as { stands: { standId: string; preCoche: boolean }[] };
  const ticked = preselection.stands
    .filter((stand) => stand.preCoche)
    .map((stand) => stand.standId)
    .sort();
  expect(ticked.length).toBeGreaterThan(0);
  await expect(form.locator('.consigne-stand')).toHaveCount(preselection.stands.length);
  await expect(form).toContainText(`${ticked.length} coché(s) sur ${preselection.stands.length}`);

  // « Fenêtres repas ce jour-là »: the evening meal window follows the
  // compensation, the justification is written for the organiser.
  await form.getByText('Fenêtres repas ce jour-là').click();
  await form.getByRole('button', { name: 'Aligner le soir sur la compensation' }).click();
  await expect(form.getByLabel('Soir, début')).toHaveValue('18:00');
  await expect(form.getByLabel('Soir, fin')).toHaveValue('22:00');
  await expect(form.getByLabel('Justification')).toHaveValue(
    'Les équipes mangent pendant la fermeture',
  );
  await expect(form.locator('.consigne-form-repas .consigne-chip-repas')).toBeVisible();

  await form.getByRole('button', { name: 'Aperçu' }).click();
  await expect(form.locator('.consigne-apercu-jour')).toHaveCount(3);
  await expect(form.locator('.consigne-apercu-jour').first()).toContainText(
    `${ticked.length} stand(s) rouvert(s)`,
  );
  await form.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(page.getByText('Consigne enregistrée sur 3 date(s).')).toBeVisible();

  for (const day of DAYS) {
    const row = page.locator(`tr[data-date="${day}"]`);
    await expect(row).toContainText(MOTIF);
    await expect(row).toContainText('12h–18h');
    await expect(row).toContainText('repas surchargé');
    await expect(row).toContainText('à venir');
  }

  // What was written is what the preview read: three consignes, the evening
  // meal window on each, one opening per ticked stand on the window typed.
  const laid = await consignesState();
  expect(laid.consignes.map((consigne) => consigne.date).sort()).toEqual([...DAYS].sort());
  for (const consigne of laid.consignes) {
    expect(consigne.motif).toBe(MOTIF);
    expect(consigne.repas?.soirDebut).toBe('18:00:00');
    expect(consigne.repas?.soirFin).toBe('22:00:00');
    expect(consigne.repas?.justification).toBe('Les équipes mangent pendant la fermeture');
    expect(consigne.ouvertures.map((ouverture) => ouverture.standId).sort()).toEqual(ticked);
    expect(consigne.ouvertures.every((ouverture) => ouverture.debut === '18:00:00')).toBe(true);
  }
  const addedByDay = new Map(
    laid.consignes.map((consigne) => [consigne.date, consigne.creneauxAjoutes]),
  );
  // The grid of the first day stops at 20h: the window adds a 20h–22h créneau.
  expect(addedByDay.get(DAY1)?.length).toBeGreaterThan(0);
  // The edition's own meal windows never move: the override is dated.
  expect(await legalParameters()).toEqual(legalBefore);

  // Re-solved with every other day locked: still feasible, and the
  // publication names only the three days.
  await lockDaysExcept(DAYS);
  const underConsigne = await solveFromReferenceData();
  expect(underConsigne.result?.diagnostic.hardScore, 'the compensation must be staffable').toBe(0);
  await expect.poll(constraintsHardScore, { timeout: 60_000 }).toBe(0);

  const previewUnderConsigne = await publicationPreview();
  expect(previewUnderConsigne.nombreConcernes).toBeGreaterThan(0);
  const daysUnderConsigne = changedDays(previewUnderConsigne);
  expect(daysUnderConsigne.size).toBeGreaterThan(0);
  for (const day of daysUnderConsigne) {
    expect(DAYS.map(dayMonth), `a change dated ${day} is outside the consigne`).toContain(day);
  }

  // The witness' mail names the modified days — read through somebody who is
  // both concerned and seated on a day under consigne.
  const planUnderConsigne = await persistedPlan();
  const seatedUnderConsigne = new Set(
    planUnderConsigne
      .filter((seat) => DAYS.includes(seat.creneau?.date ?? ''))
      .map((seat) => seat.animateur?.id),
  );
  const concernedEmail = previewUnderConsigne.destinataires.find(
    (recipient) => !!recipient.email && seatedUnderConsigne.has(recipient.animateurId),
  )?.email;
  expect(concernedEmail, 'somebody seated under the consigne must be concerned').toBeTruthy();
  const mailsBefore = await mailCount(concernedEmail as string);
  const secondPublication = await publishByApi();
  expect(secondPublication.envoyes).toBeGreaterThan(0);
  const mail = await newestMailText(concernedEmail as string, mailsBefore);
  expect(mail).toContain('Journées aux horaires modifiés');
  expect(mail).toContain(MOTIF);
  expect((await publicationPreview()).nombreConcernes).toBe(0);

  // 4. Levée: the alert is lifted on the third day only, from its row.
  await page.goto('/consignes');
  await page.locator(`tr[data-date="${DAY3}"]`).getByRole('button', { name: 'Lever' }).click();
  const lifting = await dialogueOuvert(page);
  await expect(lifting).toContainText('Lever une consigne');
  await lifting.getByRole('button', { name: 'Aperçu' }).click();
  await expect(lifting).toContainText(dateLabel(DAY3));
  await lifting.getByRole('button', { name: 'Lever', exact: true }).click();
  await expect(page.getByText('Consigne levée.')).toBeVisible();
  await expect(page.locator(`tr[data-date="${DAY3}"]`)).toHaveCount(0);
  await expect(page.locator(`tr[data-date="${DAY1}"]`)).toHaveCount(1);
  await expect(page.locator(`tr[data-date="${DAY2}"]`)).toHaveCount(1);

  const remaining = await consignesState();
  expect(remaining.consignes.map((consigne) => consigne.date).sort()).toEqual([DAY1, DAY2]);
  // The créneaux the consigne added on that day are gone, the nominal grid of
  // the day is exactly what it was; the other days keep theirs.
  const gridAfterLifting = await creneaux();
  const idsAfterLifting = new Set(gridAfterLifting.map((creneau) => creneau.id));
  for (const id of addedByDay.get(DAY3) ?? []) {
    expect(idsAfterLifting.has(id), `créneau ${id} added on ${DAY3} should be gone`).toBe(false);
  }
  for (const id of addedByDay.get(DAY1) ?? []) {
    expect(idsAfterLifting.has(id), `créneau ${id} added on ${DAY1} should remain`).toBe(true);
  }
  expect(
    gridAfterLifting
      .filter((creneau) => creneau.date === DAY3)
      .map((creneau) => `${creneau.heureDebut}-${creneau.heureFin}`)
      .sort(),
  ).toEqual(nominalGridDay3);

  // The two days still under consigne are published and validated: locked
  // too, so only the lifted day moves.
  await lockDaysExcept([DAY3]);
  const afterLifting = await solveFromReferenceData();
  expect(afterLifting.result?.diagnostic.hardScore, 'the nominal day must be feasible again').toBe(
    0,
  );
  await expect.poll(constraintsHardScore, { timeout: 60_000 }).toBe(0);

  // The third day gets its nominal seats back: the same stands on the same
  // hours as before the alert — shortened only where the stand's own hours
  // shorten them — and none on the 20h–22h reopening the consigne carried.
  const planAfterLifting = await persistedPlan();
  const seatsDay3 = seatsOf(planAfterLifting, DAY3);
  expect(seatShapes(planAfterLifting, DAY3)).toEqual(nominalSeatsDay3);
  expect(seatSpans(planAfterLifting, DAY3)).toEqual(nominalSpansDay3);
  expect(seatsDay3.some((seat) => effectiveSpan(seat) === '20:00:00-22:00:00')).toBe(false);
  expect(owed(seatsDay3).every((seat) => seat.animateur !== null)).toBe(true);
  // The other two days still run under their consigne.
  expect(seatsOf(planAfterLifting, DAY1).some((seat) => !!seat.heureDebutEffective)).toBe(true);

  // And the publication announces the third day, and nothing else.
  const previewAfterLifting = await publicationPreview();
  expect(previewAfterLifting.nombreConcernes).toBeGreaterThan(0);
  const daysAfterLifting = changedDays(previewAfterLifting);
  expect([...daysAfterLifting]).toEqual([dayMonth(DAY3)]);
  const thirdPublication = await publishByApi();
  expect(thirdPublication.envoyes).toBeGreaterThan(0);
  expect((await publicationPreview()).nombreConcernes).toBe(0);
  expect(await legalParameters()).toEqual(legalBefore);

  await page.context().close();
});
