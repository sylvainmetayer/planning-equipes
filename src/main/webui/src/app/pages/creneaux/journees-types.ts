// The day templates read as a whole on the Créneaux page: a template typed as
// one line, a calendar edited as a list of dates. Pure functions, unit tested
// without rendering — same split as `grille-creneaux.ts` next door.

import { formatHeure } from '../../core/time-of-day';
import { normaliseHour, splitHourRange } from '../../core/horaire-stand';
import { AffectationJourneeType, JourneeType, VacationType } from '../../core/models';
import { compareCodeUnits } from '../../core/string-order';

/**
 * What stops a vacations line from being sent. A day template's vacation
 * always has an end — it is the vacation itself — and carries no headcount:
 * that is each stand's business, on the openings grid.
 */
export type ErreurVacations = 'VIDE' | 'FORME' | 'HEURE' | 'DOUBLON';

export type SaisieVacations =
  | { readonly vacations: VacationType[]; readonly erreur: null; readonly morceau: null }
  | { readonly vacations: null; readonly erreur: ErreurVacations; readonly morceau: string };

const LINE_TERMINATOR = /[\n\r\u2028\u2029]/;

/**
 * What is left of a vacation once its meal-relay mark is taken off — a
 * trailing ` R` after a blank, or a trailing `(R)`, either case — or `null`
 * when it carries none. What precedes the mark stays on one line.
 */
function withoutRelayMark(morceau: string): string | null {
  const beforeLetter = morceau.slice(0, -1);
  const bodyBeforeLetter = beforeLetter.trimEnd();
  if (
    /[rR]$/.test(morceau) &&
    bodyBeforeLetter !== '' &&
    bodyBeforeLetter.length < beforeLetter.length &&
    !LINE_TERMINATOR.test(bodyBeforeLetter)
  ) {
    return bodyBeforeLetter;
  }
  const bodyBeforeParentheses = morceau.slice(0, -3).trimEnd();
  if (/\([rR]\)$/.test(morceau) && !LINE_TERMINATOR.test(bodyBeforeParentheses)) {
    return bodyBeforeParentheses;
  }
  return null;
}

/**
 * `09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00`: one vacation per
 * comma, a trailing `R` for a meal relay. Hours read as the stand windows do
 * (`9h`, `9h30`, `09:30`). An end at or before the start crosses midnight,
 * as a créneau does.
 */
export function parseVacations(text: string): SaisieVacations {
  const vacations: VacationType[] = [];
  const cles = new Set<string>();
  for (const brut of text.split(/[,;]/)) {
    const morceau = brut.trim();
    if (morceau === '') {
      continue;
    }
    let corps = morceau;
    let couverturePause = false;
    const relais = withoutRelayMark(morceau);
    if (relais !== null) {
      couverturePause = true;
      corps = relais.trim();
    }
    const plage = splitHourRange(corps);
    if (!plage || plage[1].trim() === '') {
      return { vacations: null, erreur: 'FORME', morceau };
    }
    const heureDebut = normaliseHour(plage[0]);
    const heureFin = normaliseHour(plage[1]);
    if (heureDebut === null || heureFin === null) {
      return { vacations: null, erreur: 'HEURE', morceau };
    }
    const cle = `${heureDebut}-${heureFin}`;
    if (cles.has(cle)) {
      return { vacations: null, erreur: 'DOUBLON', morceau };
    }
    cles.add(cle);
    vacations.push({ heureDebut, heureFin, couverturePause });
  }
  if (vacations.length === 0) {
    return { vacations: null, erreur: 'VIDE', morceau: text.trim() };
  }
  return { vacations, erreur: null, morceau: null };
}

/** The inverse of {@link parseVacations}, what the form shows when opened on a template. */
export function formatVacations(vacations: readonly VacationType[]): string {
  return vacations
    .map(
      (vacation) =>
        `${formatHeure(vacation.heureDebut)}-${formatHeure(vacation.heureFin)}${vacation.couverturePause ? ' R' : ''}`,
    )
    .join(', ');
}

/** `12:00-13:00`, plus the relay marker the chip shows — the wording of one vacation. */
export function libelleVacation(vacation: VacationType): string {
  return `${formatHeure(vacation.heureDebut)}–${formatHeure(vacation.heureFin)}`;
}

/** A day, in milliseconds UTC, or `null` for anything that is not a plain `AAAA-MM-JJ`. */
function jourUtc(date: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    return null;
  }
  const [annee, mois, jour] = date.split('-').map(Number);
  const instant = Date.UTC(annee, mois - 1, jour);
  // Date.UTC rolls 2027-02-31 over to March: only a round trip rejects it.
  return Number.isNaN(instant) || new Date(instant).toISOString().slice(0, 10) !== date
    ? null
    : instant;
}

const JOUR_MS = 24 * 60 * 60 * 1000;

/**
 * An edition longer than this is a typo, not a range — and the bound is what
 * keeps the loop finite whatever the two fields hold.
 */
const PLAGE_MAX_JOURS = 366;

/**
 * Every date from `du` to `au` inclusive, ISO; empty when the range is reversed,
 * incomplete, not a real date, or longer than {@link PLAGE_MAX_JOURS} days. The
 * `<input type="date">` pair can hand over a five-digit year, whose ISO form
 * starts with a `+` and so compares below every plain date: walking day by day
 * until the string passed the end never ended.
 */
export function datesDePlage(du: string, au: string): string[] {
  const debut = jourUtc(du);
  const fin = jourUtc(au);
  if (debut === null || fin === null || fin < debut || fin - debut >= PLAGE_MAX_JOURS * JOUR_MS) {
    return [];
  }
  const dates: string[] = [];
  for (let instant = debut; instant <= fin; instant += JOUR_MS) {
    dates.push(new Date(instant).toISOString().slice(0, 10));
  }
  return dates;
}

/**
 * The calendar with `dates` now governed by `journeeTypeId`: a date already
 * there changes template, the others are kept, and the result is sorted so
 * the table reads top to bottom.
 */
export function affecterDates(
  calendrier: readonly AffectationJourneeType[],
  dates: readonly string[],
  journeeTypeId: number,
): AffectationJourneeType[] {
  const parDate = new Map(calendrier.map((affectation) => [affectation.date, affectation]));
  for (const date of dates) {
    parDate.set(date, { date, journeeTypeId });
  }
  return [...parDate.values()].sort((a, b) => a.date.localeCompare(b.date));
}

export function retirerDate(
  calendrier: readonly AffectationJourneeType[],
  date: string,
): AffectationJourneeType[] {
  return calendrier.filter((affectation) => affectation.date !== date);
}

/** Template id → name, for the calendar rows; an unknown id reads as its number. */
export function nomJourneeType(journeesTypes: readonly JourneeType[], id: number): string {
  return journeesTypes.find((journeeType) => journeeType.id === id)?.nom ?? String(id);
}

/** The two bounds the calendar spans, or `null` when it is empty — the edition's dates, as typed here. */
export function bornesCalendrier(
  calendrier: readonly AffectationJourneeType[],
): { du: string; au: string } | null {
  if (calendrier.length === 0) {
    return null;
  }
  const dates = calendrier.map((affectation) => affectation.date).sort(compareCodeUnits);
  return { du: dates[0], au: dates.at(-1) ?? dates[0] };
}
