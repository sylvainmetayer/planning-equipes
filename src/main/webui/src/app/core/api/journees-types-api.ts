// The day templates and their calendar: `/api/journees-types/*` (ADR 0032).
// See planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  AffectationJourneeType,
  EtatJourneesTypes,
  JourneeType,
  RapportApplicationJourneesTypes,
  ReconnaissanceJourneesTypes,
} from '../models';

@Injectable({ providedIn: 'root' })
export class JourneesTypesApi {
  private readonly api = inject(ApiService);

  /** Templates, calendar and drift, in one read. */
  etat(): Promise<EtatJourneesTypes> {
    return this.api.get<EtatJourneesTypes>('/api/journees-types');
  }

  create(journeeType: JourneeType): Promise<JourneeType> {
    return this.api.post<JourneeType>('/api/journees-types', journeeType);
  }

  update(id: number, journeeType: JourneeType): Promise<JourneeType> {
    return this.api.put<JourneeType>(`/api/journees-types/${id}`, journeeType);
  }

  delete(id: number): Promise<void> {
    return this.api.delete(`/api/journees-types/${id}`);
  }

  /** The whole calendar, rewritten: a date left out is no longer governed. */
  setCalendrier(calendrier: AffectationJourneeType[]): Promise<EtatJourneesTypes> {
    return this.api.put<EtatJourneesTypes>('/api/journees-types/calendrier', calendrier);
  }

  /** What applying would change, and the verdict on the result — nothing written. */
  previewApplication(): Promise<RapportApplicationJourneesTypes> {
    return this.api.post<RapportApplicationJourneesTypes>(
      '/api/journees-types/application/apercu',
      {},
    );
  }

  /** Materialises the calendar and declares the grid as vacations. */
  apply(): Promise<RapportApplicationJourneesTypes> {
    return this.api.post<RapportApplicationJourneesTypes>('/api/journees-types/application', {});
  }

  previewReconnaissance(): Promise<ReconnaissanceJourneesTypes> {
    return this.api.post<ReconnaissanceJourneesTypes>(
      '/api/journees-types/reconnaissance/apercu',
      {},
    );
  }

  /** Replaces every template and the whole calendar by what the grid implies. */
  reconnaitre(): Promise<ReconnaissanceJourneesTypes> {
    return this.api.post<ReconnaissanceJourneesTypes>('/api/journees-types/reconnaissance', {});
  }
}
