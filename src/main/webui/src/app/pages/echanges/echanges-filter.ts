// The « à arbitrer » view of the swap requests screen: what the home screen's
// « À traiter aujourd'hui » counts, and what its link opens. Kept apart from
// the page so the rule is pinned down without rendering it.

import type { ParamMap, Params } from '@angular/router';
import type { ListMeasure } from '../../core/api/echanges-api';
import type { DemandeEchangeView, StatutDemandeEchange } from '../../core/models';
import { optionalParam } from '../../core/view-query-params';

/** The tabs of the screen: the requests, the default, and the statistics of the foire. */
export type EchangesTab = 'liste' | 'stats';

/** Reads `onglet`; anything but `stats` is the list, the tab the screen opens on. */
export function readEchangesTab(value: string | null): EchangesTab {
  return value === 'stats' ? 'stats' : 'liste';
}

/** The value of the `statut` query param that narrows the screen to the requests to arbitrate. */
export const TO_ARBITRATE = 'a-arbitrer';

/** Tolerant reading: anything but {@link TO_ARBITRATE} is the whole screen, never an error. */
export function readToArbitrate(statut: string | null): boolean {
  return statut === TO_ARBITRATE;
}

/**
 * Since when a request has waited on the organisation: the colleague's
 * agreement, which is what put it on the desk, or its creation for one that
 * predates the two-step flow — the same reading as the nightly alert's.
 */
export function waitingSince(demande: DemandeEchangeView): string {
  return demande.cibleDecideLe ?? demande.creeLe;
}

/** The requests, the one waiting the longest first. */
export function oldestWaitingFirst<T extends DemandeEchangeView>(demandes: readonly T[]): T[] {
  return [...demandes].sort((a, b) => waitingSince(a).localeCompare(waitingSince(b)));
}

/**
 * The narrowing a figure of the statistics opens the list with, carried by the
 * URL so the link is the filter. Every criterion is optional, and they add up.
 * The creation period (`du`, `au`) and the measure (`mesure`) are the
 * server's to apply — it cuts the days in the same zone as the statistics,
 * and owns the rules of the figures — so they live beside this, on the page.
 */
export interface ListFilter {
  /** `?statuts=ACCEPTEE,REFUSEE`; empty = every statut. */
  statuts: readonly StatutDemandeEchange[];
  /** `?dirigees=1`: the requests naming the seat wanted in return. */
  directed: boolean;
  /** `?prevalidee=oui|non`: the verdict of the prevalidation at submission. */
  prevalidated: 'oui' | 'non' | null;
  /** `?jour=AAAA-MM-JJ`: the date of the seat given up; `retire` for a timeslot since deleted. */
  day: string | null;
  /** `?stand=<id>`: the stand of the seat given up. */
  stand: string | null;
  /** `?contrainte=<description>`: a hard constraint the request broke at submission. */
  constraint: string | null;
}

export const NO_LIST_FILTER: ListFilter = {
  statuts: [],
  directed: false,
  prevalidated: null,
  day: null,
  stand: null,
  constraint: null,
};

/** Every `?mesure=` the server knows; see {@link ListMeasure}. */
const MEASURES: readonly ListMeasure[] = [
  'repondues',
  'accordees',
  'delai-reponse',
  'delai-arbitrage',
  'delai-communication',
  'delai-annulation',
];

/** Tolerant reading: an unknown measure is no measure, never an error. */
export function readMeasure(value: string | null): ListMeasure | null {
  return MEASURES.find((measure) => measure === value) ?? null;
}

/** The value of `?jour=` for the requests whose timeslot no longer exists. */
export const REMOVED_TIMESLOT = 'retire';

const STATUTS: ReadonlySet<StatutDemandeEchange> = new Set<StatutDemandeEchange>([
  'EN_ATTENTE_CIBLE',
  'PROPOSEE',
  'ACCEPTEE',
  'REFUSEE',
  'REFUSEE_CIBLE',
  'ANNULEE',
]);

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Tolerant reading: an unknown statut or a malformed day is dropped, never an error. */
export function readListFilter(params: ParamMap): ListFilter {
  const statuts = (params.get('statuts') ?? '')
    .split(',')
    .filter((value): value is StatutDemandeEchange => STATUTS.has(value as StatutDemandeEchange));
  const prevalidated = params.get('prevalidee');
  const day = params.get('jour');
  return {
    statuts,
    directed: params.get('dirigees') === '1',
    prevalidated: prevalidated === 'oui' || prevalidated === 'non' ? prevalidated : null,
    day: day === REMOVED_TIMESLOT || (day !== null && DAY.test(day)) ? day : null,
    stand: optionalParam(params.get('stand')),
    constraint: optionalParam(params.get('contrainte')),
  };
}

/** A creation day of the URL, `null` unless it reads as `AAAA-MM-JJ`. */
export function readDay(value: string | null): string | null {
  return value !== null && DAY.test(value) ? value : null;
}

/** The inverse of {@link readListFilter}: a default criterion clears its param. */
export function listFilterParams(filter: ListFilter): Params {
  return {
    statuts: filter.statuts.length > 0 ? filter.statuts.join(',') : null,
    dirigees: filter.directed ? '1' : null,
    prevalidee: filter.prevalidated,
    jour: filter.day,
    stand: filter.stand,
    contrainte: filter.constraint,
  };
}

export function isListFiltered(filter: ListFilter): boolean {
  return (
    filter.statuts.length > 0 ||
    filter.directed ||
    filter.prevalidated !== null ||
    filter.day !== null ||
    filter.stand !== null ||
    filter.constraint !== null
  );
}

/** Whether one request passes every criterion of the filter. */
export function matchesListFilter(demande: DemandeEchangeView, filter: ListFilter): boolean {
  if (filter.statuts.length > 0 && !filter.statuts.includes(demande.statut)) {
    return false;
  }
  if (filter.directed && demande.creneauCibleId === null) {
    return false;
  }
  if (filter.prevalidated === 'oui' && demande.prevalidationOk !== true) {
    return false;
  }
  if (filter.prevalidated === 'non' && demande.prevalidationOk !== false) {
    return false;
  }
  if (
    filter.day === REMOVED_TIMESLOT
      ? demande.date !== null
      : filter.day !== null && demande.date !== filter.day
  ) {
    return false;
  }
  if (filter.stand !== null && demande.standId !== filter.stand) {
    return false;
  }
  return filter.constraint === null || demande.contraintesViolees.includes(filter.constraint);
}
