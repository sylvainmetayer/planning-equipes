// « Ajouter un stand », guided: identity, game categories, location, then the
// stand's hours — the edition's timeslot windows, each open with a headcount
// or unticked, and the weekdays it opens on. No rule to write and no syntax to
// learn: the choices become the cells of the stand's row of the Horaires des
// stands grid, which the server compacts into rules as it does for that grid.
// Pure, so the translation from choices to cells is tested without rendering.

import { formatHeure } from '../../core/time-of-day';
import { JourAmplitude } from '../../core/models';
import { Cellules, ColonneGrille, libelleColonne } from '../ouvertures/grille-horaires';

/** One window of the edition's timeslots, as the last step offers it. */
export interface CreationWindow {
  /** `HH:mm-HH:mm`. */
  key: string;
  label: string;
  open: boolean;
  effectif: number;
}

/** One weekday the event has a date on. `0` is Sunday, as `Date.getUTCDay` counts. */
export interface CreationWeekday {
  day: number;
  open: boolean;
}

function windowKey(colonne: ColonneGrille): string {
  return `${formatHeure(colonne.heureDebut)}-${formatHeure(colonne.heureFin)}`;
}

/** The weekday of an ISO date, `0` for Sunday — read in UTC, so no timezone moves it. */
export function weekdayOf(date: string): number {
  return new Date(`${date}T00:00:00Z`).getUTCDay();
}

/**
 * The windows of the edition's timeslots, in time order, each ticked and at
 * `effectif`: the stand open on every timeslot, as a stand is by default —
 * the step is there to untick.
 */
export function creationWindows(
  colonnes: readonly ColonneGrille[],
  effectif: number,
): CreationWindow[] {
  const windows = new Map<string, CreationWindow>();
  for (const colonne of colonnes) {
    const key = windowKey(colonne);
    if (!windows.has(key)) {
      windows.set(key, { key, label: libelleColonne(colonne), open: true, effectif });
    }
  }
  return [...windows.values()].sort((left, right) => left.key.localeCompare(right.key));
}

/** The weekdays the event has a date on, Monday first, all ticked. */
export function creationWeekdays(jours: readonly JourAmplitude[]): CreationWeekday[] {
  const days = new Set(jours.map((jour) => weekdayOf(jour.date)));
  return [1, 2, 3, 4, 5, 6, 0].filter((day) => days.has(day)).map((day) => ({ day, open: true }));
}

/**
 * True when the last step was left as it was laid — every window open at the
 * headcount it was laid with, every weekday ticked: the stand opens as a new
 * stand does by default, on its first step's minimum and maximum, and there is
 * no grid to write. Any window touched is a grid to write: a cell holds one
 * headcount, which the stand's bounds then follow — three people everywhere
 * is a stand of three, whatever the first step said.
 */
export function isDefaultOpening(
  windows: readonly CreationWindow[],
  weekdays: readonly CreationWeekday[],
  laid: number,
): boolean {
  return (
    windows.every((window) => window.open && window.effectif === laid) &&
    weekdays.every((weekday) => weekday.open)
  );
}

/**
 * The stand's cells for the Ouvertures grid's save: under each column, the
 * window's headcount when the window is ticked and the day's weekday too,
 * closed otherwise.
 */
export function creationCells(
  standId: string,
  colonnes: readonly ColonneGrille[],
  windows: readonly CreationWindow[],
  weekdays: readonly CreationWeekday[],
): Cellules {
  const byKey = new Map(windows.map((window) => [window.key, window]));
  const openDays = new Set(
    weekdays.filter((weekday) => weekday.open).map((weekday) => weekday.day),
  );
  const row = new Map<string, number | null>();
  for (const colonne of colonnes) {
    const window = byKey.get(windowKey(colonne));
    const open = !!window?.open && openDays.has(weekdayOf(colonne.date)) && window.effectif > 0;
    row.set(colonne.colonneId, open ? window.effectif : null);
  }
  return new Map([[standId, row]]);
}

/** The headcounts the ticked windows ask for: the stand's bounds, before the grid derives its own. */
export function creationBounds(
  windows: readonly CreationWindow[],
  fallback: { min: number; max: number },
): { min: number; max: number } {
  const effectifs = windows
    .filter((window) => window.open && window.effectif > 0)
    .map((window) => window.effectif);
  if (effectifs.length === 0) {
    return fallback;
  }
  return { min: Math.min(...effectifs), max: Math.max(...effectifs) };
}
