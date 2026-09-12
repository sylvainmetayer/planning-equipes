// The `/api/editions/*` reads and writes; `edition.store.ts` holds the state.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { Edition, EtatEdition } from '../models';

@Injectable({ providedIn: 'root' })
export class EditionsApi {
  private readonly api = inject(ApiService);

  /** Every edition, for the switcher and the Éditions page. */
  list(): Promise<Edition[]> {
    return this.api.get<Edition[]>('/api/editions');
  }

  /** The edition this browser works on, as the server resolved it. */
  current(): Promise<Edition> {
    return this.api.get<Edition>('/api/editions/courant');
  }

  /** The checklist of the current edition's cycle, one call for the home screen. */
  etat(): Promise<EtatEdition> {
    return this.api.get<EtatEdition>('/api/editions/courant/etat');
  }

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
