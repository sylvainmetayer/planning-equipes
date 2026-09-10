// The `/api/editions/*` writes — the reads go through `edition.store.ts`.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { Edition } from '../models';

@Injectable({ providedIn: 'root' })
export class EditionsApi {
  private readonly api = inject(ApiService);

  /** A new edition, empty or duplicated from `source` — the variant of an edition is another edition. */
  create(target: unknown, source: string | null): Promise<Edition> {
    const url = source ? `/api/editions/${encodeURIComponent(source)}/dupliquer` : '/api/editions';
    return this.api.post<Edition>(url, target);
  }

  rename(editionId: string, nom: string): Promise<unknown> {
    return this.api.put(`/api/editions/${encodeURIComponent(editionId)}`, { nom });
  }

  /** The edition every request without an explicit one lands in. */
  setDefault(editionId: string): Promise<unknown> {
    return this.api.put(`/api/editions/${encodeURIComponent(editionId)}/defaut`, {});
  }

  delete(editionId: string): Promise<void> {
    return this.api.delete(`/api/editions/${encodeURIComponent(editionId)}`);
  }
}
