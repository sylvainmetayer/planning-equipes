// The editions (`edition`) the whole referential is partitioned into, and which
// one this browser is working in.
//
// Loaded once in the app shell rather than per page: the "Édition actuelle"
// strip sits in the shell and is shown on every screen. Which edition is
// current is not server state — it is this browser's own choice, persisted in
// localStorage and sent as `X-Edition-Id` (see `edition-courante.ts`), so two
// tabs can work on two editions at the same time.

import { Injectable, computed, inject, signal } from '@angular/core';
import { EditionsApi } from './api/editions-api';
import { Edition } from './models';
import {
  clearStoredEditionId,
  getStoredEditionId,
  setStoredEditionIdAndReload,
} from './edition-courante';

@Injectable({ providedIn: 'root' })
export class EditionStore {
  private readonly _editions = signal<Edition[]>([]);
  readonly editions = this._editions.asReadonly();

  /**
   * What the server says this browser's requests are actually resolved to.
   * Authoritative on purpose: a stored id naming a since-deleted edition is
   * silently answered from the default edition, and this is how the UI finds
   * out which edition it is really looking at.
   */
  private readonly _courant = signal<Edition | null>(null);
  readonly courant = this._courant.asReadonly();

  readonly autres = computed(() =>
    this.editions().filter((edition) => edition.id !== this.courant()?.id),
  );

  private readonly editionsApi = inject(EditionsApi);

  async reload(): Promise<void> {
    const [editions, courant] = await Promise.all([
      this.editionsApi.list(),
      this.editionsApi.current(),
    ]);
    this._editions.set(editions);
    this._courant.set(courant);
    // The stored choice was answered from another edition: drop it, so the
    // next reload doesn't keep sending a header the server ignores anyway.
    const stored = getStoredEditionId();
    if (stored && stored !== courant.id) {
      clearStoredEditionId();
    }
  }

  /** Switches edition; every screen is swapped at once by the page reload this triggers. */
  basculer(edition: Edition): void {
    setStoredEditionIdAndReload(edition.id);
  }
}
