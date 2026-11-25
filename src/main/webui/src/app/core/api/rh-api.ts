// The RH right's reads: the compliance report of an edition. The edition is in the
// path — never the X-Edition-Id header, which an RH-only account has no scope for.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { EditionAccessible, RapportConformite } from '../models';

@Injectable({ providedIn: 'root' })
export class RhApi {
  private readonly api = inject(ApiService);

  /** The editions the caller may read the report of. */
  editions(): Promise<EditionAccessible[]> {
    return this.api.get<EditionAccessible[]>('/api/rh/editions');
  }

  rapport(editionId: string): Promise<RapportConformite> {
    return this.api.get<RapportConformite>(
      `/api/rh/editions/${encodeURIComponent(editionId)}/conformite`,
    );
  }

  /** Saves the report as a PDF, the document shown to an inspector. */
  pdf(editionId: string): Promise<string> {
    return this.api.downloadGet(
      `/api/rh/editions/${encodeURIComponent(editionId)}/conformite.pdf`,
      'rapport-conformite.pdf',
      'application/pdf',
    );
  }

  /** Saves the report as a CSV, for reprocessing. */
  csv(editionId: string): Promise<string> {
    return this.api.downloadGet(
      `/api/rh/editions/${encodeURIComponent(editionId)}/conformite.csv`,
      'rapport-conformite.csv',
      'text/csv',
    );
  }
}
