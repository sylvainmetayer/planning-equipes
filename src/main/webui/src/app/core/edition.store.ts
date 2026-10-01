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
import { Edition, EditionSituation } from './models';
import { setStoredEditionIdAndOpen, setStoredEditionIdAndReload } from './edition-courante';

@Injectable({ providedIn: 'root' })
export class EditionStore {
  private readonly _editions = signal<Edition[]>([]);
  readonly editions = this._editions.asReadonly();

  /**
   * What the server says this browser's requests are resolved to. A stored
   * id naming a since-deleted edition is refused (`EDITION_INCONNUE`, ADR
   * 0072), which `editionInterceptor` answers by choosing again.
   */
  private readonly _courant = signal<Edition | null>(null);
  readonly courant = this._courant.asReadonly();

  readonly autres = computed(() =>
    this.editions().filter((edition) => edition.id !== this.courant()?.id),
  );

  private readonly _situations = signal<EditionSituation[]>([]);
  /**
   * What the editions' state asks of the organiser today — the active edition
   * is over, or an inactive one starts within a few days. The shell's strip
   * shows a banner when it is not empty; the Éditions page gives the detail.
   */
  readonly situations = this._situations.asReadonly();

  /** The one edition allowed to reach outside, `null` between two events. */
  readonly active = computed(() => this.editions().find((edition) => edition.active) ?? null);

  private readonly editionsApi = inject(EditionsApi);

  async reload(): Promise<void> {
    const [editions, courant] = await Promise.all([
      this.editionsApi.list(),
      this.editionsApi.current(),
    ]);
    this._editions.set(editions);
    this._courant.set(courant);
    // A reminder, never a blocker: a failure leaves the banner off.
    this._situations.set(await this.editionsApi.situations().catch(() => []));
  }

  /** Switches edition; every screen is swapped at once by the page reload this triggers. */
  basculer(edition: Edition): void {
    setStoredEditionIdAndReload(edition.id);
  }

  /**
   * Switches this browser to `editionId` and opens `url` there: what a link
   * to a fiche of another edition does. Every tab of this browser follows on
   * its next load, as with {@link basculer}.
   */
  openIn(editionId: string, url: string): void {
    setStoredEditionIdAndOpen(editionId, url);
  }
}
