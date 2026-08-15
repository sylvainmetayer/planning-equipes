// Planning locks (issue #87): the validated parts of the schedule the solver
// must not touch again. Kept out of `ReferenceDataStore` on purpose — the
// calendars read the locks to show a padlock, and they must not pull the whole
// reference dataset to do it.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { GroupeCreneau, TypeVerrouillage, VerrouillagePlanning } from './models';

@Injectable({ providedIn: 'root' })
export class VerrouillageStore {
  /** Every lock, all groupes de créneaux included. */
  readonly verrouillages = signal<VerrouillagePlanning[]>([]);
  readonly groupeActifId = signal<string | null>(null);

  /**
   * The locks the next solve will actually apply: those of the active groupe
   * de créneaux. The others stay visible on the management page, flagged as
   * dormant, rather than silently disappearing.
   */
  readonly actifs = computed(() =>
    this.verrouillages().filter((verrouillage) => verrouillage.groupeCreneauId === this.groupeActifId())
  );

  private readonly api = inject(ApiService);

  async reload(): Promise<void> {
    const [verrouillages, groupes] = await Promise.all([
      this.api.get<VerrouillagePlanning[]>('/api/verrouillages'),
      this.api.get<GroupeCreneau[]>('/api/groupes-creneaux')
    ]);
    this.verrouillages.set(verrouillages);
    this.groupeActifId.set(groupes.find((groupe) => groupe.actif)?.id ?? null);
  }

  async create(verrouillage: Partial<VerrouillagePlanning> & { type: TypeVerrouillage }): Promise<void> {
    await this.api.post('/api/verrouillages', verrouillage);
    await this.reload();
  }

  async remove(id: string): Promise<void> {
    await this.api.delete(`/api/verrouillages/${encodeURIComponent(id)}`);
    await this.reload();
  }

  estJourVerrouille(jour: string | null | undefined): boolean {
    return !!jour && this.actifs().some((verrouillage) => verrouillage.type === 'JOUR' && verrouillage.jour === jour);
  }

  estStandVerrouille(standId: string | null | undefined): boolean {
    return (
      !!standId && this.actifs().some((verrouillage) => verrouillage.type === 'STAND' && verrouillage.standId === standId)
    );
  }

  estCreneauVerrouille(creneauId: number | null | undefined): boolean {
    return (
      creneauId !== null &&
      creneauId !== undefined &&
      this.actifs().some((verrouillage) => verrouillage.type === 'CRENEAU' && verrouillage.creneauId === creneauId)
    );
  }

  estAnimateurVerrouille(animateurId: string | null | undefined): boolean {
    return (
      !!animateurId &&
      this.actifs().some((verrouillage) => verrouillage.type === 'ANIMATEUR' && verrouillage.animateurId === animateurId)
    );
  }
}
