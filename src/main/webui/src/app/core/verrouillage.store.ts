// Planning locks (issue #87): the validated parts of the schedule the solver
// must not touch again. Kept out of `ReferenceDataStore` on purpose — the
// calendars read the locks to show a padlock, and they must not pull the whole
// reference dataset to do it.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import {
  Avertissement,
  TypeVerrouillage,
  VerrouillagePlanning,
  WrittenVerrouillage,
} from './models';

@Injectable({ providedIn: 'root' })
export class VerrouillageStore {
  /** Every lock of the edition — the ones the next solve applies. */
  private readonly _verrouillages = signal<VerrouillagePlanning[]>([]);
  readonly verrouillages = this._verrouillages.asReadonly();

  /** Alias kept for the read helpers below: every lock applies now. */
  readonly actifs = computed(() => this.verrouillages());

  private readonly api = inject(ApiService);

  async reload(): Promise<void> {
    this._verrouillages.set(await this.api.get<VerrouillagePlanning[]>('/api/verrouillages'));
  }

  /**
   * Records the lock and answers what the server wants read about it — seats it
   * freezes that already break a hard rule. The lock is written either way: a
   * warning is not a refusal (see `Avertissement`).
   */
  async create(
    verrouillage: Partial<VerrouillagePlanning> & { type: TypeVerrouillage },
  ): Promise<Avertissement[]> {
    const written = await this.api.post<WrittenVerrouillage>('/api/verrouillages', verrouillage);
    await this.reload();
    return written?.avertissements ?? [];
  }

  async remove(id: string): Promise<void> {
    await this.api.delete(`/api/verrouillages/${encodeURIComponent(id)}`);
    await this.reload();
  }

  estJourVerrouille(jour: string | null | undefined): boolean {
    return (
      !!jour &&
      this.actifs().some(
        (verrouillage) => verrouillage.type === 'JOUR' && verrouillage.jour === jour,
      )
    );
  }

  estStandVerrouille(standId: string | null | undefined): boolean {
    return (
      !!standId &&
      this.actifs().some(
        (verrouillage) => verrouillage.type === 'STAND' && verrouillage.standId === standId,
      )
    );
  }

  estCreneauVerrouille(creneauId: number | null | undefined): boolean {
    return (
      creneauId !== null &&
      creneauId !== undefined &&
      this.actifs().some(
        (verrouillage) => verrouillage.type === 'CRENEAU' && verrouillage.creneauId === creneauId,
      )
    );
  }

  estAnimateurVerrouille(animateurId: string | null | undefined): boolean {
    return (
      !!animateurId &&
      this.actifs().some(
        (verrouillage) =>
          verrouillage.type === 'ANIMATEUR' && verrouillage.animateurId === animateurId,
      )
    );
  }
}
