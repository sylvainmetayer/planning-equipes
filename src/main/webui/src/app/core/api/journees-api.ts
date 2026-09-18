// One day of the plan read against a reference (issue #6): what moved since
// the last publication, or since the last solve.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ChangementsJournee } from '../models';

/** The query-string form of a reference, `publication` or `resolution`. */
export type ReferenceChangementsParam = 'publication' | 'resolution';

@Injectable({ providedIn: 'root' })
export class JourneesApi {
  private readonly api = inject(ApiService);

  /**
   * The day's changes. Without a reference, the server picks the publication
   * when one exists and the last solve otherwise; the answer says which.
   */
  changements(
    jour: string,
    reference: ReferenceChangementsParam | null = null,
  ): Promise<ChangementsJournee> {
    const query = reference ? `?reference=${encodeURIComponent(reference)}` : '';
    return this.api.get<ChangementsJournee>(
      `/api/journees/${encodeURIComponent(jour)}/changements${query}`,
    );
  }
}
