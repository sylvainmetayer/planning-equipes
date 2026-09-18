// The consignes of the edition (issue #4), shared by every screen that marks
// a day as « sous consigne »: the Consignes page itself, the Journée card, the
// home banner, the Créneaux and Ouvertures badges. Re-read on demand — server
// state, never persisted in the browser.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ConsignesApi } from './api/consignes-api';
import { errorMessage } from './error-message';
import { ConsigneEdition, EtatConsignes } from './models';

@Injectable({ providedIn: 'root' })
export class ConsignesStore {
  private readonly api = inject(ConsignesApi);

  /** `null` until loaded, or when the request failed (see `error`). */
  private readonly _etat = signal<EtatConsignes | null>(null);
  readonly etat = this._etat.asReadonly();
  private readonly _loading = signal(false);
  readonly loading = this._loading.asReadonly();
  private readonly _error = signal('');
  readonly error = this._error.asReadonly();

  readonly consignes = computed<ConsigneEdition[]>(() => this.etat()?.consignes ?? []);
  /** The server's today, `null` until the first read. */
  readonly aujourdhui = computed(() => this.etat()?.aujourdhui ?? null);
  /** Date → its consigne, the lookup every badge does. */
  readonly parDate = computed(
    () => new Map(this.consignes().map((consigne) => [consigne.date, consigne])),
  );
  /** Ids of every créneau a consigne added to the grid. */
  readonly creneauxAjoutes = computed(
    () => new Set(this.consignes().flatMap((consigne) => consigne.creneauxAjoutes)),
  );

  /** The consigne governing `date`, `null` on an ordinary day. */
  consigneDe(date: string | null): ConsigneEdition | null {
    return date ? (this.parDate().get(date) ?? null) : null;
  }

  async reload(): Promise<void> {
    this._loading.set(true);
    try {
      this._etat.set(await this.api.etat());
      this._error.set('');
    } catch (error) {
      this._error.set(errorMessage(error));
    } finally {
      this._loading.set(false);
    }
  }
}
