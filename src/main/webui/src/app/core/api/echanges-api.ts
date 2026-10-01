// The `/api/echanges/*` endpoints of the admin side of the foire aux échanges.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  ConfigurationFoire,
  DemandeEchangeView,
  EchangeSimulation,
  EchangeStatistics,
} from '../models';

/**
 * The period of the statistics: the foire window by default, the whole
 * edition, or two days (either optional).
 */
export type StatisticsPeriod =
  { kind: 'foire' } | { kind: 'edition' } | { kind: 'dates'; du: string | null; au: string | null };

/**
 * `?mesure=` of `GET /api/echanges`: what a figure of the statistics counts
 * beyond a statut — whether the colleague answered, the requests a delay was
 * measured over. The server applies it with the very predicates its figures
 * are computed with.
 */
export type ListMeasure =
  | 'repondues'
  | 'accordees'
  | 'delai-reponse'
  | 'delai-arbitrage'
  | 'delai-communication'
  | 'delai-annulation';

@Injectable({ providedIn: 'root' })
export class EchangesApi {
  private readonly api = inject(ApiService);

  /**
   * Every request, or those created between `du` and `au` (both included,
   * either optional) — cut into days by the server exactly as the statistics
   * are — and narrowed to one measure of the statistics, so a figure opens the
   * list of exactly the requests it counted.
   */
  list(
    du: string | null = null,
    au: string | null = null,
    mesure: ListMeasure | null = null,
  ): Promise<DemandeEchangeView[]> {
    const params = new URLSearchParams({ du: du ?? '', au: au ?? '', mesure: mesure ?? '' });
    dropEmpty(params);
    return this.api.get<DemandeEchangeView[]>(`/api/echanges?${params}`);
  }

  /**
   * The statistics over a period. « Choisir les dates » with neither bound is
   * the whole edition, said as such: without `periode=edition` the server
   * would fall back to the foire window, which is not what the screen shows.
   */
  statistics(period: StatisticsPeriod): Promise<EchangeStatistics> {
    const dates = period.kind === 'dates' ? period : null;
    const wholeEdition = period.kind === 'edition' || (dates !== null && !dates.du && !dates.au);
    const params = new URLSearchParams({
      du: dates?.du ?? '',
      au: dates?.au ?? '',
      periode: wholeEdition ? 'edition' : '',
    });
    dropEmpty(params);
    return this.api.get<EchangeStatistics>(`/api/echanges/statistiques?${params}`);
  }

  configuration(): Promise<ConfigurationFoire> {
    return this.api.get<ConfigurationFoire>('/api/echanges/configuration');
  }

  saveConfiguration(configuration: {
    foireOuverte: boolean;
    debut: string | null;
    fin: string | null;
  }): Promise<ConfigurationFoire> {
    return this.api.put<ConfigurationFoire>('/api/echanges/configuration', configuration);
  }

  /** What accepting this request would do to the plan, without doing it. */
  impact(demandeId: number | string): Promise<EchangeSimulation> {
    return this.api.get<EchangeSimulation>(`/api/echanges/${demandeId}/impact`);
  }

  /**
   * `acceptation` or `refus`, with the administrator's word for the
   * animateurs. The two are the last segment of the route, so the type is
   * the contract: a third word would compile and answer 404.
   */
  decide(
    demandeId: number | string,
    action: 'acceptation' | 'refus',
    commentaire: string | null,
  ): Promise<DemandeEchangeView> {
    return this.api.post<DemandeEchangeView>(`/api/echanges/${demandeId}/${action}`, {
      commentaire,
    });
  }
}

/**
 * The params with the empty ones left out: an empty `du` is no bound, not a
 * date the server would refuse. Built from a `URLSearchParams` literal so that
 * `api-contract-check` can still read every parameter name.
 */
function dropEmpty(params: URLSearchParams): void {
  for (const [key, value] of [...params]) {
    if (!value) {
      params.delete(key);
    }
  }
}
