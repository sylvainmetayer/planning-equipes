// The pure side of the Journée page: which days the plan holds, which of the
// four renderings a query param names, and how a day is keyed in the URL.

import { PosteAffectation } from '../../core/models';

/** The four renderings of one day, and the values of the `vue` query param. */
export type JourneeView = 'calendrier' | 'rail' | 'carte' | 'pauses';

export const JOURNEE_VIEWS: readonly JourneeView[] = ['calendrier', 'rail', 'carte', 'pauses'];

/** One event day of the plan, as the shared selector lists it. */
export interface JourEvenement {
  jour: number;
  /** ISO date; null when the créneaux carry none. */
  date: string | null;
  /** What the `date` query param carries: the ISO date, or `J<n>` for a day without one. */
  key: string;
  title: string;
}

/** Reads the `vue` query param; anything unknown is the calendar, the rendering the page opens on. */
export function readView(value: string | null): JourneeView {
  return (JOURNEE_VIEWS as readonly string[]).includes(value ?? '')
    ? (value as JourneeView)
    : 'calendrier';
}

/** The key of a day in the URL: its date when it has one, its number otherwise. */
export function dayKey(jour: number, date: string | null): string {
  return date ?? `J${jour}`;
}

/**
 * The days of the plan, in order, one per distinct day number of its
 * créneaux. Built once by the page and handed to every rendering, so the
 * selector offers the same days whatever the view — the breaks report only
 * knows the days somebody works, the plan knows them all.
 */
export function planningDays(postes: readonly PosteAffectation[]): JourEvenement[] {
  const jours = new Map<number, string | null>();
  for (const poste of postes) {
    const creneau = poste.creneau;
    if (creneau && !jours.has(creneau.jour)) {
      jours.set(creneau.jour, creneau.date ?? null);
    }
  }
  return [...jours.entries()]
    .sort(([gauche], [droite]) => gauche - droite)
    .map(([jour, date]) => ({
      jour,
      date,
      key: dayKey(jour, date),
      title: date
        ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${jour}:jour: — ${date}:date:`
        : $localize`:@@calendarDay.dayTitle:Jour ${jour}:jour:`,
    }));
}

/**
 * Which day a URL asks for: `date` names it by its key; the older `jour`
 * param of the four screens this page replaced named it by its number, and a
 * bookmark carrying one still lands on the right day.
 */
export function requestedKey(date: string | null, jour: string | null): string | null {
  if (date) {
    return date;
  }
  const numero = Number(jour);
  return Number.isFinite(numero) && numero > 0 ? `J${numero}` : null;
}

/** The day a requested key resolves to, `J<n>` keys matching a dated day by number as well. */
export function jourDemande(
  jours: readonly JourEvenement[],
  key: string | null,
): JourEvenement | null {
  if (key === null) {
    return null;
  }
  const numero = /^J(\d+)$/.exec(key);
  return (
    jours.find((jour) => jour.key === key) ??
    (numero ? (jours.find((jour) => jour.jour === Number(numero[1])) ?? null) : null)
  );
}
