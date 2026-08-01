// Tracks which groupe de créneaux the last persisted solve was computed for,
// and the currently active group, so every créneaux-related screen can warn
// when the two have diverged (the persisted planning is then stale for the
// active group and the solver must be relaunched).
//
// Self-contained on purpose: it fetches its own copy of `/api/groupes-creneaux`
// rather than depending on `ReferenceDataStore` being loaded, since it must
// work on screens (e.g. the calendars) that never touch that store.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { GroupeCreneau, PlanningResolution } from './models';

@Injectable({ providedIn: 'root' })
export class PlanningResolutionStore {
  readonly resolution = signal<PlanningResolution | null>(null);
  readonly groupesCreneaux = signal<GroupeCreneau[]>([]);

  readonly activeGroupe = computed(() => this.groupesCreneaux().find((groupe) => groupe.actif) ?? null);

  /** True once a solve has run and its groupe no longer matches the active one. */
  readonly stale = computed(() => {
    const resolution = this.resolution();
    const active = this.activeGroupe();
    return !!(resolution?.solved && active && resolution.groupeCreneauId !== active.id);
  });

  private readonly api = inject(ApiService);

  async reload(): Promise<void> {
    const [resolution, groupesCreneaux] = await Promise.all([
      this.api.get<PlanningResolution>('/api/planning/persisted/resolution'),
      this.api.get<GroupeCreneau[]>('/api/groupes-creneaux')
    ]);
    this.resolution.set(resolution);
    this.groupesCreneaux.set(groupesCreneaux);
  }
}
