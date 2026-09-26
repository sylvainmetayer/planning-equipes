// The order of the Animateurs list, as a pure function of its view: which
// rows the quick filter, the acknowledgement modes, the game category, the
// wish, the minors and the managers keep, and in which order the sort lays
// them — the ids in their natural order when no column is chosen, and to break
// a tie. Shared by the list and by
// the fiche, whose « précédent / suivant » walks the very list the reader came
// from — the same view read from the same URL keys, so the two cannot disagree.

import { ParamMap, Params } from '@angular/router';
import { intlLocale } from '../../core/locale';
import {
  Animateur,
  ConfirmationView,
  PlanningEvenement,
  StatutConfirmation,
} from '../../core/models';
import { compareNatural } from '../../core/table-sort';
import { correspondAuFiltre } from '../../core/text-filter';
import { typologieLabel } from '../../core/typologie-colors';
import {
  NO_SORT,
  SortState,
  optionalParam,
  readSort,
  sortQueryParams,
} from '../../core/view-query-params';
import {
  ModeAccuses,
  SILENCE_JOURS_DEFAUT,
  keptByAcknowledgement,
  readModeAccuses,
  readNeverReminded,
} from './confirmation-filter';

/** What narrows and orders the list — everything the list keeps in its URL. */
export interface RosterView {
  sort: SortState;
  filtre: string;
  accuses: ModeAccuses;
  silenceJours: number;
  neverReminded: boolean;
  /** Typologie ids, comma-separated: only the animateurs appreciated on one of them. */
  typologie: string;
  /** A typologie id: only the animateurs who wished it. */
  souhait: string;
  /** Only the people who are minors on the edition's first day. */
  mineurs: boolean;
  /** Only the managers. */
  managers: boolean;
}

/** The whole referential, in the order the store holds it. */
export const WHOLE_ROSTER: RosterView = {
  sort: NO_SORT,
  filtre: '',
  accuses: 'tous',
  silenceJours: SILENCE_JOURS_DEFAUT,
  neverReminded: false,
  typologie: '',
  souhait: '',
  mineurs: false,
  managers: false,
};

/** Reads the list's view from its query params; anything unreadable is the default. */
export function readRosterView(params: ParamMap): RosterView {
  const accuses = readModeAccuses(params.get('confirmation'), params.get('silence'));
  return {
    sort: readSort(params),
    filtre: params.get('q') ?? '',
    accuses: accuses.mode,
    silenceJours: accuses.jours,
    neverReminded: readNeverReminded(params.get('relance')),
    typologie: params.get('typologie') ?? '',
    souhait: params.get('souhait')?.trim() ?? '',
    mineurs: params.get('mineurs') === '1',
    managers: params.get('manager') === '1',
  };
}

/** The inverse of {@link readRosterView}: a default writes nothing. */
export function rosterViewParams(view: RosterView): Params {
  return {
    ...sortQueryParams(view.sort),
    q: optionalParam(view.filtre),
    confirmation: view.accuses === 'jamais' ? 'jamais' : null,
    silence: view.accuses === 'silence' ? String(view.silenceJours) : null,
    relance: view.accuses !== 'tous' && view.neverReminded ? 'jamais' : null,
    typologie: optionalParam(view.typologie),
    souhait: optionalParam(view.souhait),
    mineurs: view.mineurs ? '1' : null,
    manager: view.managers ? '1' : null,
  };
}

/** The same params without their `null`s, for a `routerLink`'s `queryParams`. */
export function rosterLinkParams(view: RosterView): Params {
  return Object.fromEntries(
    Object.entries(rosterViewParams(view)).filter(([, value]) => value !== null),
  );
}

/** True when the view needs the acknowledgements to filter or to sort. */
export function needsConfirmations(view: RosterView): boolean {
  return view.accuses !== 'tous' || view.sort.active === 'confirmation';
}

/** True when the view sorts on the seats of the persisted plan. */
export function needsSeats(view: RosterView): boolean {
  return view.sort.active === 'postes';
}

/** What the filter and the sort read besides the rows themselves. */
export interface RosterContext {
  confirmations: ReadonlyMap<string, ConfirmationView>;
  lastPublishedAt: string | null;
  /** Typologie id → label, what the quick filter matches besides the id. */
  typologies: Map<string, string>;
  now: Date;
  /** The edition's first day, the day a minor is a minor on; `null` (today) without a timeslot. */
  premierJour: string | null;
  /** The seats of the persisted plan, by animateur; `null` while no plan holds anybody. */
  postes: ReadonlyMap<string, number> | null;
}

/** The edition's first day, from its timeslots' dates; `null` when there is none. */
export function firstDay(dates: readonly string[]): string | null {
  return dates.length === 0 ? null : dates.reduce((min, date) => (date < min ? date : min));
}

/** The seats of a plan counted by animateur; `null` when nobody is seated. */
export function seatCounts(planning: PlanningEvenement | null): ReadonlyMap<string, number> | null {
  const postes = new Map<string, number>();
  for (const poste of planning?.postes ?? []) {
    if (poste.animateur) {
      postes.set(poste.animateur.id, (postes.get(poste.animateur.id) ?? 0) + 1);
    }
  }
  return postes.size === 0 ? null : postes;
}

/** The rows the view keeps, in the store's order. */
export function filterRoster(
  animateurs: readonly Animateur[],
  view: RosterView,
  context: RosterContext,
): Animateur[] {
  const typologies = view.typologie
    .split(',')
    .map((id) => id.trim())
    .filter((id) => id !== '');
  return animateurs.filter(
    (animateur) =>
      keptByAcknowledgement(
        view.accuses,
        view.silenceJours,
        context.confirmations.get(animateur.id),
        context.lastPublishedAt,
        context.now,
        view.neverReminded,
      ) &&
      (typologies.length === 0 || typologies.some((id) => id in (animateur.competences ?? {}))) &&
      (view.souhait === '' || (animateur.souhaits ?? []).includes(view.souhait)) &&
      (!view.mineurs || majorite(animateur, context.premierJour) === 'mineur') &&
      (!view.managers || animateur.manager) &&
      correspondAuFiltre(view.filtre, [
        animateur.id,
        animateur.prenom,
        animateur.nom,
        // The label is what the screen shows; the id stays findable too.
        ...Object.keys(animateur.competences ?? {}).flatMap((id) => [
          id,
          typologieLabel(context.typologies, id),
        ]),
        // The acknowledgement label travels with the row so the quick filter
        // finds « relancé » or « silencieux » without a control of its own.
        confirmationLabel(context.confirmations.get(animateur.id)),
      ]),
  );
}

/**
 * The rows in the order of the chosen column, ties and the unsorted table in
 * the natural order of the ids (A2 before A10).
 */
export function sortRoster(
  animateurs: readonly Animateur[],
  sort: SortState,
  context: Pick<RosterContext, 'confirmations' | 'premierJour' | 'postes'>,
): Animateur[] {
  const { active, direction } = sort;
  const byId = (a: Animateur, b: Animateur) => compareNatural(a.id, b.id);
  if (!active || !direction) {
    return [...animateurs].sort(byId);
  }
  const factor = direction === 'asc' ? 1 : -1;
  return [...animateurs].sort(
    (a, b) => factor * compareByColumn(a, b, active, context) || byId(a, b),
  );
}

/** The whole list as the page shows it: filtered, then sorted. */
export function rosterOrder(
  animateurs: readonly Animateur[],
  view: RosterView,
  context: RosterContext,
): Animateur[] {
  return sortRoster(filterRoster(animateurs, view, context), view.sort, context);
}

/** The neighbours of `id` in `ordered`; `null` at either end, or both when `id` is not in it. */
export function neighbours(
  ordered: readonly Animateur[],
  id: string,
): { previous: Animateur | null; next: Animateur | null; rank: number } {
  const index = ordered.findIndex((animateur) => animateur.id === id);
  if (index < 0) {
    return { previous: null, next: null, rank: -1 };
  }
  return {
    previous: ordered[index - 1] ?? null,
    next: ordered[index + 1] ?? null,
    rank: index,
  };
}

/** Wording of the acknowledgement column; empty for somebody nothing was asked of. */
export function confirmationLabel(confirmation: ConfirmationView | undefined): string {
  if (!confirmation?.affecte) {
    return '';
  }
  switch (confirmation.statut) {
    case 'NON_VU':
      return $localize`:@@animateurs.confirmation.nonVu:Silencieux`;
    case 'CONFIRME':
      return $localize`:@@animateurs.confirmation.confirme:Confirmé`;
    case 'RELANCE':
      return $localize`:@@animateurs.confirmation.relance:Relancé`;
  }
}

/**
 * Order of one column, ascending. Every column here is sorted on something the
 * cell actually shows, so the result reads as sorted rather than shuffled — and
 * where the value is not a text, the ranking is chosen to put what still needs
 * doing on top of the ascending order:
 *
 *   - `age` on the age at the edition's first day, the youngest — the minors
 *     the regime protects — first;
 *   - `majorite` and `manager`, columns of a former layout still read from a
 *     bookmarked `?sort=`, are booleans: "Oui" first;
 *   - `competences`, `indisponibilites` and `postes` show a list or a count,
 *     so the count is what is compared;
 *   - `confirmation` is a status with no natural order: silencieux, then
 *     relancé, then confirmé, and last the people who were asked nothing —
 *     ascending is then "who is left to chase".
 *
 * An unknown column answers 0, which leaves the rows in source order: a link
 * carrying a `?sort=` of a column since removed degrades to an unsorted table
 * (see `core/view-query-params.ts`).
 */
export function compareByColumn(
  a: Animateur,
  b: Animateur,
  column: string,
  context: Pick<RosterContext, 'confirmations' | 'premierJour' | 'postes'>,
): number {
  switch (column) {
    case 'id':
      return compareText(a.id, b.id);
    case 'nom':
      // On the string the cell shows, not on the family name: the column reads
      // « Prénom Nom », and sorting on anything else looks broken on screen.
      return compareText(nomAffiche(a), nomAffiche(b));
    case 'age':
      return (
        (ageOn(a, context.premierJour) ?? Infinity) - (ageOn(b, context.premierJour) ?? Infinity)
      );
    case 'majorite':
      return rankMajorite(a) - rankMajorite(b);
    case 'manager':
      return rankBooleen(a.manager) - rankBooleen(b.manager);
    case 'competences':
      return Object.keys(a.competences ?? {}).length - Object.keys(b.competences ?? {}).length;
    case 'indisponibilites':
      return (a.joursIndisponibles?.length ?? 0) - (b.joursIndisponibles?.length ?? 0);
    case 'postes':
      return (context.postes?.get(a.id) ?? 0) - (context.postes?.get(b.id) ?? 0);
    case 'confirmation':
      return (
        rankConfirmation(a, context.confirmations) - rankConfirmation(b, context.confirmations)
      );
    default:
      return 0;
  }
}

/**
 * Numeric-aware and accent-insensitive: ids run A1, A2 … A10, which a plain
 * code-point comparison files as A1, A10, A2 — and « Élodie » must not land
 * after « Zoé ».
 */
function compareText(left: string, right: string): number {
  return (left ?? '').localeCompare(right ?? '', intlLocale(), {
    numeric: true,
    sensitivity: 'base',
  });
}

export function nomAffiche(animateur: Animateur): string {
  return `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim();
}

/** "Oui" first, like {@link rankMajorite}. */
function rankBooleen(valeur: boolean): number {
  return valeur ? 0 : 1;
}

function rankConfirmation(
  animateur: Animateur,
  confirmations: ReadonlyMap<string, ConfirmationView>,
): number {
  const confirmation = confirmations.get(animateur.id);
  if (!confirmation?.affecte) {
    // Nothing was asked of them: last, because there is nothing to chase.
    return 3;
  }
  return CONFIRMATION_RANKS[confirmation.statut];
}

const CONFIRMATION_RANKS: Record<StatutConfirmation, number> = {
  NON_VU: 0,
  RELANCE: 1,
  CONFIRME: 2,
};

function rankMajorite(animateur: Animateur): number {
  const statut = majorite(animateur);
  if (statut === 'majeur') {
    return 0;
  }
  if (statut === 'mineur') {
    return 1;
  }
  return 2;
}

/**
 * Age in whole years on `date` (`AAAA-MM-JJ`, today when `null`), `null`
 * without a readable birth date. Derived, never stored: the legal regime
 * hangs on the day, and the edition's first day is when it starts to apply.
 */
export function ageOn(animateur: Animateur, date: string | null): number | null {
  const dateNaissance = animateur.dateNaissance;
  if (!dateNaissance) {
    return null;
  }
  const [year, month, day] = dateNaissance.split('-').map(Number);
  if (!year || !month || !day) {
    return null;
  }
  const now = new Date();
  const [refYear, refMonth, refDay] = date
    ? date.split('-').map(Number)
    : [now.getFullYear(), now.getMonth() + 1, now.getDate()];
  let age = refYear - year;
  if (refMonth < month || (refMonth === month && refDay < day)) {
    age -= 1;
  }
  return age;
}

export function majorite(
  animateur: Animateur,
  date: string | null = null,
): 'majeur' | 'mineur' | 'inconnu' {
  const age = ageOn(animateur, date);
  if (age === null) {
    return 'inconnu';
  }
  return age >= 18 ? 'majeur' : 'mineur';
}
