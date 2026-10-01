// What the Statistiques tab of the swap requests screen reads off the server's
// aggregates: a rate said honestly for its population, a delay in words, the
// geometry of the histogram, and the link from a figure to the requests it
// counts. Pure, so each rule is pinned down without rendering the tab.

import type { Params } from '@angular/router';
import type { ListMeasure, StatisticsPeriod } from '../../core/api/echanges-api';
import type { EchangeDayCount, EchangeRate, EchangeStatistics } from '../../core/models';
import { ListFilter, NO_LIST_FILTER, listFilterParams } from './echanges-filter';

/**
 * Below this many requests, a percentage lies — 67 % of three is two people —
 * so the rate is shown as « 2 sur 3 » alone.
 */
export const SMALL_POPULATION = 5;

export type RateReading =
  | { kind: 'empty' }
  | { kind: 'raw'; numerator: number; denominator: number }
  | { kind: 'percent'; percent: number; numerator: number; denominator: number };

export function readRate(rate: EchangeRate): RateReading {
  if (rate.denominateur === 0) {
    return { kind: 'empty' };
  }
  if (rate.denominateur < SMALL_POPULATION) {
    return { kind: 'raw', numerator: rate.numerateur, denominator: rate.denominateur };
  }
  return {
    kind: 'percent',
    percent: Math.round((100 * rate.numerateur) / rate.denominateur),
    numerator: rate.numerateur,
    denominator: rate.denominateur,
  };
}

/** A delay in the unit a person would say it in: minutes, hours, then days. */
export function formatDelay(seconds: number): string {
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return $localize`:@@echanges.stats.duree.minutes:${minutes}:minutes: min`;
  }
  const hours = Math.round(seconds / 3600);
  if (hours < 48) {
    return $localize`:@@echanges.stats.duree.heures:${hours}:heures: h`;
  }
  const days = Math.round(seconds / 86400);
  return $localize`:@@echanges.stats.duree.jours:${days}:jours: j`;
}

/** The period the statistics were computed over, in a sentence; `day` words an `AAAA-MM-JJ`. */
export function describePeriod(stats: EchangeStatistics, day: (iso: string) => string): string {
  const from = stats.du ? day(stats.du) : null;
  const to = stats.au ? day(stats.au) : null;
  if (from && to) {
    return stats.fenetreFoire
      ? $localize`:@@echanges.stats.periode.fenetre:Fenêtre de la foire, du ${from}:du: au ${to}:au:`
      : $localize`:@@echanges.stats.periode.entre:Demandes créées du ${from}:du: au ${to}:au:`;
  }
  if (from) {
    return stats.fenetreFoire
      ? $localize`:@@echanges.stats.periode.fenetreDepuis:Fenêtre de la foire, depuis le ${from}:du:`
      : $localize`:@@echanges.stats.periode.depuis:Demandes créées depuis le ${from}:du:`;
  }
  if (to) {
    return stats.fenetreFoire
      ? $localize`:@@echanges.stats.periode.fenetreJusquau:Fenêtre de la foire, jusqu'au ${to}:au:`
      : $localize`:@@echanges.stats.periode.jusquau:Demandes créées jusqu'au ${to}:au:`;
  }
  return $localize`:@@echanges.stats.periode.edition:Toute l'édition`;
}

/** One bar of the creation histogram, in the coordinates of its `viewBox`. */
export interface HistogramBar {
  day: string;
  count: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

export const HISTOGRAM_HEIGHT = 120;
const BAR_STEP = 14;
const BAR_GAP = 3;

/** The bars of the histogram, one per day, the highest filling the height. */
export function histogramBars(days: readonly EchangeDayCount[]): HistogramBar[] {
  const highest = Math.max(1, ...days.map((day) => day.nombre));
  return days.map((day, index) => {
    // A day with requests keeps a visible stub, however small next to the peak.
    const height =
      day.nombre === 0 ? 0 : Math.max(2, Math.round((HISTOGRAM_HEIGHT * day.nombre) / highest));
    return {
      day: day.jour,
      count: day.nombre,
      x: index * BAR_STEP,
      y: HISTOGRAM_HEIGHT - height,
      width: BAR_STEP - BAR_GAP,
      height,
    };
  });
}

export function histogramWidth(days: readonly EchangeDayCount[]): number {
  return Math.max(BAR_STEP, days.length * BAR_STEP);
}

/** The highest day of the histogram, what its scale is read against. */
export function histogramPeak(days: readonly EchangeDayCount[]): number {
  return Math.max(0, ...days.map((day) => day.nombre));
}

/** What a figure narrows the list by: criteria of the list, and a measure the server applies. */
export interface ListLinkCriteria extends Partial<ListFilter> {
  measure?: ListMeasure;
}

/**
 * The query of the List tab narrowed to the requests a figure counts: the
 * creation period the statistics were computed over, plus the criteria of the
 * figure. Absent `onglet`: the list is the screen's default tab.
 */
export function listLink(
  stats: EchangeStatistics,
  criteria: ListLinkCriteria = {},
  period: { du: string | null; au: string | null } = stats,
): Params {
  const { measure, ...filter } = criteria;
  const params: Params = { ...listFilterParams({ ...NO_LIST_FILTER, ...filter }) };
  if (measure) {
    params['mesure'] = measure;
  }
  if (period.du) {
    params['du'] = period.du;
  }
  if (period.au) {
    params['au'] = period.au;
  }
  for (const key of Object.keys(params)) {
    if (params[key] === null) {
      delete params[key];
    }
  }
  return params;
}

/** `?periode=` of the Statistiques tab, read tolerantly: anything unknown is the foire window. */
export function readStatisticsPeriod(
  periode: string | null,
  du: string | null,
  au: string | null,
): StatisticsPeriod {
  if (periode === 'edition') {
    return { kind: 'edition' };
  }
  if (periode === 'dates') {
    return { kind: 'dates', du, au };
  }
  return { kind: 'foire' };
}

/** The inverse of {@link readStatisticsPeriod}: the foire window, the default, clears every key. */
export function statisticsPeriodParams(period: StatisticsPeriod): Params {
  switch (period.kind) {
    case 'foire':
      return { periode: null, du: null, au: null };
    case 'edition':
      return { periode: 'edition', du: null, au: null };
    case 'dates':
      return { periode: 'dates', du: period.du, au: period.au };
  }
}
