// The grid of créneaux and what shapes it: `/api/creneaux/*` — see
// planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  DerivationRequest,
  DiagnosticGrille,
  RapportDerivation,
  RapportGrille,
  RapportRecurrence,
  RegleRecurrence,
} from '../models';

@Injectable({ providedIn: 'root' })
export class CreneauxApi {
  private readonly api = inject(ApiService);

  /** What the grid holds: how many vacations, over which dates, with how many meal relays. */
  diagnostic(): Promise<DiagnosticGrille> {
    return this.api.get<DiagnosticGrille>('/api/creneaux/diagnostic');
  }

  /** The verdict on the grid: anomalies, stand openings, feasibility. */
  control(): Promise<RapportGrille> {
    return this.api.get<RapportGrille>('/api/creneaux/controle');
  }

  previewDerivation(request: DerivationRequest): Promise<RapportDerivation> {
    return this.api.post<RapportDerivation>('/api/creneaux/derivation/apercu', request);
  }

  /** Writes the créneaux derived from the stands' opening hours. */
  derive(request: DerivationRequest): Promise<RapportDerivation> {
    return this.api.post<RapportDerivation>('/api/creneaux/derivation', request);
  }

  previewRecurrence(regle: RegleRecurrence): Promise<RapportRecurrence> {
    return this.api.post<RapportRecurrence>('/api/creneaux/recurrence/apercu', regle);
  }

  /** Writes the créneaux a recurring rule produces. */
  createRecurrence(regle: RegleRecurrence): Promise<RapportRecurrence> {
    return this.api.post<RapportRecurrence>('/api/creneaux/recurrence', regle);
  }
}
