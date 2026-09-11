// The `/api/echanges/*` endpoints of the admin side of the foire aux échanges.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ConfigurationFoire, DemandeEchangeView, EchangeSimulation } from '../models';

@Injectable({ providedIn: 'root' })
export class EchangesApi {
  private readonly api = inject(ApiService);

  list(): Promise<DemandeEchangeView[]> {
    return this.api.get<DemandeEchangeView[]>('/api/echanges');
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
