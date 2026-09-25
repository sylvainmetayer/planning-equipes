// The French public holidays of a range: `/api/jours-feries` — see
// planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { PublicHoliday } from '../models';

@Injectable({ providedIn: 'root' })
export class JoursFeriesApi {
  private readonly api = inject(ApiService);

  /** The holidays from `debut` to `fin` (`AAAA-MM-JJ`, both included, two years at most). */
  between(debut: string, fin: string): Promise<PublicHoliday[]> {
    const params = new URLSearchParams({ debut, fin });
    return this.api.get<PublicHoliday[]>(`/api/jours-feries?${params}`);
  }
}
