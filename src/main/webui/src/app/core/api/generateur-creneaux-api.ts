// The one timeslot generator: `/api/generateur-creneaux/*` (ADR 0126) — its
// templates (« gabarits »), their calendar, and the three gestures on the grid
// whatever the source: preview, apply, withdraw. See planning-api.ts for why
// the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  AffectationGabarit,
  DemandeGeneration,
  EtatGenerateur,
  Gabarit,
  Generation,
  ReconnaissanceGabarits,
} from '../models';

@Injectable({ providedIn: 'root' })
export class GenerateurCreneauxApi {
  private readonly api = inject(ApiService);

  /** Templates, calendar and drift, in one read. */
  etat(): Promise<EtatGenerateur> {
    return this.api.get<EtatGenerateur>('/api/generateur-creneaux');
  }

  create(gabarit: Gabarit): Promise<Gabarit> {
    return this.api.post<Gabarit>('/api/generateur-creneaux/gabarits', gabarit);
  }

  update(id: number, gabarit: Gabarit): Promise<Gabarit> {
    return this.api.put<Gabarit>(`/api/generateur-creneaux/gabarits/${id}`, gabarit);
  }

  delete(id: number): Promise<void> {
    return this.api.delete(`/api/generateur-creneaux/gabarits/${id}`);
  }

  /** The whole calendar, rewritten: a date left out is no longer governed. */
  setCalendrier(calendrier: AffectationGabarit[]): Promise<EtatGenerateur> {
    return this.api.put<EtatGenerateur>('/api/generateur-creneaux/calendrier', calendrier);
  }

  /** Dates the generator stops governing; their timeslots stay. */
  withdraw(dates: string[]): Promise<EtatGenerateur> {
    return this.api.post<EtatGenerateur>('/api/generateur-creneaux/retrait', { dates });
  }

  /** What the generation would produce, and the verdict on the resulting grid — nothing written. */
  preview(demande: DemandeGeneration): Promise<Generation> {
    return this.api.post<Generation>('/api/generateur-creneaux/apercu', demande);
  }

  /** Writes the generation: the gabarits materialise their calendar, the stands' grid is added or replaces, a recurrence adds. */
  apply(demande: DemandeGeneration): Promise<Generation> {
    return this.api.post<Generation>('/api/generateur-creneaux/application', demande);
  }

  previewReconnaissance(): Promise<ReconnaissanceGabarits> {
    return this.api.post<ReconnaissanceGabarits>(
      '/api/generateur-creneaux/reconnaissance/apercu',
      {},
    );
  }

  /** Replaces every template and the whole calendar by what the grid implies. */
  reconnaitre(): Promise<ReconnaissanceGabarits> {
    return this.api.post<ReconnaissanceGabarits>('/api/generateur-creneaux/reconnaissance', {});
  }
}
