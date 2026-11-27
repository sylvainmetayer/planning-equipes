// The pure side of the Planning page: which days the plan holds, which view
// the `vue` and `mode` query params name, and how a day is keyed in the URL.

import { parseDateKey, toDateKey } from '../../core/date-utils';
import { intlLocale } from '../../core/locale';
import { PosteAffectation, RapportPauses } from '../../core/models';
import { continuedSeatIds } from '../../core/seat-places';

/**
 * The renderings of one day: the view « Jour » in its two modes (`rail`,
 * `calendrier` the table), and the map.
 */
export type JourneeView = 'calendrier' | 'rail' | 'carte';

export const JOURNEE_VIEWS: readonly JourneeView[] = ['calendrier', 'rail', 'carte'];

/**
 * The axes of the Planning page: one day (the default), the stands × days
 * grid, the people × days grid. Carried by `vue=stand|personne` — the 3.x
 * `axe` param still names them.
 */
export type PlanningAxe = 'jour' | 'stand' | 'personne';

/**
 * The four views of the page, the values of `vue`: « Jour » (rail or table),
 * « Stand », « Personne », « Carte ».
 */
export type PlanningView = 'jour' | 'stand' | 'personne' | 'carte';

export const PLANNING_VIEWS: readonly PlanningView[] = ['jour', 'stand', 'personne', 'carte'];

const GRID_AXES: readonly string[] = ['stand', 'personne'];

/**
 * Reads the axis from `vue` (`stand`, `personne`) or, for a 3.x address, from
 * `axe`; anything else is the day.
 */
export function readAxe(view: string | null, legacyAxe: string | null = null): PlanningAxe {
  if (GRID_AXES.includes(view ?? '')) {
    return view as PlanningAxe;
  }
  return GRID_AXES.includes(legacyAxe ?? '') ? (legacyAxe as PlanningAxe) : 'jour';
}

/**
 * The rendering of a grid axis (`rendu=treemap|frise|synthese`): the 3.x
 * addresses carried it in `vue`, which names the axis now.
 */
export function readRendu(query: { get(name: string): string | null }): string | null {
  const view = query.get('vue');
  return query.get('rendu') ?? (PLANNING_VIEWS.includes(view as PlanningView) ? null : view);
}

/** One event day of the plan, as the shared selector lists it. */
export interface JourEvenement {
  jour: number;
  /** ISO date; null when the créneaux carry none. */
  date: string | null;
  /** What the `date` query param carries: the ISO date, or `J<n>` for a day without one. */
  key: string;
  title: string;
}

/**
 * Reads the `vue` and `mode` query params. The view « Jour » (`vue=jour`, or no
 * `vue`) is the rail unless `mode=tableau`; the 3.x values `vue=calendrier` and
 * `vue=rail` still name their rendering; anything else is the day's rail.
 */
export function readView(value: string | null, mode: string | null = null): JourneeView {
  if ((JOURNEE_VIEWS as readonly string[]).includes(value ?? '')) {
    return value as JourneeView;
  }
  return mode === 'tableau' ? 'calendrier' : 'rail';
}

/** What the `vue` and `mode` params carry for a rendering: nothing on the default, the rail. */
export function viewParams(view: JourneeView): { vue: string | null; mode: string | null } {
  if (view === 'rail') {
    return { vue: null, mode: null };
  }
  return view === 'calendrier' ? { vue: 'jour', mode: 'tableau' } : { vue: view, mode: null };
}

/**
 * The tabs of the Planning page, read from `?onglet=`: the plan in its four
 * views (the default, no `onglet`), « Calculer » (the former Solveur page)
 * and « Versions du plan » (its former second tab).
 */
export type OngletPlanning = 'plan' | 'calculer' | 'versions';

/** `calculer` and `versions` name their tab; anything else is the plan. */
export function readOngletPlanning(value: string | null): OngletPlanning {
  return value === 'calculer' || value === 'versions' ? value : 'plan';
}

/** The day's views: « Jour » gathers the rail and the table, two modes of one view. */
export type JourneeTab = 'jour' | 'carte';

export function tabOf(view: JourneeView): JourneeTab {
  return view === 'carte' ? 'carte' : 'jour';
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
 * bookmark carrying one still lands on the right day. `jour` may name it by
 * its date too — the natural thing to type, once ignored in silence.
 */
export function requestedKey(date: string | null, jour: string | null): string | null {
  if (date) {
    return date;
  }
  if (jour && /^\d{4}-\d{2}-\d{2}$/.test(jour)) {
    return jour;
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

/**
 * The sentence saying a requested day is not one of the plan's, so the page
 * shows the first one instead: `null` when the key names a day, when nothing
 * was asked, and while the plan is not known yet.
 */
export function jourInconnu(jours: readonly JourEvenement[], key: string | null): string | null {
  if (key === null || jours.length === 0 || jourDemande(jours, key) !== null) {
    return null;
  }
  const demande = requestedDayLabel(key);
  return $localize`:@@journee.jourInconnu:Le ${demande}:jour: n'est pas un jour du planning : voici le premier jour.`;
}

/**
 * A requested key as the reader wrote it: a real date with its year — a
 * bookmark from another edition names the same day and month as a day of
 * this one —, anything else, an impossible date included, as it was typed.
 */
function requestedDayLabel(key: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(key)) {
    return key;
  }
  const date = parseDateKey(key);
  if (toDateKey(date) !== key) {
    return key;
  }
  return date.toLocaleDateString(intlLocale(), {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  });
}

function seatsOfDay(postes: readonly PosteAffectation[], jour: number): PosteAffectation[] {
  return postes.filter((poste) => poste.creneau?.jour === jour && poste.stand);
}

/** The figures of one day, as the relecture bar sums them up. */
export interface SyntheseJournee {
  /**
   * The seats to fill; null when the scope is a set of animateurs, whose
   * seats are all held by definition — an empty seat belongs to nobody.
   */
  sieges: number | null;
  pourvus: number;
  /** The empty seats; null for the same reason as {@link sieges}. */
  vides: number | null;
  /** The breaks owed that day with nobody to relieve; null when the breaks could not be read. */
  unrelievedBreaks: number | null;
  animateurs: number;
  stands: number;
}

/**
 * What the bar sums up once a filter narrows the lines: the stands of the
 * calendar lines kept, or the animateurs of the rail lines kept. Absent, the
 * whole day counts.
 */
export type SummaryScope =
  { standIds: ReadonlySet<string> } | { animateurIds: ReadonlySet<string> };

function inScope(
  scope: SummaryScope | null,
  standId: string | undefined,
  animateurId: string | undefined,
): boolean {
  if (!scope) {
    return true;
  }
  if ('standIds' in scope) {
    return standId !== undefined && scope.standIds.has(standId);
  }
  return animateurId !== undefined && scope.animateurIds.has(animateurId);
}

/**
 * One day summed up: seats, who holds them, the breaks without relief, the
 * stands open — over the whole day, or over the {@link SummaryScope} the
 * filtered lines leave, so the banner and the lines under it tell one story.
 */
export function syntheseJournee(
  postes: readonly PosteAffectation[],
  jour: number,
  pauses: RapportPauses | null,
  scope: SummaryScope | null = null,
): SyntheseJournee {
  const daySeats = seatsOfDay(postes, jour).filter((poste) =>
    inScope(scope, poste.stand?.id, poste.animateur?.id),
  );
  // A seat split on the day (ADR 0066) is one place, counted on its
  // continuation; whoever held its first part was still there that day.
  const continued = continuedSeatIds(postes);
  const places = daySeats.filter((poste) => !continued.has(poste.id));
  const pourvus = places.filter((poste) => poste.animateur).length;
  let unrelievedBreaks: number | null = null;
  if (pauses) {
    unrelievedBreaks = 0;
    for (const journee of pauses.journees) {
      if (journee.jour !== jour) {
        continue;
      }
      for (const sequence of journee.sequences) {
        unrelievedBreaks += sequence.pausesDues.filter(
          (pause) => !pause.relaisDisponible && inScope(scope, pause.standId, journee.animateurId),
        ).length;
      }
    }
  }
  const animateurs = new Set(
    daySeats.flatMap((poste) => (poste.animateur ? [poste.animateur.id] : [])),
  );
  // Scoped to animateurs, only held seats are left: counting them as the seats
  // to fill would claim a day without a single empty seat.
  const seatsKnown = !scope || 'standIds' in scope;
  return {
    sieges: seatsKnown ? places.length : null,
    pourvus,
    vides: seatsKnown ? places.length - pourvus : null,
    unrelievedBreaks,
    animateurs: animateurs.size,
    stands: new Set(daySeats.map((poste) => poste.stand?.id)).size,
  };
}
