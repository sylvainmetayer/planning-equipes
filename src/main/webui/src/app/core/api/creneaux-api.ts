// The grid of créneaux and its verdict: `/api/creneaux/*` (the bulk generation is
// `generateur-creneaux-api.ts`) — see
// planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { DiagnosticGrille, RapportGrille } from '../models';

@Injectable({ providedIn: 'root' })
export class CreneauxApi {
  private readonly api = inject(ApiService);

  /** What the grid holds: how many vacations, over which dates. */
  diagnostic(): Promise<DiagnosticGrille> {
    return this.api.get<DiagnosticGrille>('/api/creneaux/diagnostic');
  }

  /** The verdict on the grid: anomalies, stand openings, feasibility. */
  control(): Promise<RapportGrille> {
    return this.api.get<RapportGrille>('/api/creneaux/controle');
  }
}
