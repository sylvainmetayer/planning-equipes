// The day columns of the Planning page's grids, read from the page's own days:
// the weekday initial, the week-end and the week bands that turn a row of
// « J1 J2 J3 … » back into a calendar. The Jours de repos grid computed them
// for itself; both axes read them from here now.

import { parseDateKey } from '../../core/date-utils';
import { intlLocale } from '../../core/locale';
import { JourEvenement } from '../journee/journee';
import { JourGrille, LigneGrille, PiedGrille } from './planning-grille';

/**
 * The calendar week an ISO date falls in, as the day number of its Monday.
 * Counted in UTC days, so no daylight-saving change shifts it.
 */
function mondayOf(date: string): number {
  const [year, month, day] = date.split('-').map(Number);
  const days = Date.UTC(year, month - 1, day) / 86_400_000;
  const weekday = new Date(days * 86_400_000).getUTCDay();
  return days - ((weekday + 6) % 7);
}

/**
 * One column per day of the plan, in order. Dates make the three marks exact:
 * a week starts on the first column of a new calendar week, which is not
 * always a Monday — the days are the ones holding seats, and an event closed
 * on Mondays has none. Without dates the band is cut every seventh column so
 * the eye still has a step to count by, and the initial stays empty rather
 * than being guessed.
 */
export function joursGrille(jours: readonly JourEvenement[]): JourGrille[] {
  const initiales = new Intl.DateTimeFormat(intlLocale(), { weekday: 'narrow' });
  return jours.map((jour, index) => {
    const jourSemaine = jour.date ? parseDateKey(jour.date).getDay() : null;
    const previous = index > 0 ? jours[index - 1].date : null;
    const newWeek =
      jour.date && previous ? mondayOf(jour.date) !== mondayOf(previous) : index % 7 === 0;
    return {
      key: jour.key,
      label: $localize`:@@heatmap.dayColumn:J${jour.jour}:jour:`,
      initiale: jour.date ? initiales.format(parseDateKey(jour.date)) : '',
      titre: jour.title,
      weekEnd: jourSemaine === 0 || jourSemaine === 6,
      // Never on the first column: the grid's own edge is already there.
      debutSemaine: index > 0 && newWeek,
    };
  });
}

/** `Prénom N.`: a cell of the grid holds several names in a day column's width. */
export function nomCourt(
  prenom: string | null | undefined,
  nom: string | null | undefined,
  id: string,
): string {
  const premier = (prenom ?? '').trim();
  const dernier = (nom ?? '').trim();
  if (!premier && !dernier) {
    return id;
  }
  if (!dernier) {
    return premier;
  }
  const initiale = `${dernier.charAt(0)}.`;
  return premier ? `${premier} ${initiale}` : dernier;
}

/**
 * How many days the two grids show around the page's day (`?portee=`): the
 * day alone, its week — the default — or the whole event. A hundred and fifty
 * people over three weeks of columns is a sheet nobody reads on a screen; the
 * week keeps the rhythm a grid is for, the day gives each cell the width of
 * the page.
 */
export type GridSpan = 'jour' | 'semaine' | 'evenement';

export function readGridSpan(value: string | null): GridSpan {
  return value === 'jour' || value === 'evenement' ? value : 'semaine';
}

/** The id of the note saying so, which the density switches are described by. */
export const COLOUR_NOTE_ID = 'planning-grille-couleur-imposee';

/** Why the density switch is off over the whole event, where every cell holds its colour alone. */
export function colourForcedLabel(): string {
  return $localize`:@@planningGrille.couleurImposee:Sur tout l'événement, les cases n'affichent que leur couleur : le détail se lit sur la semaine ou le jour`;
}

/** The columns a span keeps, `[start, end)`. */
export interface DayWindow {
  start: number;
  end: number;
}

/**
 * The columns around the marked day — the first one when none is marked. A
 * week is cut on the very bands the header draws (`debutSemaine`), so the
 * first and the last week of an event may be short: they are the event's.
 */
export function spanWindow(
  columns: readonly JourGrille[],
  marked: string | null,
  span: GridSpan,
): DayWindow {
  if (span === 'evenement' || columns.length === 0) {
    return { start: 0, end: columns.length };
  }
  const found = columns.findIndex((column) => column.key === marked);
  const index = Math.max(found, 0);
  if (span === 'jour') {
    return { start: index, end: index + 1 };
  }
  let start = index;
  while (start > 0 && !columns[start].debutSemaine) {
    start--;
  }
  let end = index + 1;
  while (end < columns.length && !columns[end].debutSemaine) {
    end++;
  }
  return { start, end };
}

/**
 * A grid narrowed to a window: each line's day cells and the footer's day
 * totals. The summary columns stay those of the whole event — they are
 * computed once, before any window, and a window never changes them.
 */
export function sliceDays<
  L extends LigneGrille,
  G extends { lignes: readonly L[]; pied: PiedGrille },
>(grid: G, window: DayWindow): G {
  return {
    ...grid,
    lignes: grid.lignes.map((ligne) => ({
      ...ligne,
      cases: ligne.cases.slice(window.start, window.end),
    })),
    pied: { ...grid.pied, cases: grid.pied.cases.slice(window.start, window.end) },
  };
}
