// Réalisé vs planifié: the gap between the published plan and the plan held,
// per stand and per elapsed day, and the measure the previous edition left.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { CellDetail, PreviousEdition, RealisedVsPlanned } from '../models';

@Injectable({ providedIn: 'root' })
export class RealiseApi {
  private readonly api = inject(ApiService);

  /** The grid, its totals and what the measure rests on. */
  report(): Promise<RealisedVsPlanned> {
    return this.api.get<RealisedVsPlanned>('/api/planning/realise');
  }

  /** One cell: the shifts of that stand and day whose holder or hours moved, holders named. */
  detail(jour: string, standId: string): Promise<CellDetail> {
    return this.api.get<CellDetail>(
      `/api/planning/realise/detail?jour=${encodeURIComponent(jour)}&stand=${encodeURIComponent(standId)}`,
    );
  }

  /** The grid as a CSV: stand, day and counters, never a person. */
  exportCsv(): Promise<string> {
    return this.api.downloadGet(
      '/api/planning/realise/export',
      'realise-vs-planifie.csv',
      'text/csv',
    );
  }

  /** The measure of the edition whose event ended last before this one's. */
  previousEdition(): Promise<PreviousEdition> {
    return this.api.get<PreviousEdition>('/api/planning/realise/precedente');
  }
}
