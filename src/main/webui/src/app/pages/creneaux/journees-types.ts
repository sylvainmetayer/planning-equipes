// The day templates read as a whole on the Créneaux page: a template typed as
// one line, a calendar edited as a list of dates. Pure functions, unit tested
// without rendering — same split as `grille-creneaux.ts` next door.

import { formatHeure } from '../../core/time-of-day';
import { normaliseHour } from '../../core/horaire-stand';
import { AffectationJourneeType, JourneeType, VacationType } from '../../core/models';

/**
 * What stops a vacations line from being sent. A day template's vacation
 * always has an end — it is the vacation itself — and carries no headcount:
 * that is each stand's business, on the openings grid.
 */
export type ErreurVacations = 'VIDE' | 'FORME' | 'HEURE' | 'DOUBLON';

export type SaisieVacations =
  | { readonly vacations: VacationType[]; readonly erreur: null; readonly morceau: null }
  | { readonly vacations: null; readonly erreur: ErreurVacations; readonly morceau: string };

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
    const relais = /^(.*\S)\s+[rR]$/.exec(morceau) ?? /^(.*?)\s*\([rR]\)$/.exec(morceau);
    if (relais) {
      couverturePause = true;
      corps = relais[1].trim();
    }
    const m = /^([^-–→]+)[-–→]\s*(.*)$/.exec(corps);
    if (!m || m[2].trim() === '') {
      return { vacations: null, erreur: 'FORME', morceau };
    }
    const heureDebut = normaliseHour(m[1]);
    const heureFin = normaliseHour(m[2]);
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

/** Every date from `du` to `au` inclusive, ISO; empty when the range is reversed or incomplete. */
export function datesDePlage(du: string, au: string): string[] {
  if (!du || !au || au < du) {
    return [];
  }
  const dates: string[] = [];
  const [annee, mois, jour] = du.split('-').map(Number);
  const courant = new Date(Date.UTC(annee, mois - 1, jour));
  for (;;) {
    const iso = courant.toISOString().slice(0, 10);
    if (iso > au) {
      break;
    }
    dates.push(iso);
    courant.setUTCDate(courant.getUTCDate() + 1);
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
  const dates = calendrier.map((affectation) => affectation.date).sort();
  return { du: dates[0], au: dates[dates.length - 1] };
}
